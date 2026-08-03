package com.ddtv.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import com.ddtv.app.core.BiliLiveApi
import com.ddtv.app.core.FFmpegRepair
import com.ddtv.app.core.Http
import com.ddtv.app.core.LiveRecorder
import com.ddtv.app.core.Logger
import com.ddtv.app.core.RoomManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JS ↔ Kotlin 桥接。UI 全部在 WebView 中（VSCode 风格），业务逻辑在本类 + core 包。
 */
class DDTVBridge(private val context: Context, private val webView: WebView) {

    init {
        // 修复任务状态变化 → 推 JS 任务列表
        com.ddtv.app.core.RepairTaskManager.listener = { pushRepairTasks() }
    }

    // ============ 主题 ============

    /** 获取主题: "dark"/"light"/"system" */
    @JavascriptInterface
    fun getThemeSync(): String {
        return try {
            context.getSharedPreferences("ddtv_settings", Context.MODE_PRIVATE)
                .getString("theme", "system") ?: "system"
        } catch (e: Exception) {
            "system"
        }
    }

    /** 保存主题: "dark"/"light"/"system" */
    @JavascriptInterface
    fun setTheme(theme: String) {
        try {
            context.getSharedPreferences("ddtv_settings", Context.MODE_PRIVATE)
                .edit().putString("theme", theme).apply()
        } catch (e: Exception) {
            Logger.w("Bridge", "setTheme 失败: ${e.message}")
        }
    }

    // ============ 页面栈深度（Android 返回键） ============

    /** JS 页面栈深度，返回键据此判断弹页还是退出 */
    private var pageDepth = 0

    @JavascriptInterface
    fun setPageDepth(depth: Int) {
        pageDepth = depth.coerceAtLeast(0)
    }

    fun currentPageDepth(): Int = pageDepth

    // ============ 房间 ============

    @JavascriptInterface
    fun getRooms(): String {
        val arr = JSONArray()
        RoomManager.getRooms().forEach { arr.put(roomToJson(it)) }
        return arr.toString()
    }

