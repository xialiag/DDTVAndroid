package com.ddtv.app.core

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 弹幕 → 字幕(.srt)：把落盘/内存的弹幕生成可被播放器、剪辑软件加载的字幕文件。
 * 提供两种方式：
 *  - buildSrt：一组弹幕 → .srt 文本（时间轴从 startMs 偏移）
 *  - exportForVideo：从录像文件路径生成同名 .srt（关联同目录 danmu_*.json，按录像起点对齐）
 *  - saveForAuto：录制结束后自动生成 <base>.srt（设置"结束自动转字幕"开关用，与 danmu json 同名）
 */
object DanmakuExport {

    private fun fmtSrtTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3600000
        val m = (t % 3600000) / 60000
        val s = (t % 60000) / 1000
        val millis = t % 1000
        return String.format(Locale.CHINA, "%02d:%02d:%02d,%03d", h, m, s, millis)
    }

    /** 弹幕列表 → .srt 文本。startMs=时间原点(通常录像开始时间ms)；早于原点的归 0 */
    fun buildSrt(items: List<DanmakuItem>, startMs: Long): String {
        val sorted = items.sortedBy { it.time }
        if (sorted.isEmpty()) return ""
        val sb = StringBuilder()
        var idx = 1
        for (i in sorted.indices) {
            val it = sorted[i]
            val t = (it.time - startMs).coerceAtLeast(0)
            val next = if (i + 1 < sorted.size) sorted[i + 1].time else it.time + 5000
            val end = (next - startMs).coerceAtLeast(t + 800)
            val label = when (it.type) {
                "DANMU_MSG" -> "${it.user}: ${it.content}"
                "SEND_GIFT" -> "【礼物】${it.user} 送出 ${it.content}"
                "SUPER_CHAT_MESSAGE" -> "【SC】${it.user}: ${it.content}"
                "GUARD_BUY", "GUARD_RENEW" -> "【上舰】${it.user} ${it.content}"
                else -> "${it.user}: ${it.content}"
            }
            sb.append(idx++).append('\n')
                .append(fmtSrtTime(t)).append(" --> ").append(fmtSrtTime(end)).append('\n')
                .append(label).append("\n\n")
        }
        return sb.toString()
    }

    /** 从录像文件路径读同目录弹幕 json，生成同名 .srt */
    fun exportForVideo(videoPath: String): String {
        return try {
            val vf = File(videoPath)
            if (!vf.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            val dir = vf.parentFile ?: return """{"code":-1,"msg":"无目录"}"""
            val items = readDanmakuJsons(dir)
            if (items.isEmpty()) return """{"code":-1,"msg":"该目录下没有弹幕数据(需先录制并落盘弹幕)"}"""
            val startMs = videoStartMs(vf)
            val srt = buildSrt(items, startMs)
            if (srt.isBlank()) return """{"code":-1,"msg":"弹幕数据为空"}"""
            val out = File(dir, vf.nameWithoutExtension + ".srt")
            out.writeText(srt, Charsets.UTF_8)
            Logger.i("Danmaku", "字幕已生成: ${out.name} (${items.size}条)")
            """{"code":1,"msg":"字幕已生成","path":"${out.absolutePath}","count":${items.size}}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"生成字幕失败: ${e.message}"}"""
        }
    }

    /** 解析录像开始时间(ms)：优先文件名 [YYYY-MM-DD HH-mm-ss]，失败用文件修改时间 */
    private fun videoStartMs(vf: File): Long {
        val m = Regex("""\[(\d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2})\]""").find(vf.name)
        if (m != null) {
            return try {
                SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.CHINA)
                    .parse(m.groupValues[1])!!.time
            } catch (_: Exception) { vf.lastModified() }
        }
        return vf.lastModified()
    }

    /** 读目录下所有 danmu_*.json，合并弹幕条目 */
    private fun readDanmakuJsons(dir: File): List<DanmakuItem> {
        val out = ArrayList<DanmakuItem>()
        dir.listFiles { f -> f.name.matches(Regex("danmu_.*\\.json")) }?.forEach { f ->
            try {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                val arr = o.optJSONArray("items") ?: return@forEach
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    out.add(DanmakuItem(
                        type = j.optString("type", "DANMU_MSG"),
                        user = j.optString("user", ""),
                        uid = j.optLong("uid", 0),
                        content = j.optString("content", ""),
                        time = j.optLong("time", System.currentTimeMillis()),
                        color = j.optInt("color", 0),
                        extra = j.optString("extra", ""),
                    ))
                }
            } catch (_: Exception) {}
        }
        return out
    }

    /** 录制结束自动字幕：弹幕缓冲 → <base>.srt（base 与 danmu json 同名）；无弹幕返回 null */
    fun saveForAuto(items: List<DanmakuItem>, base: File): File? {
        if (items.isEmpty()) return null
        val start = items.minOfOrNull { it.time } ?: 0
        val srt = buildSrt(items, start)
        if (srt.isBlank()) return null
        val f = File(base.absolutePath + ".srt")
        return try { f.writeText(srt, Charsets.UTF_8); f } catch (_: Exception) { null }
    }
}
