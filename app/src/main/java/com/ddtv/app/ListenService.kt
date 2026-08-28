package com.ddtv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.ddtv.app.core.BiliLiveApi
import com.ddtv.app.core.Http
import com.ddtv.app.core.LiveRecorder
import com.ddtv.app.core.Logger
import com.ddtv.app.core.RoomManager

/**
 * 在线听直播：前台服务（mediaPlayback），用 Media3 ExoPlayer 实时播放直播间音频。
 * - 取流复用 BiliLiveApi（audioOnly 优先纯音频流，失败回退常规流；qn=150 流畅省流量）
 * - HLS(http_hls+fmp4+avc) 优先，FLV(http_stream+flv+avc) 兜底
 * - 只渲染音频轨道（禁用 VIDEO），锁屏/后台持续播放，通知栏提供 播放/暂停/停止
 * - 前端经 DDTVBridge 的 startListen/stopListen/getListenStatus 控制；状态经 listener 推送 JS
 */
@UnstableApi
class ListenService : Service() {

    companion object {
        const val CHANNEL_ID = "ddtv_listen"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.ddtv.app.LISTEN_START"
        const val ACTION_TOGGLE = "com.ddtv.app.LISTEN_TOGGLE"
        const val ACTION_STOP = "com.ddtv.app.LISTEN_STOP"
        const val EXTRA_ROOM_ID = "roomId"

        /** 回调状态 listener（由 DDTVBridge 注册，把状态推给前端） */
        @Volatile var listener: ((roomId: Long, playing: Boolean, label: String) -> Unit)? = null

        @Volatile private var currentRoomId = 0L
        @Volatile var playing = false

        /** 当前收听的房间号，0=未收听 */
        fun activeRoom(): Long = currentRoomId

        fun start(context: Context, roomId: Long) {
            val i = Intent(context, ListenService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROOM_ID, roomId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (e: Exception) {
                // Android 12+ 后台限制：从后台直接拉前台服务可能被拒，回退 startService（仍能播，但无前台通知）
                Logger.w("Listen", "startForegroundService 失败，回退 startService: ${e.message}")
                try { context.startService(i) } catch (e2: Exception) {
                    Logger.e("Listen", "启动听直播服务失败: ${e2.message}")
                }
            }
        }

        fun stop(context: Context) {
            try { context.startService(Intent(context, ListenService::class.java).apply { action = ACTION_STOP }) } catch (_: Exception) {}
        }

        fun toggle(context: Context) {
            try { context.startService(Intent(context, ListenService::class.java).apply { action = ACTION_TOGGLE }) } catch (_: Exception) {}
        }

        private fun notify(roomId: Long, playing: Boolean, label: String) {
            try { listener?.invoke(roomId, playing, label) } catch (_: Exception) {}
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var reconnectCount = 0
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "在线听直播", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "实时收听直播间音频"; setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getLongExtra(EXTRA_ROOM_ID, 0L))
            ACTION_TOGGLE -> togglePause()
            ACTION_STOP -> {
                val rid = ListenService.activeRoom()
                stopPlayback(false)
                if (rid != 0L) notify(rid, false, "已停止收听")
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(roomId: Long) {
        Logger.i("Listen", "开始收听 room=$roomId 录制中=${LiveRecorder.isRecordingRoom(roomId)}")
        paused = false
        if (roomId == 0L) { stopPlayback(false); return }
        // 同一房间已在播：只把前台通知顶上来，不重建
        if (ListenService.activeRoom() == roomId && player != null) {
            playing = true
            startForeground(NOTIFICATION_ID, buildNotification(roomId, true))
            notify(roomId, true, "正在收听")
            return
        }
        // 切到新房间：先停旧播放
        stopPlayback(true)

        // 立即显示前台通知，避免 Android 12+ startForeground 未及时 → ANR
        startForeground(NOTIFICATION_ID, buildNotification(roomId, true))

        // 跟随房间清晰度设置（RoomCard.quality，默认原画）
        val qn = RoomManager.getRoom(roomId)?.quality ?: 150

        // 始终走 B 站网络取流(录制时下方按线路错开)，保证 ExoPlayer 兼容性稳定；
        // 边录边播(本地代理流)作为后续可选增强，不在默认路径(ExoPlayer 对代理流兼容性差会反复失败)

        // 取流（网络）在子线程，完成后主线程构建播放器
        Thread({
            try {
                // 纯音频流优先（ptype=8，只拉音频省流量且 ExoPlayer 稳定）；失败回退常规流
                var info = BiliLiveApi.getStreamInfo(roomId, qn = qn, audioOnly = true)
                if (info == null) {
                    Logger.w("Listen", "纯音频流获取失败，回退常规流")
                    info = BiliLiveApi.getStreamInfo(roomId, qn = qn, audioOnly = false)
                }
                if (info == null) { finishError(roomId, "获取直播流失败"); return@Thread }
                if (info.hlsUrl.isEmpty() && info.flvUrl.isEmpty()) { finishError(roomId, "该直播暂无可用线路"); return@Thread }
                // 错开只做"不同 CDN host 的 FLV"：ExoPlayer 对 B 站 HLS fmp4 直播兼容差(Source error 常见)，
                // 一律 FLV 优先；录制中优先选与录制当前线路不同 host 的 FLV 错开节点，HLS 仅兜底
                val recHost = if (LiveRecorder.isRecordingRoom(roomId)) LiveRecorder.currentStreamHost(roomId) else null
                fun hostOf(u: String): String? = Regex("""^https?://([^/]+)""").find(u)?.groupValues?.get(1)
                fun pickLine(lines: List<String>, exclude: String?): String? =
                    lines.firstOrNull { hostOf(it) != exclude } ?: lines.firstOrNull()
                val flv = pickLine(info.flvLines, recHost)
                val hls = pickLine(info.hlsLines, recHost)
                val url: String
                val isHls: Boolean
                if (flv != null) {
                    // FLV 恒优先(录制时 pickLine 已优先不同 host 的 FLV 错开节点);ExoPlayer 最稳
                    url = flv; isHls = false
                } else if (hls != null) {
                    url = hls; isHls = true
                } else {
                    finishError(roomId, "该直播暂无可用线路"); return@Thread
                }
                Logger.i("Listen", "room=$roomId 选线=${if (isHls) "HLS" else "FLV"} url=${url.take(120)} (flv=${info.flvLines.size}条 hls=${info.hlsLines.size}条 录制节点=${recHost ?: "无"})")
                mainHandler.post { prepareAndPlay(roomId, url, isHls) }
            } catch (e: Exception) {
                Logger.e("Listen", "取流异常: ${e.message}")
                finishError(roomId, "获取直播流失败")
            }
        }, "ListenStream").apply { isDaemon = true; start() }
    }

    /** 构建并启动播放器（主线程） */
    private fun prepareAndPlay(roomId: Long, url: String, isHls: Boolean) {
        try {
            currentRoomId = roomId
            playing = true
            // HLS 常规线路认 live.bilibili.com；FLV(www) 常规线路认 www.bilibili.com（对齐 LiveRecorder 成功配置）
            val referer = if (isHls) "https://live.bilibili.com/" else "https://www.bilibili.com/"
            val ds = DefaultHttpDataSource.Factory()
                .setUserAgent(Http.userAgent)
                .setDefaultRequestProperties(mapOf("Referer" to referer))
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(30000)
            val mediaSource = if (isHls) {
                HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(url))
            } else {
                ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(url))
            }
            Logger.i("Listen", "构建播放器 url=${url.take(90)} isHls=$isHls")
            val p = ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true // 自动处理音频焦点
                )
                // 只播音频：禁用视频轨道（省解码省电）
                trackSelectionParameters = TrackSelectionParameters.Builder(this@ListenService)
                    .setDisabledTrackTypes(setOf(C.TRACK_TYPE_VIDEO))
                    .build()
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> { reconnectCount = 0; Logger.i("Listen", "播放就绪(READY) room=$roomId"); notify(roomId, true, "正在收听") }
                            Player.STATE_BUFFERING -> notify(roomId, true, "缓冲中…")
                            Player.STATE_ENDED -> onLiveEnded()
                            else -> {}
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val detail = buildString {
                            append("code=").append(error.errorCode)
                            append(" msg=").append(error.message ?: "")
                            error.cause?.let { append(" cause=").append(it.javaClass.simpleName).append(":").append(it.message ?: "") }
                        }
                        Logger.e("Listen", "播放错误: $detail")
                        if (reconnectCount < 2) {
                            reconnectCount++
                            Logger.w("Listen", "第 $reconnectCount/2 次重连…")
                            notify(roomId, false, "播放出错，尝试重连…")
                            // 必须先释放旧播放器:否则 handleStart 命中"同房间在播"分支直接返回,重试无效
                            mainHandler.postDelayed({
                                stopPlayback(true)
                                handleStart(roomId)
                            }, reconnectCount * 3000L)
                        } else {
                            finishError(roomId, "播放出错（$detail）".take(64))
                        }
                    }
                    override fun onIsPlayingChanged(p: Boolean) {
                        playing = p
                        Logger.d("Listen", "isPlaying=$p room=$roomId")
                        // 缓冲/等待数据时 onIsPlayingChanged(false) 会触发，显示"缓冲中…"而非误导的"已暂停"
                        notify(roomId, p, if (p) "正在收听" else "缓冲中…")
                        updateNotification(roomId, p)
                    }
                })
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
            player = p
        } catch (e: Exception) {
            Logger.e("Listen", "播放器构建失败: ${e.message}")
            finishError(roomId, "播放器初始化失败")
        }
    }

    private fun togglePause() {
        val p = player
        if (p != null && p.playWhenReady) {
            // 暂停：真正停止拉流（释放播放器省流量），保留前台通知与房间，恢复时重新拉流直连直播边缘
            val rid = currentRoomId
            try { p.release() } catch (_: Exception) {}
            player = null
            playing = false
            paused = true
            if (rid != 0L) {
                startForeground(NOTIFICATION_ID, buildNotification(rid, false))
                updateNotification(rid, false)
                notify(rid, false, "已暂停")
            }
        } else {
            // 恢复：重新取流播放（紧跟直播，无旧缓冲延迟）
            paused = false
            val rid = currentRoomId
            if (rid != 0L) handleStart(rid)
        }
    }

    /** 直播流播完（下播 m3u8 ENDLIST）时的清理 */
    private fun onLiveEnded() {
        val rid = currentRoomId
        stopPlayback(false)
        notify(rid, false, "直播已结束")
    }

    private fun finishError(roomId: Long, msg: String) {
        Logger.e("Listen", "收听失败并停止 room=$roomId: $msg")
        stopPlayback(false)
        notify(roomId, false, msg)
    }

    /** 清理播放器；release=false 时同时撤前台通知并停止服务 */
    private fun stopPlayback(release: Boolean) {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playing = false
        paused = false
        currentRoomId = 0L
        if (!release) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(roomId: Long, p: Boolean) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(roomId, p)) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playing = false
        super.onDestroy()
    }

    private fun buildNotification(roomId: Long, p: Boolean): Notification {
        val card = RoomManager.getRoom(roomId)
        val title = card?.name ?: "在线听直播"
        val text = (card?.title ?: "房间 $roomId").take(50)
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val toggleText = if (p) "暂停" else "播放"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ddtv)
            .setContentTitle(title)
            .setContentText(if (p) "正在收听 · $text" else "已暂停 · $text")
            .setContentIntent(pending)
            .setOngoing(p)
            .setOnlyAlertOnce(true)
            .addAction(0, toggleText, PendingIntent.getService(
                this, 1, Intent(this, ListenService::class.java).apply { action = ACTION_TOGGLE }, PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "停止", PendingIntent.getService(
                this, 2, Intent(this, ListenService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE))
            .apply { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) priority = NotificationCompat.PRIORITY_LOW }
            .build()
    }
}
