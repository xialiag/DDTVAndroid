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

    /** 显示/更新通知。playing=true 显示暂停按钮 */
    fun show(roomId: Long, playing: Boolean, status: String) {
        val ctx = appCtx ?: return
        val card = RoomManager.getRoom(roomId)
        // 封面同步取自磁盘缓存（避免"先无图再刷新"的闪烁）；缓存缺失时异步下载后补一次
        val coverFile = java.io.File(ctx.cacheDir, "listen_cover_$roomId.jpg")
        val cover: Bitmap? = if (coverFile.exists() && coverFile.length() > 0)
            try { BitmapFactory.decodeFile(coverFile.absolutePath) } catch (_: Exception) { null } else null
        render(ctx, roomId, card, playing, status, cover)
        if (cover == null && card != null) fetchCoverAsync(ctx, roomId, card, playing, status)
    }

    private fun render(ctx: Context, roomId: Long, card: RoomCard?, playing: Boolean, status: String, cover: Bitmap?) {
        val sig = "$roomId|$playing|$status|${cover != null}"
        if (sig == lastSig) return
        val anchor = card?.name?.ifEmpty { "直播间 $roomId" } ?: "直播间 $roomId"
        val title = card?.title?.ifEmpty { "正在收听" } ?: "正在收听"
        val desc = if (playing) title else "$status · $title"

        val openPi = PendingIntent.getActivity(ctx, 7001, Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val togglePi = PendingIntent.getBroadcast(ctx, 7002, Intent(ACTION_TOGGLE).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getBroadcast(ctx, 7003, Intent(ACTION_STOP).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.ddtv.app.R.drawable.ic_stat_ddtv)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(anchor)
            .setContentText(desc)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放", togglePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (cover != null) {
            b.setLargeIcon(cover)
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(cover).setSummaryText(desc))
            card?.areaName?.takeIf { it.isNotEmpty() }?.let { b.setSubText(it) }
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(desc))
            card?.areaName?.takeIf { it.isNotEmpty() }?.let { b.setSubText(it) } ?: b.setSubText(status)
        }
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, b.build())
            lastSig = sig
        } catch (e: Exception) {
            Logger.w("Notify", "听直播通知失败: ${e.message}")
        }
    }

    private fun fetchCoverAsync(ctx: Context, roomId: Long, card: RoomCard, playing: Boolean, status: String) {
        val url = card.cover.ifEmpty { card.face }
        if (url.isEmpty()) return
        val f = java.io.File(ctx.cacheDir, "listen_cover_$roomId.jpg")
        Thread({
            try {
                val bytes = Http.getBytes(url, referer = "https://live.bilibili.com/")
                if (bytes.isEmpty()) return@Thread
                f.writeBytes(bytes)
                if (ListenPlayer.activeRoom() != roomId) return@Thread  // 已切房/停止，丢弃
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@Thread
                render(ctx, roomId, RoomManager.getRoom(roomId), ListenPlayer.isPlaying(), status, bmp)
            } catch (_: Exception) {}
        }, "ListenCover").apply { isDaemon = true; start() }
    }

    fun cancel() {
        val ctx = appCtx ?: return
        lastSig = null
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID) } catch (_: Exception) {}
    }
}
