package com.ddtv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ddtv.app.core.AccountManager
import com.ddtv.app.core.DanmakuItem
import com.ddtv.app.core.LiveRecorder
import com.ddtv.app.core.Logger
import com.ddtv.app.core.RoomManager

/**
 * 前台服务：保证录制/监控在后台持续运行（mediaPlayback 类型）
 * 保活机制（参考 BBDown DownloadService）：
 *  - 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠（录制时息屏不断流）
 *  - 后台线程每 2s 刷新通知 + WakeLock 超时后重新获取
 *  - START_STICKY 被杀后重启；onTaskRemoved 从最近任务移除时重启
 * 开播/下播提醒 + 登录失效检测
 */
class LiveService : Service() {

    companion object {
        const val CHANNEL_ID = "ddtv_live"
        const val CHANNEL_REMIND = "ddtv_remind"
        const val NOTIFICATION_ID = 1001
        const val REMIND_BASE_ID = 2000
        private var running = false
        fun isRunning(): Boolean = running
    }

    private var checkThread: Thread? = null
    private var loginCheckThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        RoomManager.addListener(serviceRoomListener)
        RoomManager.startPolling()
        // 立即显示通知，避免 Android 12+ 未及时 startForeground 导致 ANR
        startForeground(NOTIFICATION_ID, buildNotification())

