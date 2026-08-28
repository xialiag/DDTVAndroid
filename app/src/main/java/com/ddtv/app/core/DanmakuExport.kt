package com.ddtv.app.core

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 弹幕 → 字幕(.srt / .ass)：把落盘/内存的弹幕生成可被播放器、剪辑软件加载的字幕文件。
 * 格式：
 *  - "srt"     → .srt 静态字幕（通用兼容，逐条字幕显示）
 *  - "ass"     → .ass 静态字幕（V4+ 样式，底部逐条叠加）
 *  - "assdm"   → .ass 弹幕（\move 从右往左滚动飞过，带弹幕颜色，最接近原版弹幕观感）
 */
object DanmakuExport {

    private fun label(it: DanmakuItem): String = when (it.type) {
        "DANMU_MSG" -> "${it.user}: ${it.content}"
        "SEND_GIFT" -> "【礼物】${it.user} 送出 ${it.content}"
        "SUPER_CHAT_MESSAGE" -> "【SC】${it.user}: ${it.content}"
        "GUARD_BUY", "GUARD_RENEW" -> "【上舰】${it.user} ${it.content}"
        else -> "${it.user}: ${it.content}"
    }

    /** 时间轴段：每条弹幕 [t, 下一条/末尾+5000)，返回 (start,end) ms */
    private fun segments(sorted: List<DanmakuItem>, startMs: Long): List<Pair<Long, Long>> {
        return sorted.mapIndexed { i, it ->
            val t = (it.time - startMs).coerceAtLeast(0)
            val next = if (i + 1 < sorted.size) sorted[i + 1].time else it.time + 5000
            val end = (next - startMs).coerceAtLeast(t + 800)
            t to end
        }
    }

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
        segments(sorted, startMs).forEachIndexed { idx, (t, end) ->
            sb.append(idx + 1).append('\n')
                .append(fmtSrtTime(t)).append(" --> ").append(fmtSrtTime(end)).append('\n')
                .append(label(sorted[idx])).append("\n\n")
        }
        return sb.toString()
    }

    private fun fmtAssTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3600000
        val m = (t % 3600000) / 60000
        val s = (t % 60000) / 1000
        val cs = (t % 1000) / 10
        return String.format(Locale.CHINA, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    private fun assHeader(fontPx: Int): String = "[Script Info]\nScriptType: v4.00+\nPlayResX: 1280\nPlayResY: 720\n\n" +
        "[V4+ Styles]\n" +
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
        "Style: Default,Microsoft YaHei,$fontPx,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,0,2,10,10,20,1\n\n" +
        "[Events]\n" +
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"

    /** 弹幕列表 → .ass 静态字幕（逐条底部叠加显示，适合当字幕看） */
    fun buildAss(items: List<DanmakuItem>, startMs: Long): String {
        val sorted = items.sortedBy { it.time }
        if (sorted.isEmpty()) return ""
        val sb = StringBuilder(assHeader(28))
        val segs = segments(sorted, startMs)
        segs.forEachIndexed { i, (t, end) ->
            val text = label(sorted[i]).replace("\n", " ")
            sb.append("Dialogue: 0,").append(fmtAssTime(t)).append(",").append(fmtAssTime(end))
                .append(",Default,,0,0,0,,").append(text).append('\n')
        }
        return sb.toString()
    }

    /** B站 0xRRGGBB → ASS 颜色 &H00BBGGRR（不透明）；0 或非法回退白色 */
    private fun assCol(color: Int): String {
        if (color == 0) return "&H00FFFFFF"
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return String.format(Locale.CHINA, "&H00%02x%02x%02x", b, g, r)
    }

    private fun escAss(s: String): String = s.replace("\\", "").replace("\n", " ").replace("\r", " ")

    /**
     * 弹幕列表 → .ass **弹幕**(滚动)：每条弹幕 \move 从右(1280)往左(-320)飞过，按条分轨道，带弹幕颜色。
     * 最接近原版弹幕观感（像视频里滚动飞过的弹幕）。
     */
    fun buildAssDanmaku(items: List<DanmakuItem>, startMs: Long): String {
        val sorted = items.sortedBy { it.time }
        if (sorted.isEmpty()) return ""
        val sb = StringBuilder(assHeader(24))
        val trackCount = 6
        val trackH = 44
        val y0 = 88
        val segs = segments(sorted, startMs)
        segs.forEachIndexed { i, (t, _) ->
            val it = sorted[i]
            val y = y0 + (i % trackCount) * trackH
            val col = assCol(it.color)
            val text = escAss(label(it))
            // 每条弹幕独立 3.2s 飞过；\move 从屏幕右外到左外
            sb.append("Dialogue: 0,").append(fmtAssTime(t)).append(",").append(fmtAssTime(t + 3200))
                .append(",Default,,0,0,0,,{\\move(1280,$y,-340,$y)\\1c$col}").append(text).append('\n')
        }
        return sb.toString()
    }

    /** 从录像文件路径读同目录弹幕 json，生成同名字幕（format: srt|ass|assdm） */
    fun exportForVideo(videoPath: String, format: String?): String {
        val f = format ?: "srt"
        val ext = if (f == "srt") "srt" else "ass"
        return try {
            val vf = File(videoPath)
            if (!vf.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            val dir = vf.parentFile ?: return """{"code":-1,"msg":"无目录"}"""
            val items = readDanmakuJsons(dir)
            if (items.isEmpty()) return """{"code":-1,"msg":"该目录下没有弹幕数据(需先录制并落盘弹幕)"}"""
            val startMs = videoStartMs(vf)
            val text = when (f) {
                "ass" -> buildAss(items, startMs)
                "assdm" -> buildAssDanmaku(items, startMs)
                else -> buildSrt(items, startMs)
            }
            if (text.isBlank()) return """{"code":-1,"msg":"弹幕数据为空"}"""
            val out = File(dir, vf.nameWithoutExtension + "." + ext)
            out.writeText(text, Charsets.UTF_8)
            Logger.i("Danmaku", "字幕已生成: ${out.name} ($f, ${items.size}条)")
            """{"code":1,"msg":"字幕已生成","path":"${out.absolutePath}","count":${items.size},"format":"$f"}"""
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

    /** 录制结束自动字幕：弹幕缓冲 → <base>.<ext>(format: srt|ass|assdm)；无弹幕返回 null */
    fun saveForAuto(items: List<DanmakuItem>, base: File, format: String?): File? {
        if (items.isEmpty()) return null
        val f = format ?: "srt"
        val ext = if (f == "srt") "srt" else "ass"
        val start = items.minOfOrNull { it.time } ?: 0
        val text = when (f) {
            "ass" -> buildAss(items, start)
            "assdm" -> buildAssDanmaku(items, start)
            else -> buildSrt(items, start)
        }
        if (text.isBlank()) return null
        val out = File(base.absolutePath + "." + ext)
        return try { out.writeText(text, Charsets.UTF_8); out } catch (_: Exception) { null }
    }
}
