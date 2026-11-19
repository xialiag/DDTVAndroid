package com.ddtv.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ddtv.app.MainActivity

/**
 * 听直播的通知栏（仿 B 站直播间卡片：封面大图 + 主播名/标题/状态 + 播放暂停/停止）。
 *
 * 听直播走进程内 ExoPlayer（无独立 Service，见 ListenPlayer 注释），这里用普通通知；进程保活由录制服务承担。
 * 按钮通过动态注册的 BroadcastReceiver 回调 ListenPlayer。
 */
object ListenNotify {

    private const val CHANNEL = "ddtv_listen"
    private const val NOTIFY_ID = 1002
    private const val ACTION_TOGGLE = "com.ddtv.app.listen.TOGGLE"
    private const val ACTION_STOP = "com.ddtv.app.listen.STOP"

    private var appCtx: Context? = null
    private var receiver: BroadcastReceiver? = null
    /** 上次渲染的签名，消除高频状态更新导致的重复通知构建 */
    private var lastSig: String? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        ensureChannel(ctx)
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        ACTION_TOGGLE -> ListenPlayer.toggle()
                        ACTION_STOP -> ListenPlayer.stop()
                    }
                }
            }
            try {
                ctx.registerReceiver(r, IntentFilter().apply { addAction(ACTION_TOGGLE); addAction(ACTION_STOP) })
                receiver = r
            } catch (e: Exception) {
                Logger.w("Notify", "注册听直播通知按钮失败: ${e.message}")
            }
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "在线听直播", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "听直播时的播放控制通知"
                        setShowBadge(false); setSound(null, null)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    })
            }
        }
    }

    // 当前房间的封面 Bitmap 缓存（避免每次状态刷新都 decodeFile）
    @Volatile private var coverBmp: Bitmap? = null
    @Volatile private var coverRoomId = 0L
    @Volatile private var coverUrl = ""

    /** 显示/更新通知。playing=true 显示暂停按钮 */
    fun show(roomId: Long, playing: Boolean, status: String) {
        val ctx = appCtx ?: return
        val card = RoomManager.getRoom(roomId)
        val url = card?.let { it.cover.ifEmpty { it.face } } ?: ""
        // 房间/封面变化时重置 Bitmap 缓存
        if (roomId != coverRoomId || url != coverUrl) {
            coverRoomId = roomId; coverUrl = url
            coverBmp = null
        }
        // 命中内存缓存直接用；否则查磁盘封面缓存（CoverCache 统一缓存，带并发去重/负缓存）
        var cover = coverBmp
        if (cover == null && url.isNotEmpty()) {
            CoverCache.cachedFile(url)?.let { f ->
                cover = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
                coverBmp = cover
            }
        }
        render(ctx, roomId, card, playing, status, cover)
        // 封面未就绪：交给 CoverCache 异步下载（内部已并发去重），完成后补一次渲染
        if (cover == null && url.isNotEmpty()) {
            val rId = roomId
            CoverCache.cacheAsync(url) {
                if (ListenPlayer.activeRoom() != rId) return@cacheAsync
                CoverCache.cachedFile(url)?.let { f ->
                    val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null } ?: return@cacheAsync
                    coverBmp = bmp
                    render(ctx, rId, RoomManager.getRoom(rId), ListenPlayer.isPlaying(), status, bmp)
                }
            }
        }
    }

    private fun render(ctx: Context, roomId: Long, card: RoomCard?, playing: Boolean, status: String, cover: Bitmap?) {
        // 瞬时状态（缓冲中…/重连…）不参与通知签名，避免每次 buffering 都重建通知
        val norm = when {
            status.contains("暂停") -> "已暂停"
            status.contains("出错") || status.contains("失败") -> "播放异常"
            playing -> "正在收听"
            else -> "已暂停"
        }
        val sig = "$roomId|$norm|${cover != null}"
        if (sig == lastSig) return
        val anchor = card?.name?.ifEmpty { "直播间 $roomId" } ?: "直播间 $roomId"
        val title = card?.title?.ifEmpty { "正在收听" } ?: "正在收听"
        val desc = if (playing) title else "$norm · $title"

        val openPi = PendingIntent.getActivity(ctx, 7001, Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val togglePi = PendingIntent.getBroadcast(ctx, 7002, Intent(ACTION_TOGGLE).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getBroadcast(ctx, 7003, Intent(ACTION_STOP).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val sub = card?.areaName?.takeIf { it.isNotEmpty() } ?: norm
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.ddtv.app.R.drawable.ic_stat_ddtv)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(anchor)
            .setContentText(desc)
            .setSubText(sub)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放", togglePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (cover != null) {
            b.setLargeIcon(cover)
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(cover).setSummaryText(desc))
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(desc))
        }
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, b.build())
            lastSig = sig
        } catch (e: Exception) {
            Logger.w("Notify", "听直播通知失败: ${e.message}")
        }
    }

    fun cancel() {
        val ctx = appCtx ?: return
        lastSig = null
        coverBmp = null; coverRoomId = 0L; coverUrl = ""
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID) } catch (_: Exception) {}
    }
}
