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
 * 背景：部分机型(实测 Android 13)对 startForegroundService/startService 创建独立服务会**静默拦截**
 * (调用正常但不创建,无异常无日志)，而同一进程内录制服务(LiveService)却正常。
 * 因此听直播用**进程内 ExoPlayer**(仅需 Context)，完全绕开服务创建；进程保活由录制服务+WakeLock 承担。
 *
 * 并发安全：所有操作以 seq(操作序号)为准——停止/暂停/失败会递增 seq，
 * 在途的取流线程与播放器构建检测到 seq 过期即放弃，保证任一时刻只有一个活跃播放器。
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
    /** 操作序号：停止/暂停/失败/重建均递增，过期的取流与构建直接放弃 */
    @Volatile private var seq = 0L

    fun init(context: Context) { appCtx = context.applicationContext }

    fun activeRoom(): Long = currentRoomId

    /** 开始收听；统一主线程调度(@JavascriptInterface 回调在 WebView 线程,ExoPlayer 操作必须在主线程) */
    fun start(roomId: Long) { mainHandler.post { doStart(roomId) } }

    private fun doStart(roomId: Long) {
        if (appCtx == null) return
        val ctx = appCtx ?: return
        Logger.i("Listen", "[player] start room=$roomId 录制中=${LiveRecorder.isRecordingRoom(roomId)}")
        paused = false
        if (currentRoomId == roomId && player != null) {
            playing = true
            notify(roomId, true, "正在收听")
            return
        }
        val s = ++seq
        releasePlayer()
        val qn = RoomManager.getRoom(roomId)?.quality ?: 150
        Thread({
            try {
                var info = BiliLiveApi.getStreamInfo(roomId, qn = qn, audioOnly = true)
                if (info == null) {
                    Logger.w("Listen", "纯音频流获取失败，回退常规流")
                    info = BiliLiveApi.getStreamInfo(roomId, qn = qn, audioOnly = false)
                }
                if (s != seq) return@Thread  // 已停止/切换,放弃
                if (info == null) { finishError(roomId, "获取直播流失败"); return@Thread }
                if (info.hlsUrl.isEmpty() && info.flvUrl.isEmpty()) { finishError(roomId, "该直播暂无可用线路"); return@Thread }
                // 按 CDN host 真实错开:录制中选与录制当前线路不同 host 的 FLV;FLV 优先,HLS 兜底
                val recHost = if (LiveRecorder.isRecordingRoom(roomId)) LiveRecorder.currentStreamHost(roomId) else null
                fun hostOf(u: String): String? = Regex("""^https?://([^/]+)""").find(u)?.groupValues?.get(1)
                fun pickLine(lines: List<String>, exclude: String?): String? =
                    lines.firstOrNull { hostOf(it) != exclude } ?: lines.firstOrNull()
                val flv = pickLine(info.flvLines, recHost)
                val hls = pickLine(info.hlsLines, recHost)
                val url: String
                val isHls: Boolean
                if (flv != null) { url = flv; isHls = false }
                else if (hls != null) { url = hls; isHls = true }
                else { finishError(roomId, "该直播暂无可用线路"); return@Thread }
                Logger.i("Listen", "[player] room=$roomId 选线=${if (isHls) "HLS" else "FLV"} url=${url.take(120)} (flv=${info.flvLines.size}条 hls=${info.hlsLines.size}条 录制节点=${recHost ?: "无"})")
                Logger.i("Listen", "[player] 构建播放器 url=${url.take(90)} isHls=$isHls")
                mainHandler.post {
                    if (s != seq) return@post
                    prepareAndPlay(roomId, s, url, isHls)
                }
            } catch (e: Exception) {
                Logger.e("Listen", "[player] 取流异常: ${e.message}")
                if (s == seq) finishError(roomId, "获取直播流失败")
            }
        }, "ListenStream").apply { isDaemon = true; start() }
    }

    private fun prepareAndPlay(roomId: Long, s: Long, url: String, isHls: Boolean) {
        val ctx = appCtx ?: return
        if (s != seq) return  // 已停止/切换,放弃
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
            releasePlayer()
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
                            Player.STATE_ENDED -> { if (s == seq) { notify(roomId, false, "直播已结束"); releaseAndClear() } }
                            else -> {}
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val detail = "code=${error.errorCode} msg=${error.message ?: ""} cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message ?: ""}"
                        Logger.e("Listen", "[player] 播放错误: $detail")
                        if (s != seq) return
                        if (reconnectCount < 2) {
                            reconnectCount++
                            Logger.w("Listen", "[player] 第 $reconnectCount/2 次重连…")
                            notify(roomId, false, "播放出错，尝试重连…")
                            mainHandler.postDelayed({
                                if (s != seq) return@postDelayed
                                releasePlayer()
                                start(roomId)
                            }, reconnectCount * 3000L)
                        } else {
                            finishError(roomId, "播放出错（$detail）".take(64))
                        }
                    }
                    override fun onIsPlayingChanged(pp: Boolean) {
                        Logger.d("Listen", "[player] isPlaying=$pp room=$roomId")
                        if (s == seq) notify(roomId, pp, if (pp) "正在收听" else "缓冲中…")
                    }
                })
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
            player = p
        } catch (e: Exception) {
            Logger.e("Listen", "[player] 播放器构建失败: ${e.message}")
            if (s == seq) finishError(roomId, "播放器初始化失败")
        }
    }

    private fun notify(roomId: Long, p: Boolean, label: String) {
        playing = p
        try { listener?.invoke(roomId, p, label) } catch (_: Exception) {}
    }

    /** 失败：递增序号使在途取流/重试全部失效，释放并复位(统一主线程释放 player) */
    private fun finishError(roomId: Long, msg: String) {
        mainHandler.post {
            Logger.e("Listen", "[player] 收听失败 room=$roomId: $msg")
            releaseAndClear()
            paused = true
            notify(roomId, false, msg)
        }
    }

    private fun releaseAndClear() {
        seq++  // 使所有在途操作失效
        releasePlayer()
        currentRoomId = 0L
    }

    /** 播放/暂停切换(前端控制器点击)；统一主线程调度 */
    fun toggle() { mainHandler.post { doToggle() } }

    private fun doToggle() {
        val rid = currentRoomId
        if (rid == 0L) { Logger.i("Listen", "[player] toggle 未在收听"); return }
        val p = player
        if (p != null && (p.playWhenReady || !paused)) {
            // 播放中/缓冲中 → 暂停(保留房间号)
            Logger.i("Listen", "[player] 点击暂停(room=$rid)")
            seq++
            releasePlayer()
            paused = true
            notify(rid, false, "已暂停")
            return
        }
        // 暂停中 → 恢复
        Logger.i("Listen", "[player] 点击恢复(room=$rid)")
        paused = false
        start(rid)
    }

    /** 停止收听(彻底停止)；统一主线程调度 */
    fun stop() { mainHandler.post { doStop() } }

    private fun doStop() {
        Logger.i("Listen", "[player] 停止收听")
        val rid = currentRoomId
        releaseAndClear()
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
