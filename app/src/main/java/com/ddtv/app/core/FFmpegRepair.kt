package com.ddtv.app.core

import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

/**
 * FFmpeg 修复工具（对应原版 ToolsPage 手动修复 + Tools/Transcode.cs + Fmp4Repair.cs）
 * - remux: 快速转封装（flv→mp4，-c copy 不重编码）
 * - repair: 修复损坏文件（-err_detect ignore_err 重封装，处理录制中断导致的损坏）
 * - transcode: 完整转码（H.264 重编码，修复时间轴/编码损坏，较慢）
 */
object FFmpegRepair {

    @Volatile var listener: ((roomId: Long, file: String, ok: Boolean, msg: String) -> Unit)? = null

    /** 正在处理的输出文件（防同一文件被并发修复互相覆盖） */
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 最近一次失败的错误摘要（供 UI 展示详细失败原因） */
    @Volatile var lastError: String = ""

    /** 任务系统的活动 FFmpeg 会话（taskId → session，供取消） */
    private val activeSessions = java.util.concurrent.ConcurrentHashMap<Long, com.arthenica.ffmpegkit.FFmpegSession>()

    /** 构造修复命令（任务系统与同步版共用）
     * 注意：ffmpeg-kit-full（非 GPL 构建）不含 libx264，转码只能用内置编码器 mpeg4（Android 播放器兼容）。
     */
    fun buildCommand(input: String, mode: String, output: String): String = when (mode) {
        "repair" -> "-y -err_detect ignore_err -i \"$input\" -c copy -movflags +faststart \"$output\""
        "transcode" -> "-y -err_detect ignore_err -i \"$input\" -c:v mpeg4 -q:v 3 -c:a aac -movflags +faststart \"$output\""
        else -> "-y -err_detect ignore_err -i \"$input\" -c copy -movflags +faststart \"$output\""
    }

    /** 从 ffmpeg 全量输出里提取真正的报错（跳过版本/配置横幅），取最后的错误行，失败时兜底取末尾 */
    fun errorOf(log: String): String {
        val lines = log.lines().filter { it.isNotBlank() }
        val errLines = lines.filter { it.contains("rror") || it.contains("Invalid") || it.contains("failed") || it.contains("not found") || it.contains("Unknown") }
        val picked = if (errLines.isNotEmpty()) errLines else lines.takeLast(6)
        return picked.joinToString("\n").takeLast(500)
    }

    /** 输出路径：input 去扩展名 + 模式后缀 */
    fun outputFor(input: String, mode: String): String {
        val base = input.removeSuffix(".flv").removeSuffix(".mp4")
        return when (mode) {
            "repair" -> base + "_repaired.mp4"
            "transcode" -> base + "_transcoded.mp4"
            else -> base + ".mp4"
        }
    }

    /**
     * 异步可取消修复（任务系统用）：结果经 onDone(output, error) 回调（任意线程）
     */
    fun repairAsync(input: String, mode: String, taskId: Long, onDone: (String?, String) -> Unit) {
        val output = outputFor(input, mode)
        val target = File(output)
        if (!File(input).exists()) { onDone(null, "文件不存在"); return }
        // 目标已存在则跳过（幂等）
        if (target.exists() && target.length() > 0) { onDone(output, ""); return }
        Logger.i("Repair", "开始[$mode] (任务#$taskId): $input")
        val cmd = buildCommand(input, mode, output)
        val session = FFmpegKit.executeAsync(cmd, { s ->
            activeSessions.remove(taskId)
            val rc = s?.returnCode?.value ?: -1
            if (rc == 0 && target.exists() && target.length() > 0) {
                Logger.i("Repair", "[$mode] 完成 (任务#$taskId): $output")
                onDone(output, "")
            } else {
                val log = s?.allLogsAsString ?: ""
                val err = errorOf(log).ifBlank { "FFmpeg 返回 $rc" }
                Logger.w("Repair", "[$mode] 失败 rc=$rc (任务#$taskId): $err")
                try { target.delete() } catch (_: Exception) {}
                onDone(null, err)
            }
        })
        activeSessions[taskId] = session
    }

    /** 取消指定任务（进行中） */
    fun cancelAsync(taskId: Long) {
        activeSessions.remove(taskId)?.cancel()
    }

    /**
     * 修复/转码文件，成功返回输出路径，失败返回 null
     * @param mode remux / repair / transcode
     */
    fun repair(input: String, mode: String = "remux"): String? {
        val f = File(input)
        if (!f.exists()) return null
        val base = input.removeSuffix(".flv").removeSuffix(".mp4")
        val output = when (mode) {
            "repair" -> base + "_repaired.mp4"
            "transcode" -> base + "_transcoded.mp4"
            else -> base + ".mp4"
        }
        // 目标已存在则跳过（幂等）
        val target = File(output)
        if (target.exists() && target.length() > 0) return output
        if (!active.add(output)) {
            Logger.i("Repair", "[$mode] 该文件正在处理中，跳过: $output")
            return null
        }
        try {
            Logger.i("Repair", "开始[$mode]: $input")
            val cmd = buildCommand(input, mode, output)
            val session = FFmpegKit.execute(cmd)
            val returnCode = session?.returnCode?.value ?: -1
            if (returnCode == 0 && target.exists() && target.length() > 0) {
                Logger.i("Repair", "[$mode] 完成: $output")
                listener?.invoke(0, output, true, "完成")
                return output
            } else {
                val log = session?.allLogsAsString ?: ""
                val err = errorOf(log).ifBlank { "FFmpeg 返回 $returnCode" }
                Logger.w("Repair", "[$mode] 失败 rc=$returnCode: $err")
                lastError = err
                try { target.delete() } catch (_: Exception) {}
                listener?.invoke(0, input, false, err.take(120))
                return null
            }
        } catch (e: Exception) {
            Logger.w("Repair", "[$mode] 异常: ${e.message}")
            lastError = e.message ?: "未知异常"
            return null
        } finally {
            active.remove(output)
        }
    }
}
