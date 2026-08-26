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
        // 在线听直播状态变化 → 推 JS 更新按钮/提示
        ListenService.listener = { roomId, p, label ->
            val card = com.ddtv.app.core.RoomManager.getRoom(roomId)
            pushToJs(JSONObject().apply {
                put("type", "listen_status")
                // active 以当前实际收听状态为准（停止/下播后为 false，前端据此隐藏控制器）
                put("active", ListenService.activeRoom() == roomId)
                put("playing", p)
                put("roomId", roomId)
                put("name", card?.name ?: "房间 $roomId")
                put("label", label)
            }.toString())
        }
        applyScreenOn(RoomManager.settings.keepScreenOn)
        startMainWatchdog()
        startJsProbe()
    }

    // ============ JS 心跳探针(前端卡死检测与自愈) ============
    // WebView 的 JS 线程/渲染卡死时主线程可能仍正常(推送照常入队但不执行),
    // 用 evaluateJavascript 探针确认 JS 是否响应:45s 无响应 → reload 自愈
    // (前端 init() 会从 bridge 重拉全量状态;视图/弹幕房间由 localStorage 恢复)
    @Volatile private var lastJsAck = System.currentTimeMillis()
    @Volatile private var jsReloadCount = 0
    @Volatile private var lastJsReload = 0L

    /** App 是否前台可见（MainActivity onStart/onStop 同步）。后台时国产 ROM 会冻结
     *  WebView 的 JS 执行,探针必然无响应——此时不判"无响应"也不 reload(无效且刷屏)。 */
    @Volatile private var appVisible = true

    /** 回到前台：重置探针基准,给 JS 一个 grace 期,避免后台冻结造成的滞后被立即误判 */
    fun onAppVisible() {
        lastJsAck = System.currentTimeMillis()
        appVisible = true
    }

    fun onAppHidden() {
        appVisible = false
    }

    private fun startJsProbe() {
        Thread({
            while (true) {
                try { Thread.sleep(15000) } catch (_: InterruptedException) { break }
                try {
                    webView.post {
                        // 回调执行 = JS 线程确实响应(主线程 post 执行不代表 JS 活着)
                        webView.evaluateJavascript("window.__jsPing=(window.__jsPing||0)+1") {
                            lastJsAck = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) {}
            }
        }, "JsProbe").apply { isDaemon = true; start() }
    }

    private fun checkJsHealth() {
        if (!appVisible) return  // 后台冻结场景:不判无响应、不 reload(见 onAppHidden 注释)
        val now = System.currentTimeMillis()
        val stall = now - lastJsAck
        if (stall <= 45000) return
        // 已 reload 过 2 次仍无响应:停止自动 reload(防循环),仅保持降载
        if (jsReloadCount >= 2 || now - lastJsReload < 90000) {
            if (jsReloadCount >= 2 && now - lastJsReload > 600000) {
                // 10 分钟后再给一次机会(可能已恢复又再次卡死)
                jsReloadCount = 0
            }
            return
        }
        jsReloadCount++
        lastJsReload = now
        Logger.w("Bridge", "前端 JS ${stall / 1000}s 无响应,第 $jsReloadCount 次 reload 自愈")
        try {
            webView.post { webView.reload() }
        } catch (_: Exception) {}
    }

    // ============ 屏幕常亮(设置项) ============

    private fun applyScreenOn(on: Boolean) {
        try {
            mainHandler.post {
                val activity = context as? android.app.Activity ?: return@post
                if (on) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Exception) {}
    }

    // ============ 主线程健康看门狗(ANR 自愈) ============
    // 背景:大直播间弹幕 + 多路录制时,高频 pushToJs(evaluateJavascript)会淹没主线程
    // 消息队列 → 点击无响应 + ANR 弹窗。看门狗每 3s 检测主线程心跳,阻塞超 5s 判定繁忙:
    // ①打日志 ②丢弃积压弹幕(只留最新 100 条) ③弹幕批量窗口 300ms→2s;主线程恢复后自动回缩。
    // 注意:tick 原先只在 pushToJs(业务推送)时刷新,空闲期(无弹幕/无事件)会停更导致
    // 假阳性降载;且降载日志经 Logger.listener→pushLog 又会刷新 tick,形成 30s 自激循环。
    // 修复:看门狗自身每 3s post 一次保底心跳,空闲时也持续刷新 tick;主线程真卡死时
    // 心跳 runnable 排队不执行,tick 停更,stall 照常增长,判定不受影响。
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var mainThreadTick = System.currentTimeMillis()
    @Volatile private var mainBusy = false
    @Volatile private var watchdogLogCooldown = 0L

    private fun startMainWatchdog() {
        Thread({
            while (true) {
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                // 保底心跳:主线程空闲时持续刷新 tick(避免业务推送停更导致的假阳性)
                mainHandler.post { mainThreadTick = System.currentTimeMillis() }
                val now = System.currentTimeMillis()
                val stall = now - mainThreadTick
                mainBusy = stall > 5000
                checkJsHealth()
                if (mainBusy) {
                    // 降载:丢弃积压弹幕,只留最新 100 条
                    var dropped = 0
                    while (danmakuQueue.size > 100) { danmakuQueue.poll(); dropped++ }
                    if (dropped > 0 && now - watchdogLogCooldown > 10000) {
                        watchdogLogCooldown = now
                        Logger.w("Bridge", "主线程阻塞 ${stall}ms,自动降载(丢弃积压弹幕 $dropped 条,批量窗口 2s)")
                    } else if (dropped == 0 && now - watchdogLogCooldown > 30000) {
                        watchdogLogCooldown = now
                        Logger.w("Bridge", "主线程阻塞 ${stall}ms,已降载等待恢复")
                    }
                }
            }
        }, "MainWatchdog").apply { isDaemon = true; start() }
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
        // 正在收听该房间 → 一并停止播放，避免残留
        if (ListenService.activeRoom() == roomId) ListenService.stop(context)
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

    // ============ UP 搜索添加直播间 ============

    /** 按 UP 名搜索直播间，返回 JSON 数组 */
    @JavascriptInterface
    fun searchLiveUsers(keyword: String): String {
        val arr = JSONArray()
        try {
            com.ddtv.app.core.BiliLiveApi.searchLiveUsers(keyword).forEach { u ->
                arr.put(JSONObject().apply {
                    put("roomId", u.roomId)
                    put("uid", u.uid)
                    put("uname", u.uname)
                    put("face", u.face)
                    put("liveStatus", u.liveStatus)
                    put("title", u.title)
                    put("online", u.online)
                    put("shortId", u.shortId)
                })
            }
        } catch (_: Exception) {}
        return arr.toString()
    }

    /** 添加搜索结果房间（名字/头像直接入库，无需 room_init 补全） */
    @JavascriptInterface
    fun addRoomFromSearch(json: String): String {
        return try {
            val o = JSONObject(json)
            val u = com.ddtv.app.core.SearchLiveUser(
                roomId = o.optLong("roomId"),
                uid = o.optLong("uid"),
                uname = o.optString("uname"),
                face = o.optString("face"),
                liveStatus = o.optInt("liveStatus"),
                title = o.optString("title"),
                online = o.optLong("online"),
                shortId = o.optLong("shortId"),
            )
            when (com.ddtv.app.core.RoomManager.addRoomFromSearch(u)) {
                1 -> """{"code":1,"msg":"已添加"}"""
                0 -> """{"code":0,"msg":"房间已存在"}"""
                else -> """{"code":-1,"msg":"该UP主暂无直播间"}"""
            }
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
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

    /** 在线听直播：播放当前房间音频（无画面，可后台/锁屏收听） */
    @JavascriptInterface
    fun startListen(roomId: Long): String {
        val card = RoomManager.getRoom(roomId) ?: return """{"code":-1,"msg":"房间不存在"}"""
        if (card.liveStatus != 1 && card.liveStatus != 2) return """{"code":0,"msg":"房间未开播，无法收听"}"""
        ListenService.start(context, roomId)
        return """{"code":1,"msg":"正在连接直播…"}"""
    }

    @JavascriptInterface
    fun stopListen(): String {
        ListenService.stop(context)
        return """{"code":1,"msg":"已停止收听"}"""
    }

    @JavascriptInterface
    fun getListenStatus(): String {
        val roomId = ListenService.activeRoom()
        if (roomId == 0L) return """{"active":false}"""
        val card = RoomManager.getRoom(roomId)
        return JSONObject().apply {
            put("active", true)
            put("playing", ListenService.playing)
            put("roomId", roomId)
            put("name", card?.name ?: "房间 $roomId")
            put("title", card?.title ?: "")
        }.toString()
    }

    /** 切换当前收听的播放/暂停（通知栏/前端控制器用） */
    @JavascriptInterface
    fun toggleListenPlay() {
        ListenService.toggle(context)
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
        val (ok, reason) = RoomManager.sendDanmaku(roomId, text)
        return if (ok) """{"code":1,"msg":"已发送"}"""
        else """{"code":-1,"msg":${JSONObject.quote(reason)}}"""
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
            put("keepScreenOn", s.keepScreenOn)
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
                keepScreenOn = o.optBoolean("keepScreenOn", keepScreenOn)
                updateRepo = o.optString("updateRepo", updateRepo)
                autoUpdate = o.optBoolean("autoUpdate", autoUpdate)
            }
            RoomManager.saveSettings()
            com.ddtv.app.core.LiveRecorder.applySettings(RoomManager.settings)
            applyScreenOn(RoomManager.settings.keepScreenOn)
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
        return """{"code":1,"msg":"${if (on) "调试服务器已开启: " + com.ddtv.app.core.DebugServer.accessUrl() else "调试服务器已关闭"}"}"""
    }

    // ============ 自动更新（GitHub Releases，参照原版 ProgramUpdates） ============

    private val currentVersion: String = "0.7.22"

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

    /** 检查 GitHub Releases 最新版，结果异步推给 JS（update_result 事件；silent=true 时 JS 不弹“已是最新/失败”提示，BBDown 同款） */
    @JavascriptInterface
    fun checkUpdate(repo: String, silent: Boolean) {
        val ownerRepo = repo.trim().removePrefix("https://github.com/").removeSuffix("/")
        Thread({
            try {
                val url = "https://api.github.com/repos/$ownerRepo/releases/latest"
                val body = Http.get(url)
                val o = JSONObject(body)
                val tag = o.optString("tag_name", "")
                if (tag.isEmpty()) {
                    pushToJs("""{"type":"update_result","ok":false,"silent":$silent,"msg":"仓库不存在或无 Release"}""")
                    return@Thread
                }
                val latest = tag.removePrefix("v")
                val hasUpdate = versionCompare(latest, currentVersion) > 0
                pushToJs(JSONObject().apply {
                    put("type", "update_result")
                    put("ok", true)
                    put("silent", silent)
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
                    put("silent", silent)
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
                                    if (f.isFile && (f.name.endsWith(".flv") || f.name.endsWith(".mp4") || f.name.endsWith(".m4a"))) {
                                        val isAudio = f.name.endsWith(".m4a")
                                        arr.put(JSONObject().apply {
                                            put("name", f.name)
                                            put("path", f.absolutePath)
                                            put("size", f.length())
                                            put("mtime", f.lastModified())
                                            put("uploader", liver.name)
                                            put("isFlv", f.name.endsWith(".flv"))
                                            put("isAudio", isAudio)
                                            // 封面优先级：同目录 _cover.jpg（视频录制时保存）→ m4a 元数据嵌入封面（提取后缓存）
                                            val cover = java.io.File(f.absolutePath.substringBeforeLast('.') + "_cover.jpg")
                                            val coverUri = if (cover.exists()) {
                                                try {
                                                    androidx.core.content.FileProvider.getUriForFile(
                                                        context, "com.ddtv.app.fileprovider", cover).toString()
                                                } catch (_: Exception) { null }
                                            } else null
                                            if (coverUri != null) put("coverPath", coverUri)
                                            else if (isAudio) audioCoverUri(f)?.let { put("coverPath", it) }
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

    /** m4a 元数据嵌入封面：MediaMetadataRetriever 提取 → 缓存文件（key=路径|mtime，0 字节=无封面负缓存不重试） */
    private fun audioCoverUri(f: java.io.File): String? {
        return try {
            val key = "audio|${f.absolutePath}|${f.lastModified()}"
            val cf = com.ddtv.app.core.CoverCache.cacheFile(key) ?: return null
            if (cf.exists()) {
                return if (cf.length() > 0) com.ddtv.app.core.CoverCache.uriFor(context, cf) else null
            }
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                val pic = mmr.embeddedPicture
                if (pic != null && pic.isNotEmpty()) {
                    com.ddtv.app.core.CoverCache.writeCache(key, pic)
                        ?.let { return com.ddtv.app.core.CoverCache.uriFor(context, it) }
                }
                com.ddtv.app.core.CoverCache.writeCache(key, ByteArray(0))
                null
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }

    /** 网络封面 → 本地缓存 URI（未缓存则返回原 URL 并后台拉取，下轮轮询自动换本地） */
    private fun coverImage(url: String): String {
        if (url.isBlank() || !url.startsWith("http")) return url
        val f = com.ddtv.app.core.CoverCache.cachedFile(url)
        if (f != null) return com.ddtv.app.core.CoverCache.uriFor(context, f) ?: url
        com.ddtv.app.core.CoverCache.cacheAsync(url) { done ->
            if (done != null) com.ddtv.app.core.Logger.i("Cache", "封面已缓存: ${url.take(80)}")
        }
        return url
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

    /** 批量删除录制历史（indices 为 JSON 数组；倒序删避免索引错位） */
    @JavascriptInterface
    fun deleteHistories(indices: String): String {
        return try {
            val arr = JSONArray(indices)
            val idxs = (0 until arr.length()).map { arr.getInt(it) }.sortedDescending()
            if (idxs.isEmpty()) return """{"code":-1,"msg":"未选择记录"}"""
            idxs.forEach { RoomManager.deleteHistory(it) }
            """{"code":1,"msg":"已删除 ${idxs.size} 条"}"""
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }
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
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            act, android.Manifest.permission.POST_NOTIFICATIONS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) return@runOnUiThread  // 已授权：JS 侧已先查状态并提示
                        val prefs = act.getSharedPreferences("ddtv_settings", android.content.Context.MODE_PRIVATE)
                        val asked = prefs.getBoolean("notif_asked", false)
                        if (asked && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                act, android.Manifest.permission.POST_NOTIFICATIONS)) {
                            // 拒绝过且系统不再弹窗（don't ask again）→ 引导去系统通知设置页
                            try {
                                act.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, act.packageName))
                            } catch (_: Exception) {}
                        } else {
                            prefs.edit().putBoolean("notif_asked", true).apply()
                            androidx.core.app.ActivityCompat.requestPermissions(
                                act, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1006)
                        }
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
            put("face", coverImage(card.face))
            put("sign", card.sign)
            put("title", card.title)
            put("cover", coverImage(card.cover))
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
            mainThreadTick = System.currentTimeMillis()
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

    // ============ 弹幕批量推送 ============
    // 大直播间弹幕风暴下逐条 pushToJs 会让主线程被 evaluateJavascript 淹没
    // (点击无响应 + ANR);按 300ms 窗口合并成数组一次推送。
    private val danmakuQueue = java.util.concurrent.ConcurrentLinkedQueue<com.ddtv.app.core.DanmakuItem>()
    private val danmakuFlushLock = Any()
    private var danmakuFlushScheduled = false

    fun pushDanmaku(item: com.ddtv.app.core.DanmakuItem) {
        danmakuQueue.add(item)
        val shouldSchedule = synchronized(danmakuFlushLock) {
            if (danmakuFlushScheduled) false else { danmakuFlushScheduled = true; true }
        }
        // 主线程繁忙时拉大批量窗口(2s),恢复后回缩 300ms
        val window = if (mainBusy) 2000L else 300L
        if (shouldSchedule) webView.postDelayed({ flushDanmaku() }, window)
    }

    private fun flushDanmaku() {
        synchronized(danmakuFlushLock) { danmakuFlushScheduled = false }
        if (danmakuQueue.isEmpty()) return
        val batch = ArrayList<com.ddtv.app.core.DanmakuItem>(danmakuQueue.size)
        while (true) { danmakuQueue.poll()?.let { batch.add(it) } ?: break }
        // 单批上限 500(与弹幕面板 DOM 上限一致),超出丢弃最旧,避免超大 JSON 卡主线程
        if (batch.size > 500) {
            val drop = batch.size - 500
            repeat(drop) { batch.removeAt(0) }
            Logger.d("Bridge", "弹幕单批超限,丢弃最旧 $drop 条")
        }
        val arr = JSONArray()
        batch.forEach { arr.put(danmakuToJson(it)) }
        pushToJs("""{"type":"danmaku","items":$arr}""")
    }

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
