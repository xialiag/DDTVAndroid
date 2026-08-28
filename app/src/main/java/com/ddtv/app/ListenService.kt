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
import com.ddtv.app.core.LiveStreamProxy
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
    @Volatile private var usingLocal = false   // 当前是否走边录边播本地代理流
    @Volatile private var forceNetwork = false // 本地流出错后强制回退网络取流

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

        // 边录边播：该房间正在录制且本地流未出过错 → 直接用本地流代理(录制流已读到的字节)，
        // 不再单独 B 站取流，避免并发取流 403/无反应；本地流出错(forceNetwork)时回退网络取流
        if (LiveRecorder.isRecordingRoom(roomId) && !forceNetwork) {
            LiveStreamProxy.ensureServer()
            usingLocal = true
            val url = "http://127.0.0.1:${LiveStreamProxy.PORT}/live?room=$roomId&t=${System.currentTimeMillis()}"
            Logger.i("Listen", "room=$roomId 边录边播(本地代理流): $url")
            mainHandler.post { prepareAndPlay(roomId, url, false) }
            return
        }
        usingLocal = false
        forceNetwork = false

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
                val hlsUrl = info.hlsUrl
                val flvUrl = info.flvUrl
                if (hlsUrl.isEmpty() && flvUrl.isEmpty()) { finishError(roomId, "该直播暂无可用线路"); return@Thread }
                // B站直播 FLV(http-stream) 是 ExoPlayer 最稳路径（HLS fmp4 直播常见 Source error）→ 默认 FLV 优先，HLS 兜底
                // 但该房间正在录制时优先用 HLS（录制默认 FLV，两者走不同协议/线路，避免同一直播间并发取流被 B 站限流导致 403/无反应）
                val useFlv = flvUrl.isNotEmpty() && (!LiveRecorder.isRecordingRoom(roomId) || hlsUrl.isEmpty())
                val (url, isHls) = if (useFlv) flvUrl to false else hlsUrl to true
                Logger.i("Listen", "room=$roomId 播放 ${if (isHls) "HLS" else "FLV"}: ${url.take(120)}")
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
                            Player.STATE_READY -> { reconnectCount = 0; notify(roomId, true, "正在收听") }
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
                            Logger.w("Listen", "第 $reconnectCount/2 次重连…(${if (usingLocal) "回退网络" else "重试"})")
                            notify(roomId, false, "播放出错，尝试重连…")
                            // 边录边播(本地代理流)出错 → 强制回退网络取流，避免本地流反复失败
                            if (usingLocal) forceNetwork = true
                            mainHandler.postDelayed({ handleStart(roomId) }, reconnectCount * 3000L)
                        } else {
                            finishError(roomId, "播放出错（$detail）".take(64))
                        }
                    }
                    override fun onIsPlayingChanged(p: Boolean) {
                        playing = p
                        // 缓冲/等待数据时 onIsPlayingChanged(false) 会触发，显示"缓冲中…"而非误导的"已暂停"
                        notify(roomId, p, if (p) "正在收听" else "缓冲中…")
                        updateNotification(roomId, p)
                    }
                })
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
            // 边录边播(本地代理流)12s 未进入 READY(数据不足/解析异常) → 强制回退网络取流，避免无限缓冲
            if (usingLocal) {
                mainHandler.postDelayed({
                    if (p.playbackState != Player.STATE_READY) {
                        Logger.w("Listen", "本地(边录边播)流 12s 未就绪，回退网络取流")
                        forceNetwork = true
                        handleStart(currentRoomId)
                    }
                }, 12000)
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