    @JavascriptInterface
    fun addRoom(input: String): String {
        return try {
            when (RoomManager.addRoom(input)) {
                1 -> """{"code":1,"msg":"添加成功"}"""
                0 -> """{"code":0,"msg":"房间已存在"}"""
                else -> """{"code":-1,"msg":"无法解析房间号/短号/UID"}"""
            }
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun removeRoom(roomId: Long) {
        RoomManager.removeRoom(roomId)
    }

    @JavascriptInterface
    fun refreshRoom(roomId: Long) {
        Thread({ RoomManager.refreshRoomInfo(roomId) }, "RefreshRoom").start()
    }

    @JavascriptInterface
    fun setAutoRecord(roomId: Long, on: Boolean) {
        RoomManager.updateRoom(roomId) { it.autoRecord = on }
    }

    @JavascriptInterface
    fun setRemind(roomId: Long, on: Boolean) {
        RoomManager.updateRoom(roomId) { it.remind = on }
    }

    @JavascriptInterface
    fun setDanmakuOpen(roomId: Long, on: Boolean) {
        RoomManager.updateRoom(roomId) { card ->
            card.danmakuOpen = on
            if (on && card.liveStatus == 1) RoomManager.ensureDanmaku(card)
            if (!on) RoomManager.stopDanmaku(roomId)
        }
    }

    @JavascriptInterface
    fun setQuality(roomId: Long, qn: Int) {
        RoomManager.updateRoom(roomId) { it.quality = qn }
    }

    @JavascriptInterface
    fun setAudioOnly(roomId: Long, on: Boolean) {
        RoomManager.updateRoom(roomId) { it.audioOnly = on }
    }

    /**
     * 打开直播间观看（对应原版 Desktop 播放窗口）：
     * 优先 B站 App 深链 bilibili://live/{roomId}，失败则用浏览器打开网页版
     */
    @JavascriptInterface
    fun openLiveRoom(roomId: Long): String {
        return try {
            val deepLink = "bilibili://live/$roomId"
            val webUrl = "https://live.bilibili.com/$roomId"
            // 方式A：指定 B站 App 包名深链
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.setPackage("tv.danmaku.bili")
                context.startActivity(intent)
                return """{"code":1,"msg":"已打开B站App直播间"}"""
            } catch (e: android.content.ActivityNotFoundException) {
                Logger.d("Bridge", "openLiveRoom 深链 ActivityNotFound")
            } catch (e: Exception) {
                Logger.w("Bridge", "openLiveRoom 深链异常: ${e.javaClass.simpleName}: ${e.message}")
            }
            // 方式B：不指定包名
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return """{"code":1,"msg":"已打开B站App直播间"}"""
            } catch (e: android.content.ActivityNotFoundException) {
                Logger.d("Bridge", "openLiveRoom 无包名深链 ActivityNotFound")
            }
            // 兜底：浏览器打开网页版
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return """{"code":1,"msg":"已用浏览器打开直播间"}"""
            } catch (e: Exception) {
                Logger.e("Bridge", "openLiveRoom 浏览器跳转失败: ${e.message}")
                """{"code":-1,"msg":"${e.message}"}"""
            }
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun startRecordNow(roomId: Long): String {
        val card = RoomManager.getRoom(roomId) ?: return """{"code":-1,"msg":"房间不存在"}"""
        if (LiveRecorder.isRecording(roomId)) return """{"code":0,"msg":"正在录制中"}"""
        card.manualStop = false
        Thread({
            val ok = LiveRecorder.start(card)
            if (!ok) {
                pushLog(roomId, "warn", "录制启动失败")
                pushToJs("""{"type":"toast","msg":"录制启动失败，请查看运行日志","level":"err"}""")
            }
        }, "StartRec").start()
        return """{"code":1,"msg":"已启动"}"""
    }

    @JavascriptInterface
    fun stopRecordNow(roomId: Long) {
        RoomManager.getRoom(roomId)?.let {
            it.manualStop = true  // 手动停止：直播不断时轮询不自动重启
            RoomManager.stopRecording(it)
        }
    }

    @JavascriptInterface
    fun getRecentDanmaku(roomId: Long, limit: Int): String {
        val arr = JSONArray()
        RoomManager.getRecentDanmaku(roomId, limit).forEach { arr.put(danmakuToJson(it)) }
        return arr.toString()
    }

    @JavascriptInterface
    fun retryDanmaku(roomId: Long) {
        RoomManager.retryDanmaku(roomId)
    }

    /** 查询弹幕连接状态（面板打开时主动拉取，避免错过一次性状态事件） */
    @JavascriptInterface
    fun getDanmakuStatus(roomId: Long): String {
        val (connected, msg) = RoomManager.getDanmakuStatus(roomId)
        val m = if (connected) "已连接" else msg
        return """{"connected":$connected,"msg":"$m"}"""
    }

    @JavascriptInterface
    fun sendDanmaku(roomId: Long, text: String): String {
        val ok = RoomManager.sendDanmaku(roomId, text)
        return if (ok) """{"code":1,"msg":"已发送"}""" else """{"code":-1,"msg":"发送失败，请先登录"}"""
    }

    // ============ 设置 ============

    @JavascriptInterface
    fun getSettings(): String {
        val s = RoomManager.settings
        return JSONObject().apply {
            put("pollInterval", s.pollInterval)
            put("quality", s.defaultQuality)
            put("splitByTitle", s.splitByTitle)
            put("splitSeconds", s.splitSeconds)
            put("splitSizeMB", s.splitSizeMB)
            put("remuxAfterLive", s.remuxAfterLive)
            put("watchHeartbeat", s.watchHeartbeat)
            put("remindLive", s.remindLive)
            put("blockBarrage", s.blockBarrage)
            put("fileNameFormat", s.fileNameFormat)
            put("repairDeleteSource", s.repairDeleteSource)
            put("debugServer", s.debugServer)
            put("updateRepo", s.updateRepo)
            put("autoUpdate", s.autoUpdate)
            put("outputDir", RoomManager.outputDir.absolutePath)
            put("version", com.ddtv.app.BuildConfig.VERSION_NAME)
        }.toString()
    }

    @JavascriptInterface
    fun setSettings(json: String): String {
        return try {
            val o = JSONObject(json)
            RoomManager.settings.apply {
                pollInterval = o.optInt("pollInterval", pollInterval)
                defaultQuality = o.optInt("quality", defaultQuality)
                splitByTitle = o.optBoolean("splitByTitle", splitByTitle)
                splitSeconds = o.optLong("splitSeconds", splitSeconds)
                splitSizeMB = o.optLong("splitSizeMB", splitSizeMB)
                remuxAfterLive = o.optBoolean("remuxAfterLive", remuxAfterLive)
                watchHeartbeat = o.optBoolean("watchHeartbeat", watchHeartbeat)
                remindLive = o.optBoolean("remindLive", remindLive)
                blockBarrage = o.optString("blockBarrage", blockBarrage)
                fileNameFormat = o.optString("fileNameFormat", fileNameFormat)
                repairDeleteSource = o.optBoolean("repairDeleteSource", repairDeleteSource)
                recordMode = o.optString("recordMode", recordMode)
                flvAppendOnReconnect = o.optBoolean("flvAppendOnReconnect", flvAppendOnReconnect)
                debugServer = o.optBoolean("debugServer", debugServer)
                updateRepo = o.optString("updateRepo", updateRepo)
                autoUpdate = o.optBoolean("autoUpdate", autoUpdate)
            }
            RoomManager.saveSettings()
            com.ddtv.app.core.LiveRecorder.applySettings(RoomManager.settings)
            """{"code":1,"msg":"设置已保存"}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun setPolling(on: Boolean) {
        if (on) RoomManager.startPolling() else RoomManager.stopPolling()
    }

    /** 调试服务器开关（19864 端口，默认关） */
    @JavascriptInterface
    fun setDebugServer(on: Boolean): String {
        RoomManager.settings.debugServer = on
        RoomManager.saveSettings()
        if (on) com.ddtv.app.core.DebugServer.start() else com.ddtv.app.core.DebugServer.stop()
        return """{"code":1,"msg":"${if (on) "调试服务器已开启(端口 19864)" else "调试服务器已关闭"}"""
    }

    // ============ 自动更新（GitHub Releases，参照原版 ProgramUpdates） ============

    private val currentVersion: String = "0.7.0"

    /** 解析 "v0.7.0" / "0.7.0-beta1" 为可比较数字段列表 */
    private fun versionParts(v: String): List<Long> {
        return Regex("\\d+").findAll(v).map { it.value.toLong() }.toList()
    }

    private fun versionCompare(a: String, b: String): Int {
        val pa = versionParts(a); val pb = versionParts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0L }; val y = pb.getOrElse(i) { 0L }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 检查 GitHub Releases 最新版，结果异步推给 JS（update_result 事件） */
    @JavascriptInterface
    fun checkUpdate(repo: String) {
        val ownerRepo = repo.trim().removePrefix("https://github.com/").removeSuffix("/")
        Thread({
            try {
                val url = "https://api.github.com/repos/$ownerRepo/releases/latest"
                val body = Http.get(url)
                val o = JSONObject(body)
                val tag = o.optString("tag_name", "")
                if (tag.isEmpty()) {
                    pushToJs("""{"type":"update_result","ok":false,"msg":"仓库不存在或无 Release"}""")
                    return@Thread
                }
                val latest = tag.removePrefix("v")
                val hasUpdate = versionCompare(latest, currentVersion) > 0
                pushToJs(JSONObject().apply {
                    put("type", "update_result")
                    put("ok", true)
                    put("hasUpdate", hasUpdate)
                    put("current", currentVersion)
                    put("latest", latest)
                    put("url", o.optString("html_url", "https://github.com/$ownerRepo/releases"))
                    put("note", o.optString("body", "").take(500))
                }.toString())
            } catch (e: Exception) {
                pushToJs(JSONObject().apply {
                    put("type", "update_result")
                    put("ok", false)
                    put("msg", e.message ?: "网络错误")
                }.toString())
            }
        }, "CheckUpdate").apply { isDaemon = true; start() }
    }

    /** 用系统浏览器打开链接 */
    @JavascriptInterface
    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            Logger.w("Bridge", "打开链接失败: ${e.message}")
        }
    }

    // ============ 录制文件 ============

    @JavascriptInterface
    fun getRecordFiles(): String {
        val arr = JSONArray()
        try {
            val root = RoomManager.outputDir
            if (root.exists()) {
                root.listFiles()?.forEach { liver ->
                    if (liver.isDirectory) {
                        liver.listFiles()?.forEach { day ->
                            if (day.isDirectory) {
                                day.listFiles()?.forEach { f ->
                                    if (f.isFile && (f.name.endsWith(".flv") || f.name.endsWith(".mp4"))) {
                                        arr.put(JSONObject().apply {
                                            put("name", f.name)
                                            put("path", f.absolutePath)
                                            put("size", f.length())
                                            put("mtime", f.lastModified())
                                            put("uploader", liver.name)
                                            put("isFlv", f.name.endsWith(".flv"))
                                            // 封面：同目录保存的 _cover.jpg（LiveRecorder.saveCoverIfNeeded），
                                            // 转 content:// URI 供 WebView 加载（不依赖监控房间列表/网络）
                                            val cover = java.io.File(f.absolutePath.substringBeforeLast('.') + "_cover.jpg")
                                            if (cover.exists()) {
                                                try {
                                                    put("coverPath", androidx.core.content.FileProvider.getUriForFile(
                                                        context, "com.ddtv.app.fileprovider", cover).toString())
                                                } catch (_: Exception) {}
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("Bridge", "getRecordFiles 失败: ${e.message}")
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun deleteRecordFile(path: String): String {
        return try {
            val f = java.io.File(path)
            if (f.exists() && f.delete()) """{"code":1,"msg":"已删除"}""" else """{"code":0,"msg":"删除失败"}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    /** 重命名录制文件（不含扩展名；同步改名同名的 mp4/_repaired 变体） */
    @JavascriptInterface
    fun renameFile(path: String, newName: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            val name = newName.trim()
            if (name.isEmpty()) return """{"code":-1,"msg":"文件名不能为空"}"""
            val sanitized = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val ext = f.extension
            val newFile = java.io.File(f.parentFile, if (ext.isNotEmpty()) "$sanitized.$ext" else sanitized)
            if (newFile.exists()) return """{"code":-1,"msg":"同名文件已存在"}"""
            if (f.renameTo(newFile)) {
                // 同步改名转封装变体（xxx.mp4 / xxx_repaired.mp4 / xxx_transcoded.mp4）
                try {
                    val base = f.absolutePath.substringBeforeLast('.')
                    listOf("$base.mp4", "${base}_repaired.mp4", "${base}_transcoded.mp4").forEach { p ->
                        val v = java.io.File(p)
                        if (v.exists()) {
                            val suffix = p.substringAfter(base, "")
                            v.renameTo(java.io.File(newFile.parentFile, newFile.nameWithoutExtension + suffix + "." + v.extension))
                        }
                    }
                } catch (_: Exception) {}
                pushToJs("""{"type":"files_changed"}""")
                """{"code":1,"msg":"已重命名","path":"${newFile.absolutePath}"}"""
            } else """{"code":-1,"msg":"重命名失败"}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    /** 复制文本到剪贴板（文件路径等） */
    @JavascriptInterface
    fun copyText(text: String): String {
        return try {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DDTV", text))
            """{"code":1}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun remuxFile(path: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            val t = com.ddtv.app.core.RepairTaskManager.submit(path, "remux")
            """{"code":1,"msg":"已加入任务队列","id":${t.id}}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    // ============ 录制历史 ============

    @JavascriptInterface
    fun getHistories(): String {
        val arr = JSONArray()
        RoomManager.getHistories().forEachIndexed { idx, h ->
            arr.put(JSONObject().apply {
                put("name", h.name)
                put("time", h.time)
                put("title", h.title)
                put("roomId", h.roomId)
                put("fileCount", h.fileCount)
                put("index", idx)
                // 历史封面：该主播录制目录下最新一张 _cover.jpg
                put("coverPath", RoomManager.latestCoverFor(h.name) ?: "")
            })
        }
        return arr.toString()
    }

    /** 删除一条录制历史 */
    @JavascriptInterface
    fun deleteHistory(index: Int): String {
        return if (RoomManager.deleteHistory(index)) """{"code":1,"msg":"已删除"}""" else """{"code":-1,"msg":"删除失败"}"""
    }

    // ============ 数据统计 ============

    @JavascriptInterface
    fun getStats(): String {
        return RoomManager.getStats().toString()
    }

    // ============ 修复工具（对应原版 ToolsPage 手动修复） ============

    /**
     * 手动修复/转封装文件（FFmpegKit）：
     * - mode=remux: -c copy 快速转封装（flv→mp4）
     * - mode=repair: 修复损坏文件（-err_detect ignore_err 重封装）
     */
    @JavascriptInterface
    fun repairFile(path: String, mode: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            if (com.ddtv.app.core.LiveRecorder.isFileBeingRecorded(path))
                return """{"code":-1,"msg":"该文件正在录制中，结束后才能修复"}"""
            val t = com.ddtv.app.core.RepairTaskManager.submit(path, mode)
            """{"code":1,"msg":"已加入任务队列","id":${t.id}}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    // ============ 修复任务队列（任务列表/管理） ============

    @JavascriptInterface
    fun getRepairTasks(): String {
        val arr = JSONArray()
        com.ddtv.app.core.RepairTaskManager.list().forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    @JavascriptInterface
    fun cancelRepairTask(id: Long) {
        com.ddtv.app.core.RepairTaskManager.cancel(id)
    }

    @JavascriptInterface
    fun retryRepairTask(id: Long) {
        com.ddtv.app.core.RepairTaskManager.retry(id)
    }

    @JavascriptInterface
    fun removeRepairTask(id: Long): String {
        return if (com.ddtv.app.core.RepairTaskManager.remove(id))
            """{"code":1,"msg":"已删除"}"""
        else """{"code":-1,"msg":"运行中/排队中不能删除"}"""
    }

    @JavascriptInterface
    fun clearRepairTasks(): String {
        val n = com.ddtv.app.core.RepairTaskManager.clearFinished()
        return """{"code":1,"msg":"已清理 $n 条记录"}"""
    }

    private fun pushRepairTasks() {
        val arr = JSONArray()
        com.ddtv.app.core.RepairTaskManager.list().forEach { arr.put(it.toJson()) }
        pushToJs("""{"type":"repair_task_update","tasks":$arr}""")
    }

    /** 分享文件（修复结果等） */
    @JavascriptInterface
    fun shareFile(path: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "com.ddtv.app.fileprovider", f
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享录制文件"))
            """{"code":1,"msg":"ok"}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    /** 播放录制文件：自动选可播变体（已转码 > 已修复 > 转封装 mp4）；FLV/拼接 fMP4 先修复再播 */
    @JavascriptInterface
    fun playFile(path: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            // 变体优先：系统播放器对 FLV 与 HLS 拼接的 fMP4 支持差，优先播修复/转码产物
            val base = f.absolutePath.removeSuffix(".flv").removeSuffix(".mp4")
            val preferred = listOf("${base}_transcoded.mp4", "${base}_repaired.mp4", "$base.mp4")
                .firstOrNull { java.io.File(it).exists() && java.io.File(it).length() > 0 }
            val play = preferred ?: path
            val pf = java.io.File(play)
            if (!pf.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            if (!play.endsWith(".flv")) {
                if (preferred != null && play != path) {
                    // 已有修复/转码/转封装产物：直接播
                    openPlayer(play)
                    return """{"code":1,"msg":"ok"}"""
                }
                // 普通 mp4（非分片封装）可直播；HLS 拼接的 fMP4 需先修复
                if (!isFragmentedMp4(pf)) {
                    openPlayer(play)
                    return """{"code":1,"msg":"ok"}"""
                }
            }
            // FLV 或 HLS 原始拼接：异步修复链（repair → transcode 兜底），完成后自动播放
            Thread({
                if (com.ddtv.app.core.LiveRecorder.isFileBeingRecorded(path)) {
                    pushLog(0, "warn", "录制中的文件暂不能修复，请录制结束后再试")
                    pushToJs("""{"type":"toast","msg":"录制中的文件暂不能修复","level":"warn"}""")
                    return@Thread
                }
                Logger.i("Bridge", "播放前修复: $path")
                pushLog(0, "info", "正在修复录制文件，完成后自动播放…")
                var out = FFmpegRepair.repair(path, "repair")
                if (out == null) {
                    pushLog(0, "warn", "快速修复失败，尝试完整转码…")
                    out = FFmpegRepair.repair(path, "transcode")
                }
                val finalPath = out ?: path
                webView.post {
                    if (out != null && java.io.File(out).exists()) {
                        pushToJs("""{"type":"toast","msg":"修复完成，开始播放","level":"ok"}""")
                    } else {
                        pushToJs("""{"type":"toast","msg":"文件损坏，无法修复，尝试直接播放","level":"warn"}""")
                    }
                    openPlayer(finalPath)
                    pushToJs("""{"type":"files_changed"}""")  // 刷新文件列表（修复产物已生成）
                }
            }, "PlayFix").apply { isDaemon = true; start() }
            """{"code":1,"msg":"正在修复"}"""
        } catch (e: Exception) {
            Logger.e("Bridge", "playFile 失败: ${e.message}")
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    /** 检测 fMP4（HLS init+分片拼接）：文件头 1MB 内出现 moof box 即为分片 MP4 */
    private fun isFragmentedMp4(f: java.io.File): Boolean {
        return try {
            if (!f.exists() || f.length() < 12) return false
            val len = minOf(1024 * 1024, f.length().toInt())
            val buf = ByteArray(len)
            java.io.RandomAccessFile(f, "r").use { raf -> raf.readFully(buf) }
            String(buf, Charsets.ISO_8859_1).contains("moof")
        } catch (e: Exception) {
            Logger.w("Bridge", "fMP4 检测失败: ${e.message}")
            false
        }
    }

    /** 用系统播放器打开本地视频 */
    private fun openPlayer(path: String) {
        val f = java.io.File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.ddtv.app.fileprovider", f)
        val mime = when {
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".flv") -> "video/x-flv"
            else -> "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ============ 账号 ============

    // ============ 账号（Web 扫码登录，与原版 DDTV 一致） ============

    @JavascriptInterface
    fun getAccount(): String {
        val am = com.ddtv.app.core.AccountManager
        val acc = am.account
        val web = am.getWebAccount()
        val json = JSONObject().apply {
            put("code", 1)
            put("logged", acc != null && acc.isLoggedIn)
            if (acc != null && acc.isLoggedIn) {
                put("uid", acc.uid)
                put("uname", acc.uname)
                put("face", acc.face)
                put("level", acc.level)
            }
            put("web", JSONObject().apply {
                put("logged", web != null)
                if (web != null) { put("uid", web.uid); put("uname", web.uname); put("face", web.face) }
            })
        }
        return json.toString()
    }

    @JavascriptInterface
    fun startQrcodeLogin() {
        com.ddtv.app.core.AccountManager.startQrcodeLogin()
    }

    @JavascriptInterface
    fun cancelQrcodeLogin() {
        com.ddtv.app.core.AccountManager.cancelQrcodeLogin()
    }

    /**
     * 打开文件管理器选择文件（SAF，修复工具用）。
     * 选中后复制到应用私有目录，通过 file_picked 事件把路径推给 JS
     */
    @JavascriptInterface
    fun pickFile(mimeType: String) {
        try {
            val activity = context as? android.app.Activity ?: run {
                pushLog(0, "warn", "文件选择不可用")
                return
            }
            MainActivity.filePickCallback = { path ->
                MainActivity.filePickCallback = null
                if (path != null) {
                    pushLog(0, "info", "已选择文件: ${path.substringAfterLast('/')}")
                    pushToJs("""{"type":"file_picked","path":"${path.replace("\\", "\\\\").replace("\"", "\\\"")}"}""")
                } else {
                    pushLog(0, "info", "已取消选择")
                }
            }
            MainActivity.pickFileFromSystem(mimeType.ifBlank { "*/*" })
            Logger.d("Bridge", "pickFile 已启动: $mimeType")
        } catch (e: Exception) {
            Logger.e("Bridge", "pickFile 异常: ${e.message}")
        }
    }

    @JavascriptInterface
    fun logout() {
        com.ddtv.app.core.AccountManager.logout()
    }

    /**
     * 跳转B站确认（移植自 BBDownAndroid openBiliApp）：
     * 优先用 bilibili://browser?url= 深链接在B站App内置浏览器打开授权确认页
     * （App 已登录则直接显示确认按钮）；无 App 则跳应用市场/浏览器。
     */
    @JavascriptInterface
    fun openAuthBrowser(): String {
        return try {
            Logger.i("Bridge", "openAuthBrowser 开始")
            // 扫码登录:跳转 B站App 内置浏览器打开授权确认页(App 已登录则直接显示确认按钮)
            val authUrl = com.ddtv.app.core.AccountManager.ensureQrLogin()
            Logger.i("Bridge", "openAuthBrowser authUrl=$authUrl")
            if (authUrl.isBlank()) return """{"code":-1,"msg":"授权链接获取失败，请稍后重试"}"""
            val pm = context.packageManager
            val biliPackage = "tv.danmaku.bili"

            // 检查 B站 App 是否已安装（Android 11+ 需要 <queries> 声明）
            val isBiliInstalled = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(biliPackage, 0)
                Logger.i("Bridge", "openAuthBrowser 检测到B站App")
                true
            } catch (e: PackageManager.NameNotFoundException) {
                Logger.w("Bridge", "openAuthBrowser 未检测到B站App (NameNotFoundException)")
                false
            } catch (e: Exception) {
                Logger.e("Bridge", "openAuthBrowser getPackageInfo 异常: ${e.javaClass.simpleName}: ${e.message}")
                false
            }

            if (!isBiliInstalled) {
                Logger.i("Bridge", "openAuthBrowser 尝试跳转应用市场")
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$biliPackage"))
                    marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(marketIntent)
                    return """{"code":1,"msg":"未检测到B站App，已跳转应用市场"}"""
                } catch (e: Exception) {
                    Logger.w("Bridge", "openAuthBrowser 应用市场跳转失败: ${e.message}")
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://app.bilibili.com"))
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(browserIntent)
                        return """{"code":1,"msg":"未检测到B站App，已打开官网"}"""
                    } catch (e2: Exception) {
                        Logger.e("Bridge", "openAuthBrowser 浏览器跳转失败: ${e2.message}")
                        return """{"code":-1,"msg":"未检测到哔哩哔哩App，请先安装"}"""
                    }
                }
            }

            // 已安装：用深链接在 B站App 内置浏览器打开授权确认页
            val encodedUrl = java.net.URLEncoder.encode(authUrl, "UTF-8")
            val deepLinks = listOf(
                "bilibili://browser?url=$encodedUrl",
                "bilibili://browser?url=$encodedUrl&navhide=1",
                "bilibili://forward?url=$encodedUrl",
                "activity://main/web?url=$encodedUrl"
            )
            for (deepLink in deepLinks) {
                // 方式A：指定B站App包名
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setPackage(biliPackage)
                    context.startActivity(intent)
                    Logger.i("Bridge", "openAuthBrowser 深链接成功(指定包名): $deepLink")
                    return """{"code":1,"msg":"已跳转B站App，请点击确认授权"}"""
                } catch (e: android.content.ActivityNotFoundException) {
                    Logger.d("Bridge", "openAuthBrowser 深链接 ActivityNotFound: $deepLink")
                } catch (e: SecurityException) {
                    Logger.w("Bridge", "openAuthBrowser 深链接 SecurityException: $deepLink - ${e.message}")
                } catch (e: Exception) {
                    Logger.w("Bridge", "openAuthBrowser 深链接异常: $deepLink - ${e.javaClass.simpleName}: ${e.message}")
                }
                // 方式B：不指定包名（bilibili:// scheme 只有B站App能处理）
                if (deepLink.startsWith("bilibili://")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Logger.i("Bridge", "openAuthBrowser 深链接成功(无包名): $deepLink")
                        return """{"code":1,"msg":"已跳转B站App，请点击确认授权"}"""
                    } catch (e: android.content.ActivityNotFoundException) {
                        Logger.d("Bridge", "openAuthBrowser 无包名深链接 ActivityNotFound: $deepLink")
                    } catch (e: Exception) {
                        Logger.w("Bridge", "openAuthBrowser 无包名深链接异常: $deepLink - ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            // 兜底：直接启动 B站App 主界面
            Logger.i("Bridge", "openAuthBrowser 所有深链接失败，尝试启动App主界面")
            val launchIntent = pm.getLaunchIntentForPackage(biliPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Logger.i("Bridge", "openAuthBrowser 启动App主界面成功")
                return """{"code":1,"msg":"已打开B站App，请手动扫码完成授权"}"""
            }

            Logger.w("Bridge", "openAuthBrowser getLaunchIntentForPackage 返回 null")
            """{"code":-1,"msg":"无法跳转B站授权页面，请直接扫码"}"""
        } catch (e: Exception) {
            Logger.e("Bridge", "openAuthBrowser 顶层异常: ${e.javaClass.simpleName}: ${e.message}")
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    // ============ 调试日志 ============

    /** 保存录制目录（设置页文字输入，参照 BBDownAndroid） */
    @JavascriptInterface
    fun setOutputDir(path: String): String {
        val err = RoomManager.setOutputDir(path.trim())
        return if (err == null) """{"code":1,"msg":"录制目录已保存"}"""
        else """{"code":-1,"msg":"$err"}"""
    }

    /** 请求存储权限（Android 11+ 跳「所有文件访问」设置页，更早版本弹运行时权限） */
    @JavascriptInterface
    fun requestStoragePermission() {
        try {
            val act = context as? android.app.Activity ?: return
            act.runOnUiThread {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        act.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${act.packageName}")))
                    } catch (_: Exception) {
                        try {
                            act.startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Exception) {}
                    }
                } else {
                    act.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1005)
                }
            }
        } catch (_: Exception) {}
    }

    // ============ 权限（设置页手动授权入口，不再依赖特定操作触发） ============

    /** 查询权限状态：type = notification | storage | battery */
    @JavascriptInterface
    fun getPermissionStatus(type: String): String {
        val granted = try {
            when (type) {
                "notification" -> {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else true
                }
                "storage" -> {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.os.Environment.isExternalStorageManager()
                    } else true
                }
                "battery" -> {
                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                }
                else -> false
            }
        } catch (_: Exception) { false }
        return """{"code":1,"granted":$granted}"""
    }

    /** 手动触发授权：type = notification | storage | battery */
    @JavascriptInterface
    fun requestPermission(type: String) {
        try {
            val act = context as? android.app.Activity ?: return
            when (type) {
                "notification" -> act.runOnUiThread {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            act, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1006)
                    }
                }
                "battery" -> act.runOnUiThread {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try {
                            act.startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:${act.packageName}")))
                        } catch (_: Exception) {
                            try {
                                act.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    }
                }
                "storage" -> requestStoragePermission()
            }
        } catch (_: Exception) {}
    }

    /** JS 侧调试日志统一写入运行日志（统一入口，不再散落在页面 DOM） */
    @JavascriptInterface
    fun logDebug(msg: String) {
        com.ddtv.app.core.Logger.i(0, "[JS] ${msg.take(300)}")
    }

    @JavascriptInterface
    fun getDebugLogs(): String {
        val arr = JSONArray()
        com.ddtv.app.core.Logger.recent(100).forEach { l ->
            arr.put(JSONObject().apply {
                put("time", l.time)
                put("level", l.level)
                put("msg", l.msg)
            })
        }
        return arr.toString()
    }

    // ============ 日志保存/管理（BBDownAndroid 同款） ============

    /** 清空内存日志（文件日志保留） */
    @JavascriptInterface
    fun clearDebugLogs(): String {
        com.ddtv.app.core.Logger.clear()
        com.ddtv.app.core.Logger.i("Bridge", "内存日志已清空")
        return """{"code":1,"msg":"日志已清除"}"""
    }

    /** 保存调试日志到文件 → {path}（与崩溃日志同目录 logs/） */
    @JavascriptInterface
    fun saveLogsToFile(): String {
        return try {
            val logDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
                .format(java.util.Date())
            val logFile = File(logDir, "ddtv_log_$timestamp.txt")
            com.ddtv.app.core.Logger.exportToFile(logFile)
            com.ddtv.app.core.Logger.i("Bridge", "日志已保存到: ${logFile.absolutePath} (共 ${com.ddtv.app.core.Logger.getCount()} 条)")
            JSONObject().apply {
                put("code", 1)
                put("msg", "日志已保存")
                put("path", logFile.absolutePath)
            }.toString()
        } catch (e: Exception) {
            com.ddtv.app.core.Logger.e("Bridge", "保存日志失败", e)
            """{"code":-1,"msg":"保存日志失败: ${e.message}"}"""
        }
    }

    /** 分享日志文件（系统分享面板） */
    @JavascriptInterface
    fun shareLogFile(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists()) return """{"code":-1,"msg":"文件不存在"}"""
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "DDTV 调试日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            """{"code":1,"msg":"ok"}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"分享失败: ${e.message}"}"""
        }
    }

    /** 崩溃日志列表 → [{filename,time,size,content,path}]（content 截断防卡顿） */
    @JavascriptInterface
    fun getCrashLogs(): String {
        return try {
            val arr = JSONArray()
            CrashHandler.getCrashLogs(context).forEach { f ->
                val j = JSONObject()
                j.put("filename", f.name)
                j.put("time", f.lastModified())
                j.put("size", f.length())
                j.put("path", f.absolutePath)
                val content = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                j.put("content", content.take(3000))
                arr.put(j)
            }
            arr.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    /** 删除指定崩溃日志 */
    @JavascriptInterface
    fun deleteCrashLog(name: String): String {
        return if (CrashHandler.deleteCrashLog(context, name)) {
            com.ddtv.app.core.Logger.i("Bridge", "已删除崩溃日志: $name")
            """{"code":1,"msg":"已删除"}"""
        } else {
            """{"code":-1,"msg":"删除失败"}"""
        }
    }

    /** 清空全部崩溃日志 */
    @JavascriptInterface
    fun clearCrashLogs(): String {
        var n = 0
        CrashHandler.getCrashLogs(context).forEach { if (it.delete()) n++ }
        com.ddtv.app.core.Logger.i("Bridge", "已清除 $n 个崩溃日志")
        return """{"code":1,"msg":"已清除 $n 个崩溃日志"}"""
    }

    // ============ 关注列表 ============

    /** 关注分组（异步：同步桥里发网络请求会抛 NetworkOnMainThreadException 并冻结 JS 线程） */
    @JavascriptInterface
    fun loadFollowGroups() {
        Thread({
            try {
                val arr = JSONArray()
                BiliLiveApi.getFollowGroups().forEach { g ->
                    arr.put(JSONObject().apply {
                        put("tagid", g.tagid)
                        put("name", g.name)
                        put("count", g.count)
                    })
                }
                pushToJs("""{"type":"follow_groups","groups":$arr}""")
            } catch (e: Exception) {
                pushToJs("""{"type":"follow_groups","groups":[]}""")
            }
        }, "FollowGroups").apply { isDaemon = true; start() }
    }

    @JavascriptInterface
    fun getFollowList(tagid: Long, page: Int): String {
        val uid = com.ddtv.app.core.AccountManager.account?.uid ?: return "[]"
        val arr = JSONArray()
        // tagid = -1 表示全部关注（合并全部分组，自动翻页）；单分组也用实时接口刷新直播状态
        val list = if (tagid == -1L) BiliLiveApi.getFollowAll(uid)
            else BiliLiveApi.refreshLiveStatus(BiliLiveApi.getFollowList(uid, tagid, page))
        list.forEach { u ->
            arr.put(JSONObject().apply {
                put("mid", u.mid)
                put("uname", u.uname)
                put("face", u.face)
                put("liveStatus", u.liveStatus)
                put("roomId", u.roomId)
            })
        }
        return arr.toString()
    }

    /**
     * 异步加载关注列表（线程池执行，完成后 push follows_loaded 事件）。
     * 账号页入口：同步版 getFollowList 会在 WebView JS 线程上跑完整网络流程（全部分组+分页+实时状态），
     * 关注人数多时阻塞 JS 线程导致页面卡死——必须异步。
     */
    @JavascriptInterface
    fun loadFollows(tagid: Long, reqId: Long) {
        Thread({
            try {
                val uid = com.ddtv.app.core.AccountManager.account?.uid
                if (uid == null) {
                    pushToJs("""{"type":"follows_loaded","tagid":$tagid,"reqId":$reqId,"users":[]}""")
                    return@Thread
                }
                // tagid = -1 用缓存版（5 分钟内不重复全量拉取）
                val list = if (tagid == -1L) BiliLiveApi.getFollowAllCached(uid)
                    else BiliLiveApi.refreshLiveStatus(BiliLiveApi.getFollowList(uid, tagid, 1))
                val arr = JSONArray()
                list.forEach { u ->
                    arr.put(JSONObject().apply {
                        put("mid", u.mid)
                        put("uname", u.uname)
                        put("face", u.face)
                        put("liveStatus", u.liveStatus)
                        put("roomId", u.roomId)
                    })
                }
                pushToJs("""{"type":"follows_loaded","tagid":$tagid,"reqId":$reqId,"users":$arr}""")
            } catch (e: Exception) {
                pushToJs("""{"type":"follows_loaded","tagid":$tagid,"reqId":$reqId,"users":[]}""")
            }
        }, "LoadFollows").apply { isDaemon = true; start() }
    }

    @JavascriptInterface
    fun importFollows(midsJson: String): String {
        return try {
            val arr = JSONArray(midsJson)
            if (arr.length() == 0) return """{"code":-1,"msg":"未勾选用户"}"""
            Thread({
                // 兼容旧格式（纯 mid 数字数组）与新格式（[{mid, roomId}]，直播中的 UP 带房间号）
                val first = arr.opt(0)
                if (first is Number) {
                    val uids = mutableListOf<Long>()
                    for (i in 0 until arr.length()) uids.add(arr.optLong(i))
                    val added = RoomManager.addRoomsBatch(uids)
                    pushLog(0, "info", "关注导入完成，新增 $added 个房间")
                } else {
                    val items = mutableListOf<Pair<Long, Long>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        items.add(o.optLong("mid") to o.optLong("roomId"))
                    }
                    val added = RoomManager.addRoomsBatchWithRoomIds(items)
                    pushLog(0, "info", "关注导入完成，新增 $added 个房间")
                }
                pushToJs("""{"type":"rooms_changed"}""")
            }, "ImportFollow").start()
            """{"code":1,"msg":"导入中…"}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
    }

    // ============ 日志 ============

    @JavascriptInterface
    fun getLogs(limit: Int): String {
        val arr = JSONArray()
        com.ddtv.app.core.Logger.recent(limit).forEach { l ->
            arr.put(JSONObject().apply {
                put("roomId", l.roomId)
                put("level", l.level)
                put("msg", l.msg)
                put("time", l.time)
            })
        }
        return arr.toString()
    }

    // ============ 序列化 ============

    private fun roomToJson(card: com.ddtv.app.core.RoomCard): JSONObject {
        return JSONObject().apply {
            put("roomId", card.roomId)
            put("shortId", card.shortId)
            put("uid", card.uid)
            put("name", card.name)
            put("face", card.face)
            put("sign", card.sign)
            put("title", card.title)
            put("cover", card.cover)
            put("liveStatus", card.liveStatus)
            put("liveTime", card.liveTime)
            put("areaName", card.areaName)
            put("popularity", card.popularity)
            put("autoRecord", card.autoRecord)
            put("quality", card.quality)
            put("danmakuOpen", card.danmakuOpen)
            put("remind", card.remind)
            put("audioOnly", card.audioOnly)
            put("cutSeconds", card.cutSeconds)
            put("cutSizeMB", card.cutSizeMB)
            put("recState", card.recState)
            put("recMode", card.recMode)
            put("recFile", card.recFile)
            put("recSize", card.recSize)
            put("recSpeed", card.recSpeed)
            put("recStartTime", card.recStartTime)
            put("livePopularity", card.livePopularity)
            put("danmakuCount", card.danmakuCount)
            put("lastError", card.lastError)
            put("files", JSONArray(card.files))
        }
    }

    private fun danmakuToJson(item: com.ddtv.app.core.DanmakuItem): JSONObject {
        return JSONObject().apply {
            put("roomId", item.roomId)
            put("type", item.type)
            put("user", item.user)
            put("uid", item.uid)
            put("content", item.content)
            put("time", item.time)
            put("color", item.color)
            put("extra", item.extra)
        }
    }

    // ============ 原生 → JS 推送 ============

    fun pushToJs(payload: String) {
        webView.post {
            try {
                webView.evaluateJavascript(
                    "window.onNativeEvent && window.onNativeEvent($payload)", null
                )
            } catch (e: Exception) {
                Logger.w("Bridge", "JS推送失败: ${e.message}")
            }
        }
    }

    fun pushRoomsChanged() = pushToJs("""{"type":"rooms_changed"}""")
    fun pushRoomUpdate(roomId: Long) = pushToJs("""{"type":"room_update","roomId":$roomId}""")
    fun pushDanmaku(item: com.ddtv.app.core.DanmakuItem) = pushToJs("""{"type":"danmaku","item":${danmakuToJson(item)}}""")
    fun pushDanmakuStatus(roomId: Long, connected: Boolean, msg: String) = pushToJs(
        JSONObject().apply {
            put("type", "danmaku_status")
            put("roomId", roomId)
            put("connected", connected)
            put("msg", msg)
        }.toString())
    fun pushLog(roomId: Long, level: String, msg: String) = pushToJs(
        JSONObject().apply {
            put("type", "log")
            put("roomId", roomId)
            put("level", level)
            put("msg", msg)
        }.toString()
    )
    fun pushAccount() = pushToJs("""{"type":"account_changed"}""")
    fun pushQrcode(imageData: String?, message: String) = pushToJs(
        JSONObject().apply {
            put("type", "qrcode")
            put("image", imageData ?: "")
            put("message", message)
        }.toString()
    )
}