        // WakeLock:无条件持有,保证息屏后 CPU 持续运行——轮询(开播检测)、录制、弹幕都依赖它
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DDTV:LiveWakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(60 * 60 * 1000L) // 1小时超时,由下方线程续期
            Logger.i("Service", "WakeLock 已获取")
        } catch (e: Exception) {
            Logger.w("Service", "获取 WakeLock 失败: ${e.message}")
        }

        // 通知刷新 + WakeLock 续期线程
        checkThread = Thread({
            while (running) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                if (!running) break
                try {
                    // WakeLock 超时被释放则重新获取
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
                } catch (_: Exception) {}
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification())
                } catch (_: Exception) {}
            }
        }, "ServiceNotify").also { it.isDaemon = true; it.start() }

        // 周期性登录态校验（每 30 分钟，对应原版登录失效提醒）
        startLoginCheckLoop()
        Logger.i("Service", "LiveService 启动，轮询间隔 ${RoomManager.settings.pollInterval}s")
    }

    private fun startLoginCheckLoop() {
        loginCheckThread = Thread({
            while (running) {
                try { Thread.sleep(30 * 60 * 1000L) } catch (_: InterruptedException) { break }
                if (!running) break
                checkLoginStatus()
            }
        }, "LoginCheck").also { it.isDaemon = true; it.start() }
    }

    private val serviceRoomListener = object : RoomManager.Listener {
        override fun onRoomsChanged() {}
        override fun onRoomUpdate(roomId: Long) { updateNotification() }
        override fun onLiveStart(roomId: Long) {
            updateNotification()
            notifyLiveChange(roomId, true)
        }
        override fun onLiveEnd(roomId: Long) {
            updateNotification()
            notifyLiveChange(roomId, false)
        }
        override fun onDanmakuEvent(item: DanmakuItem) {}
        override fun onDanmakuStatus(roomId: Long, connected: Boolean, msg: String) {}
        override fun onLog(roomId: Long, level: String, msg: String) {}
        override fun onHeartbeatLog(msg: String) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY // 被杀后尝试重启
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务移除时重启服务（录制/监控需要持续运行）
        // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，try/catch 兜底
        Logger.i("Service", "应用被移除，重启服务保活")
        val restartIntent = Intent(applicationContext, LiveService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
        } catch (e: Exception) {
            Logger.e("Service", "重启前台服务失败: ${e.message}")
            try {
                applicationContext.startService(restartIntent)
                Logger.i("Service", "回退使用 startService 重启服务")
            } catch (e2: Exception) {
                Logger.e("Service", "回退 startService 失败: ${e2.message}")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        checkThread?.interrupt()
        checkThread = null
        loginCheckThread?.interrupt()
        loginCheckThread = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        RoomManager.removeListener(serviceRoomListener)
        RoomManager.stopPolling()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "DDTV 直播录制", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "直播录制与开播检测"; setShowBadge(false) }
            nm.createNotificationChannel(channel)
            // 提醒频道：高优先级带声音（对应原版开播气泡提醒）
            val remind = NotificationChannel(
                CHANNEL_REMIND, "开播/下播提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "关注主播的开播与下播通知" }
            nm.createNotificationChannel(remind)
        }
    }

    /** 开播/下播提醒通知（开播时异步抓直播间封面，用大图样式展示，对应原版直播快照提醒） */
    private fun notifyLiveChange(roomId: Long, isLive: Boolean) {
        try {
            if (!RoomManager.settings.remindLive) return
            val card = RoomManager.getRoom(roomId) ?: return
            if (!card.remind) return
            val nm = getSystemService(NotificationManager::class.java)
            val intent = Intent(this, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                this, roomId.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (isLive) "${card.name} 开播了" else "${card.name} 下播了"
            val text = if (isLive) card.title.ifEmpty { "点击查看直播" } else "本次直播已结束"

            fun build(coverPath: String? = null): android.app.Notification {
                val b = NotificationCompat.Builder(this, CHANNEL_REMIND)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_stat_ddtv)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                if (coverPath != null) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeFile(coverPath)
                        if (bmp != null) {
                            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp)
                                .setBigContentTitle(title).setSummaryText(text))
                        }
                    } catch (_: Exception) {}
                }
                return b.build()
            }

            if (isLive) {
                // 开播快照：异步下载直播间封面后带图通知（封面拉取失败则退化为普通通知）
                val coverUrl = card.cover.ifEmpty { card.face }
                if (coverUrl.isNotEmpty()) {
                    val coverFile = java.io.File(cacheDir, "snapshot_$roomId.jpg")
                    Thread({
                        try {
                            val bytes = com.ddtv.app.core.Http.getBytes(coverUrl, referer = "https://live.bilibili.com/")
                            if (bytes.isNotEmpty()) coverFile.writeBytes(bytes)
                        } catch (_: Exception) {}
                        try { nm.notify(roomId.toInt(), build(if (coverFile.exists()) coverFile.absolutePath else null)) } catch (_: Exception) {}
                    }, "Snapshot-$roomId").apply { isDaemon = true; start() }
                } else {
                    nm.notify(roomId.toInt(), build())
                }
            } else {
                nm.notify(roomId.toInt(), build())
            }
            Logger.i("Service", "提醒: ${card.name} ${if (isLive) "开播" else "下播"}")
        } catch (e: Exception) {
            Logger.w("Service", "提醒通知失败: ${e.message}")
        }
    }

    /**
     * 登录失效检测（对应原版 LoginFailureEvent + SMTP 登录失效提醒）：
     * 由 startLoginCheckLoop 每 30 分钟调用
     */
    private fun checkLoginStatus() {
        try {
            // 未登录且本地无保存的登录 cookie：无需校验（不打扰未登录用户）
            if (!AccountManager.isLoggedIn() && !AccountManager.hasSavedCookie()) return
            // 内存无账号但本地有 cookie（如启动时网络异常）：checkLoginValid 内会尝试恢复
            if (!AccountManager.checkLoginValid()) {
                Logger.w("Service", "登录态校验失败，通知重新登录")
                val nm = getSystemService(NotificationManager::class.java)
                val intent = Intent(this, MainActivity::class.java)
                val pi = PendingIntent.getActivity(
                    this, 9001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(this, CHANNEL_REMIND)
                    .setContentTitle("⚠ DDTV 登录态已失效")
                    .setContentText("账号登录可能已过期，请重新登录以继续发送弹幕/小心心挂机")
                    .setSmallIcon(R.drawable.ic_stat_ddtv)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                nm.notify(REMIND_BASE_ID + 999, n)
            }
        } catch (e: Exception) {
            Logger.w("Service", "登录态检测异常: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rooms = RoomManager.getRooms()
        val recording = rooms.count { it.recState == "recording" }
        val watching = rooms.size
        val title = if (recording > 0) "正在录制 $recording 个直播间" else "监控 $watching 个直播间"
        val liveCount = rooms.count { it.liveStatus == 1 || it.liveStatus == 2 }
        val text = if (recording > 0) {
            val recNames = rooms.filter { it.recState == "recording" }.take(3)
                .joinToString("、") { it.name }.let { if (it.length > 30) it.take(30) + "…" else it }
            "录制中: $recNames"
        } else "DDTV 后台运行中 · ${liveCount} 个直播间在线"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_ddtv)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Logger.w("Service", "更新通知失败: ${e.message}")
        }
    }
}
