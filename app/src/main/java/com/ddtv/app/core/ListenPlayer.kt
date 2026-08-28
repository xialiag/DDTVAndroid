package com.ddtv.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
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

/**
 * 应用内"在线听直播"播放器（不依赖 Service）。
 *
 * 背景:部分机型(实测 Android 13)对 startForegroundService/startService 创建**独立服务**会静默拦截
 * (调用正常但不创建,无异常无日志),而同一进程内录制服务(LiveService)却正常。
 * 因此听直播改用**进程内 ExoPlayer**(仅需 Context),完全绕开服务创建;进程保活由录制服务+WakeLock 承担,
 * App 打开/录制期间可随时收听。代价:无前台通知/后台收听(该机型服务本就起不来)。
 */
@UnstableApi
object ListenPlayer {

    @Volatile var listener: ((roomId: Long, playing: Boolean, label: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null
    private var player: ExoPlayer? = null
    @Volatile private var currentRoomId = 0L
    @Volatile private var playing = false
    private var reconnectCount = 0
    private var paused = false

    fun init(context: Context) { appCtx = context.applicationContext }

    fun activeRoom(): Long = currentRoomId

    private fun setState(roomId: Long, p: Boolean, label: String) {
        playing = p
        currentRoomId = if (roomId == 0L) currentRoomId else currentRoomId
        try { listener?.invoke(roomId, p, label) } catch (_: Exception) {}
    }

    /** 开始收听(网络取流在子线程,播放器构建在主线程) */
    fun start(roomId: Long) {
        val ctx = appCtx ?: return
        Logger.i("Listen", "[player] start room=$roomId 录制中=${LiveRecorder.isRecordingRoom(roomId)}")
        paused = false
        if (currentRoomId == roomId && player != null) { playing = true; setState(roomId, true, "正在收听"); return }
        releasePlayer()

        val qn = RoomManager.getRoom(roomId)?.quality ?: 150
        Thread({
            try {
                var info = BiliLiveApi.getStreamInfo(roomId, qn = qn, audioOnly = true)
                if (info == null) {
                    Logger.w("Listen", "纯音频流获取失败，回退常规流")
                    info = BiliLiveApi.getStreamInfo(roomId, qn = qn, audioOnly = false)
                }
                if (info == null) { finishError(roomId, "获取直播流失败"); return@Thread }
                if (info.hlsUrl.isEmpty() && info.flvUrl.isEmpty()) { finishError(roomId, "该直播暂无可用线路"); return@Thread }
                // 按 CDN host 真实错开:录制中选与录制当前线路不同 host 的 FLV;始终 FLV 优先,HLS 兜底
                val recHost = if (LiveRecorder.isRecordingRoom(roomId)) LiveRecorder.currentStreamHost(roomId) else null
                val flvLines = info.flvLines
                val hlsLines = info.hlsLines
                fun hostOf(u: String): String? = Regex("""^https?://([^/]+)""").find(u)?.groupValues?.get(1)
                fun pickLine(lines: List<String>, exclude: String?): String? =
                    lines.firstOrNull { hostOf(it) != exclude } ?: lines.firstOrNull()
                val flv = pickLine(flvLines, recHost)
                val hls = pickLine(hlsLines, recHost)
                val url: String
                val isHls: Boolean
                if (flv != null) { url = flv; isHls = false }
                else if (hls != null) { url = hls; isHls = true }
                else { finishError(roomId, "该直播暂无可用线路"); return@Thread }
                Logger.i("Listen", "[player] room=$roomId 选线=${if (isHls) "HLS" else "FLV"} url=${url.take(120)} (flv=${flvLines.size}条 hls=${hlsLines.size}条 录制节点=${recHost ?: "无"})")
                Logger.i("Listen", "[player] 构建播放器 url=${url.take(90)} isHls=$isHls")
                mainHandler.post { prepareAndPlay(roomId, url, isHls) }
            } catch (e: Exception) {
                Logger.e("Listen", "[player] 取流异常: ${e.message}")
                finishError(roomId, "获取直播流失败")
            }
        }, "ListenStream").apply { isDaemon = true; start() }
    }

    private fun prepareAndPlay(roomId: Long, url: String, isHls: Boolean) {
        val ctx = appCtx ?: return
        try {
            currentRoomId = roomId
            playing = true
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
            val ctx2 = ctx
            val p = ExoPlayer.Builder(ctx2).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true)
                trackSelectionParameters = TrackSelectionParameters.Builder(ctx2)
                    .setDisabledTrackTypes(setOf(C.TRACK_TYPE_VIDEO))
                    .build()
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> { reconnectCount = 0; Logger.i("Listen", "[player] 播放就绪(READY) room=$roomId"); notify(roomId, true, "正在收听") }
                            Player.STATE_BUFFERING -> notify(roomId, true, "缓冲中…")
                            Player.STATE_ENDED -> { notify(roomId, false, "直播已结束"); releasePlayer() }
                            else -> {}
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val detail = "code=${error.errorCode} msg=${error.message ?: ""} cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message ?: ""}"
                        Logger.e("Listen", "[player] 播放错误: $detail")
                        if (reconnectCount < 2) {
                            reconnectCount++
                            Logger.w("Listen", "[player] 第 $reconnectCount/2 次重连…")
                            notify(roomId, false, "播放出错，尝试重连…")
                            mainHandler.postDelayed({ releasePlayer(); start(roomId) }, reconnectCount * 3000L)
                        } else {
                            finishError(roomId, "播放出错（$detail）".take(64))
                        }
                    }
                    override fun onIsPlayingChanged(pp: Boolean) {
                        Logger.d("Listen", "[player] isPlaying=$pp room=$roomId")
                        notify(roomId, pp, if (pp) "正在收听" else "缓冲中…")
                    }
                })
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
            player = p
        } catch (e: Exception) {
            Logger.e("Listen", "[player] 播放器构建失败: ${e.message}")
            finishError(roomId, "播放器初始化失败")
        }
    }

    private fun notify(roomId: Long, p: Boolean, label: String) {
        playing = p
        try { listener?.invoke(roomId, p, label) } catch (_: Exception) {}
    }

    private fun finishError(roomId: Long, msg: String) {
        Logger.e("Listen", "[player] 收听失败 room=$roomId: $msg")
        releasePlayer()
        currentRoomId = 0L
        paused = true
        notify(roomId, false, msg)
    }

    fun toggle() {
        val p = player
        if (p != null && p.playWhenReady) {
            Logger.i("Listen", "[player] 点击暂停")
            try { p.release() } catch (_: Exception) {}
            player = null
            paused = true
            val rid = currentRoomId
            currentRoomId = 0L
            notify(rid, false, "已暂停")
        } else {
            paused = false
            val rid = currentRoomId
            if (rid != 0L) start(rid)
        }
    }

    fun stop() {
        Logger.i("Listen", "[player] 停止收听")
        val rid = currentRoomId
        releasePlayer()
        currentRoomId = 0L
        paused = false
        if (rid != 0L) notify(rid, false, "已停止收听")
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    /** 状态查询(前端 getListenStatus) */
    fun statusJson(): String {
        val rid = currentRoomId
        if (rid == 0L) return """{"active":false}"""
        val card = RoomManager.getRoom(rid)
        return org.json.JSONObject().apply {
            put("active", true)
            put("playing", playing)
            put("roomId", rid)
            put("name", card?.name ?: "房间 $rid")
            put("title", card?.title ?: "")
        }.toString()
    }
}
