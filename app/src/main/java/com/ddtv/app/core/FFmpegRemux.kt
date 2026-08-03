package com.ddtv.app.core

import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

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
     * 仅提取音频轨（仅录音频模式用）：ffmpeg -vn 丢弃视频流，音频 copy 不重编码，输出 m4a。
     * 成功返回 m4a 路径并删除原文件，失败返回 null（保留原文件）。
     */
    fun extractAudio(input: String): String? {
        val f = File(input)
        if (!f.exists()) return null
        val output = input.substringBeforeLast('.') + "_audio.m4a"
        if (File(output).exists()) return output
        Logger.i("Remux", "提取音频轨: $input")
        try {
            val cmd = "-y -i \"$input\" -vn -c:a copy -movflags +faststart \"$output\""
            val session = FFmpegKit.execute(cmd)
            val returnCode = session?.returnCode?.value ?: -1
            if (returnCode == 0 && File(output).exists() && File(output).length() > 0) {
                Logger.i("Remux", "音频提取完成: $output")
                try { f.delete() } catch (_: Exception) {}
                return output
            } else {
                val log = session?.allLogsAsString ?: ""
                val err = com.ddtv.app.core.FFmpegRepair.errorOf(log).ifBlank { "FFmpeg 返回 $returnCode" }
                Logger.w("Remux", "音频提取失败 rc=$returnCode: $err")
                try { File(output).delete() } catch (_: Exception) {}
                return null
            }
        } catch (e: Exception) {
            Logger.w("Remux", "音频提取异常: ${e.message}")
            return null
        }
    }
}
