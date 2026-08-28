package com.ddtv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ddtv.app.core.Logger

/**
 * 开机自启：手机重启后自动恢复直播监控前台服务，避免"重启后被系统清理、不再录制/监控"。
 * START_STICKY 的 LiveService 只在进程被杀时重启；覆盖不了设备重启，需要显式拉起。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        try {
            Logger.init(context)  // 确保文件日志可用（进程冷启动）
            val i = Intent(context, LiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
            Logger.i("Boot", "开机自启: 已拉起 LiveService")
        } catch (e: Exception) {
            // Android 12+ 后台启动 FGS 可能抛 ForegroundServiceStartNotAllowedException；
            // 部分 ROM 的 boot 广播被后台限制。记录并放弃，用户手动打开 App 后 LiveService 会正常启动。
            try { Logger.e("Boot", "开机自启失败: ${e.message}") } catch (_: Exception) {}
        }
    }
}
