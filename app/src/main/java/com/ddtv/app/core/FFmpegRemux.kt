package com.ddtv.app.core

import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.text.SimpleDateFormat

/**
 * FFmpeg 转封装：flv→mp4（-c copy 不重编码，对应 DDTV Tools/Transcode.cs 修复逻辑）
 * 通过 FFmpegKit AAR 实现，异步执行
 */
object FFmpegRemux {

    @Volatile var listener: ((roomId: Long, file: String, ok: Boolean, msg: String) -> Unit)? = null

    /**
     * 转封装文件（flv → mp4），成功返回 mp4 路径，失败返回 null
     */
    fun remux(input: String, roomId: Long = 0): String? {
        val f = File(input)
        if (!f.exists()) return null
        if (!input.endsWith(".flv")) return null
        val output = input.removeSuffix(".flv") + ".mp4"
        if (File(output).exists()) return output
        Logger.i("Remux", "开始转封装: $input")
        try {
            val cmd = "-y -i \"$input\" -c copy -movflags +faststart \"$output\""
            val session = FFmpegKit.execute(cmd)
            val returnCode = session?.returnCode?.value ?: -1
            if (returnCode == 0 && File(output).exists() && File(output).length() > 0) {
                Logger.i("Remux", "转封装完成: $output")
                // 成功后删除 flv 原文件
                try { f.delete() } catch (_: Exception) {}
                return output
            } else {
                val log = session?.allLogsAsString ?: ""
                val err = com.ddtv.app.core.FFmpegRepair.errorOf(log).ifBlank { "FFmpeg 返回 $returnCode" }
                Logger.w("Remux", "转封装失败 rc=$returnCode: $err")
                try { File(output).delete() } catch (_: Exception) {}
                return null
            }
        } catch (e: Exception) {
            Logger.w("Remux", "转封装异常: ${e.message}")
            return null
        }
    }

    /**
     * 仅提取音频轨（仅录音频模式用）：输出 m4a，嵌入元数据（标题/主播/日期）与封面（同目录 *_cover.jpg）。
     * 中断/损坏文件自动降级修复：完整提取 → 容错 copy(ignore_err) → AAC 重编码，任一级成功即输出。
     * 成功返回 m4a 路径并删除原文件，失败返回 null（保留原文件）。
     */
    fun extractAudio(input: String, title: String = "", artist: String = "", date: String = ""): String? {
        val f = File(input)
        if (!f.exists()) return null
        val output = input.substringBeforeLast('.') + "_audio.m4a"
        if (File(output).exists()) return output
        Logger.i("Remux", "提取音频轨: $input")
        try {
            val cover = File(input.substringBeforeLast('.') + "_cover.jpg")
            val day = if (date.isNotBlank()) date
                else SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(f.lastModified()))
            val cmds = listOf(
                // 1. 完整提取：封面 + 元数据 + 音频 copy
                audioCmd(input, output, cover, title, artist, day, tolerant = false, reencode = false),
                // 2. 容错 copy：忽略错误帧（截断/半个分片等），去掉封面避免容器问题
                audioCmd(input, output, null, title, artist, day, tolerant = true, reencode = false),
                // 3. AAC 重编码：损坏严重时重编码修复时间轴/坏帧
                audioCmd(input, output, null, title, artist, day, tolerant = true, reencode = true),
            )
            var lastErr = ""
            for ((i, cmd) in cmds.withIndex()) {
                val session = FFmpegKit.execute(cmd)
                val rc = session?.returnCode?.value ?: -1
                if (rc == 0 && File(output).exists() && File(output).length() > 0) {
                    if (i > 0) Logger.i("Remux", "音频提取降级成功(第${i + 1}级): $output")
                    else Logger.i("Remux", "音频提取完成: $output")
                    try { f.delete() } catch (_: Exception) {}
                    return output
                }
                try { File(output).delete() } catch (_: Exception) {}
                lastErr = com.ddtv.app.core.FFmpegRepair.errorOf(session?.allLogsAsString ?: "")
            }
            Logger.w("Remux", "音频提取失败(三级均失败): ${lastErr.ifBlank { "FFmpeg 返回错误" }}")
            return null
        } catch (e: Exception) {
            Logger.w("Remux", "音频提取异常: ${e.message}")
            return null
        }
    }

    /** 构造音频提取命令（封面/容错/重编码可组合） */
    private fun audioCmd(input: String, output: String, cover: File?, title: String, artist: String, day: String, tolerant: Boolean, reencode: Boolean): String {
        val sb = StringBuilder()
        sb.append("-y -i \"").append(input).append('"')
        if (cover != null && cover.exists()) sb.append(" -i \"").append(cover.absolutePath).append('"')
        if (cover != null && cover.exists()) sb.append(" -map 0:a -map 1:v -c:a copy -c:v mjpeg -disposition:v attached_pic")
        else if (reencode) sb.append(" -map 0:a -c:a aac -b:a 192k")
        else sb.append(" -vn -c:a copy")
        if (tolerant) sb.append(" -err_detect ignore_err")
        if (title.isNotBlank()) sb.append(" -metadata title=\"").append(escMeta(title)).append('"')
        if (artist.isNotBlank()) sb.append(" -metadata artist=\"").append(escMeta(artist)).append('"')
        sb.append(" -metadata date=\"").append(day).append('"')
        sb.append(" -metadata album=\"B站直播录制\" -movflags +faststart \"").append(output).append('"')
        return sb.toString()
    }

    /** 元数据值转义（防引号/反斜杠破坏 ffmpeg 命令行） */
    private fun escMeta(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
