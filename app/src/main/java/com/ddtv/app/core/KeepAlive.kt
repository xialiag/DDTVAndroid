package com.ddtv.app.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * 保活兜底（JobScheduler）：周期性检查前台服务是否存活，不在且用户未主动退出时拉起。
 *
 * 为什么需要：START_STICKY 只在系统常规回收后重建；国产 ROM 的"清理后台/一键加速"直接杀进程，
 * 不触发重建。JobScheduler 由系统调度，独立于应用进程，被杀后仍会按时唤醒，从而自动复活。
 *
 * 用户主动退出后（[KeepAlive.markExited]）不再拉起，避免"关不掉、一直占后台"。
 */
object KeepAlive {
    private const val JOB_ID = 0x0D7 // 215
    private const val PREFS = "ddtv_settings"
    private const val KEY_EXITED = "app_exited"

    /** 用户是否主动退出过（退出后不复活，打开 App 时清除） */
    fun isExited(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EXITED, false)

    fun markExited(ctx: Context, exited: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EXITED, exited).apply()
    }

    /** 注册周期保活任务（幂等） */
    fun schedule(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, KeepAliveJob::class.java))
                .setPeriodic(15 * 60 * 1000L)      // 系统最小周期 15 分钟
                .setPersisted(true)                 // 重启后仍生效（需 RECEIVE_BOOT_COMPLETED）
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            js.schedule(info)
        } catch (e: Exception) {
            Logger.w("KeepAlive", "注册保活任务失败: ${e.message}")
        }
    }

    fun cancel(ctx: Context) {
        try {
            (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
        } catch (_: Exception) {}
    }
}

/**
 * 保活任务本体：服务不在且用户未退出 → 拉起前台服务。
 * 注意：Android 12+ 后台启动 FGS 可能被拒（ForegroundServiceStartNotAllowedException），
 * 此时退回 startService，并记录日志。
 */
class KeepAliveJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (KeepAlive.isExited(this)) {                     // 用户已退出：不复活，并自我取消避免空转
            KeepAlive.cancel(this)
            return false
        }
        if (!com.ddtv.app.LiveService.isRunning()) {
            try {
                val i = android.content.Intent(this, com.ddtv.app.LiveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
                else startService(i)
                Logger.i("KeepAlive", "保活任务：服务未运行，已拉起")
            } catch (e: Exception) {
                Logger.w("KeepAlive", "保活任务拉起服务失败: ${e.message}")
                try { startService(android.content.Intent(this, com.ddtv.app.LiveService::class.java)) } catch (_: Exception) {}
            }
        }
        return false  // 工作已完成
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
