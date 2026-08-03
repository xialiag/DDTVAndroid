package com.ddtv.app.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量日志：内存环形缓冲 + 监听器推送到 UI（移植 DDTV Core/LogModule/log.cs 思路）
 * seq 为全局自增序号，调试服务器用它做增量拉取/长轮询（实时检测）。
 *
 * 日志保存（BBDownAndroid 同款能力）：
 *  - 每条日志追加写入应用私有目录 logs/ddtv_YYYYMMDD.log（按天轮转，保留最近 7 天），
 *    重启不丢，DebugServer/设置页可导出、分享、查看。
 *  - 崩溃日志（CrashHandler）写在同目录 crash_*.txt。
 */
object Logger {

    data class LogLine(val roomId: Long, val level: String, val msg: String, val time: Long = System.currentTimeMillis(), val seq: Long = 0)

    interface Listener {
        fun onLog(line: LogLine)
    }

    @Volatile var listener: Listener? = null

    private val buffer = ConcurrentLinkedQueue<LogLine>()
    private val seqGen = AtomicLong(0)

    private const val MAX_BUFFER = 500
    private const val KEEP_DAYS = 7

    @Volatile private var appContext: Context? = null
    private val fileLock = Any()
    private var currentDay = ""

    /** 初始化文件日志上下文（MainActivity.onCreate 调用；未调用时仅内存日志） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 日志文件目录（外部私有目录，无需存储权限） */
    fun logDir(): File? = appContext?.getExternalFilesDir(null)?.let { File(it, "logs") }

    private fun push(roomId: Long, level: String, msg: String) {
        val line = LogLine(roomId, level, msg, System.currentTimeMillis(), seqGen.incrementAndGet())
        buffer.add(line)
        while (buffer.size > MAX_BUFFER) buffer.poll()
        listener?.onLog(line)
        if (level == "error" || level == "warn") {
            android.util.Log.w("DDTV", "[$roomId][$level] $msg")
        } else {
            android.util.Log.i("DDTV", "[$roomId] $msg")
        }
        appendToFile(line)
    }

    /** 追加写入当日日志文件（失败静默，不影响主流程） */
    private fun appendToFile(line: LogLine) {
        val ctx = appContext ?: return
        try {
            val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(line.time))
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            synchronized(fileLock) {
                if (day != currentDay) {
                    currentDay = day
                    cleanupOldFiles(dir)
                }
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date(line.time))
                File(dir, "ddtv_$day.log")
                    .appendText("[$time][${line.level}][${line.roomId}] ${line.msg}\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }

    /** 清理超过保留天数的旧日志文件 */
    private fun cleanupOldFiles(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000L
            dir.listFiles { f -> f.name.startsWith("ddtv_") && f.name.endsWith(".log") }
                ?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        } catch (_: Exception) {}
    }

    fun d(roomId: Long, msg: String) = push(roomId, "debug", msg)
    fun d(tag: String, msg: String) = push(0, "debug", "[$tag] $msg")
    fun i(roomId: Long, msg: String) = push(roomId, "info", msg)
    fun i(tag: String, msg: String) = push(0, "info", "[$tag] $msg")
    fun w(roomId: Long, msg: String) = push(roomId, "warn", msg)
    fun w(tag: String, msg: String) = push(0, "warn", "[$tag] $msg")
    fun e(roomId: Long, msg: String) = push(roomId, "error", msg)
    fun e(tag: String, msg: String) = push(0, "error", "[$tag] $msg")

    /** 错误 + 完整堆栈（异常链一并写入，崩溃排查用） */
    fun e(tag: String, msg: String, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println(msg)
        pw.println("  异常类型: ${throwable.javaClass.name}")
        pw.println("  异常消息: ${throwable.message}")
        pw.println("  堆栈跟踪:")
        throwable.printStackTrace(pw)
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 10) {
            depth++
            pw.println("  Caused by ($depth): ${cause.javaClass.name}: ${cause.message}")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        push(0, "error", "[$tag] ${sw.toString().trim()}")
    }

    fun recent(limit: Int = 200): List<LogLine> = buffer.toList().takeLast(limit)

    /** 当前最大序号（增量拉取水位） */
    fun maxSeq(): Long = seqGen.get()

    /** 取 seq 之后的日志（增量，调试服务器长轮询用） */
    fun since(seq: Long): List<LogLine> = buffer.toList().filter { it.seq > seq }

    /** 清空内存日志（文件日志保留，供导出/回溯） */
    fun clear() {
        buffer.clear()
    }

    /** 当前日志条数 */
    fun getCount(): Int = buffer.size

    /** 全部内存日志（导出用，带房间号） */
    fun getAll(): String {
        return buffer.toList().joinToString("\n") { l ->
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date(l.time))
            "[$time][${l.level}][${l.roomId}] ${l.msg}"
        }
    }

    /** 当日日志文件（DebugServer 下载/查看用）；无则 null */
    fun currentLogFile(): File? {
        val dir = logDir() ?: return null
        val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        val f = File(dir, "ddtv_$day.log")
        return if (f.exists()) f else null
    }

    /** 导出内存日志到文件（带文件头，BBDown 同款） */
    fun exportToFile(targetFile: File) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("====== DDTV 调试日志 ======")
        pw.println("导出时间: ${Date()}")
        pw.println("日志条数: ${getCount()}")
        pw.println("==========================================")
        pw.println()
        pw.println(getAll())
        pw.flush()
        targetFile.writeText(sw.toString(), Charsets.UTF_8)
    }
}
