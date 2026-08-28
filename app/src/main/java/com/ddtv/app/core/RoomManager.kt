package com.ddtv.app.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 房间管理器：房间 CRUD + 批量状态轮询（移植 DDTV DetectRoom.cs / room.cs get_status_info_by_uids）
 * 特性：
 *  - 按 UID 批量查询（get_status_info_by_uids），一次请求更新所有房间
 *  - 重入保护：上一轮未结束则跳过本轮（防网络卡顿雪崩）
 *  - 启动后首轮：已开播且自动录制的房间强制触发录制（IsFirst 兜底）
 *  - 开播 → 自动录制 + 弹幕 + 小心心；下播 → 停止录制
 */
object RoomManager {

    interface Listener {
        fun onRoomsChanged()
        fun onRoomUpdate(roomId: Long)
        fun onLiveStart(roomId: Long)
        fun onLiveEnd(roomId: Long)
        fun onDanmakuEvent(item: DanmakuItem)
        fun onDanmakuStatus(roomId: Long, connected: Boolean, msg: String)
        fun onLog(roomId: Long, level: String, msg: String)
        fun onHeartbeatLog(msg: String)
    }

    @Volatile var listener: Listener? = null
        set(value) {
            synchronized(listeners) {
                field?.let { listeners.remove(it) }
                field = value
                value?.let { listeners.add(it) }
            }
        }

    private val listeners = mutableListOf<Listener>()

    /** 多监听器注册（MainActivity 推 UI、LiveService 推通知，互不覆盖） */
    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyRoomsChanged() = synchronized(listeners) { listeners.toList() }.forEach { it.onRoomsChanged() }
    private fun notifyRoomUpdate(roomId: Long) = synchronized(listeners) { listeners.toList() }.forEach { it.onRoomUpdate(roomId) }
    private fun notifyLiveStart(roomId: Long) = synchronized(listeners) { listeners.toList() }.forEach { it.onLiveStart(roomId) }
    private fun notifyLiveEnd(roomId: Long) = synchronized(listeners) { listeners.toList() }.forEach { it.onLiveEnd(roomId) }
    private fun notifyDanmaku(item: DanmakuItem) = synchronized(listeners) { listeners.toList() }.forEach { it.onDanmakuEvent(item) }
    private fun notifyDanmakuStatus(roomId: Long, connected: Boolean, msg: String) = synchronized(listeners) { listeners.toList() }.forEach { it.onDanmakuStatus(roomId, connected, msg) }
    private fun notifyLog(roomId: Long, level: String, msg: String) = synchronized(listeners) { listeners.toList() }.forEach { it.onLog(roomId, level, msg) }
    private fun notifyHeartbeat(msg: String) = synchronized(listeners) { listeners.toList() }.forEach { it.onHeartbeatLog(msg) }

    private val rooms = ConcurrentHashMap<Long, RoomCard>()
    private val danmakuClients = ConcurrentHashMap<Long, DanmakuClient>()
    private val lock = Any()

    /** 录制历史（对应原版 Detect.histories，内存 + prefs 持久化，最多保留 100 条） */
    private val historyItems = mutableListOf<HistoryItem>()

    @Volatile var settings = AppSettings()

    /** 应用级 context（FileProvider URI 生成等） */
    @Volatile private var appContext: Context? = null

    @Volatile private var pollThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val pollingNow = AtomicBoolean(false)
    @Volatile private var isFirstRound = true

    private lateinit var prefs: SharedPreferences

    /** 应用私有录制目录 */
    @Volatile var outputDir: java.io.File = java.io.File("")

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ddtv_settings", Context.MODE_PRIVATE)
        appContext = context.applicationContext
        // 优先恢复用户选择的录制目录，无效则回退应用私有目录
        val saved = prefs.getString("output_dir", "") ?: ""
        outputDir = if (saved.isNotEmpty()) java.io.File(saved).takeIf { it.isDirectory || it.mkdirs() }
            ?: java.io.File(context.getExternalFilesDir(null), "DDTV").apply { mkdirs() }
        else java.io.File(context.getExternalFilesDir(null), "DDTV").apply { mkdirs() }
        loadSettings()
        loadRooms()
        // 启动清理：迁移上次会话遗留的占位目录（名字已持久化的房间），避免 Room<id> 目录堆积
        try { rooms.values.forEach { migratePlaceholderFolder(it) } } catch (_: Exception) {}
        loadHistories()
        LiveRecorder.outputRoot = outputDir
        LiveRecorder.applySettings(settings)
    }

    /**
     * 切换录制目录（设置页文字输入后回调）。
     * @return 错误信息（空 = 成功）
     */
    fun setOutputDir(path: String): String? {
        val dir = java.io.File(path)
        if (!dir.exists() && !dir.mkdirs()) {
            // 区分权限问题：Android 11+ 无「所有文件访问」授权时公共目录创建必然失败
            val hasManage = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager()
            return if (!hasManage)
                "无写入权限：Android 11+ 需先授权「所有文件访问」才能创建该目录"
            else "目录创建失败: $path"
        }
        if (!dir.isDirectory) return "不是目录: $path"
        if (!dir.canWrite()) return "目录不可写: $path"
        outputDir = dir
        LiveRecorder.outputRoot = dir
        prefs.edit().putString("output_dir", dir.absolutePath).apply()
        Logger.i("Room", "录制目录已切换: ${dir.absolutePath}")
        return null
    }

    // ============ 房间 CRUD ============

    fun getRooms(): List<RoomCard> = rooms.values.sortedBy { it.roomId }

    fun getRoom(roomId: Long): RoomCard? = rooms[roomId]

    fun getLiveStatus(roomId: Long): Int = rooms[roomId]?.liveStatus ?: 0

    /**
     * 添加房间。支持：纯数字房间号 / 短号 / UID
     * @return 1成功 0重复 -1解析失败
     */
    fun addRoom(input: String): Int {
        val id = input.trim()
        if (!id.all { it.isDigit() }) return -1
        val num = id.toLong()
        synchronized(lock) {
            rooms.values.find { it.roomId == num || (it.shortId != 0L && it.shortId == num) }?.let {
                return 0
            }
            val info = BiliLiveApi.roomInit(num)
            if (info == null) {
                // 房间号解析失败 → 尝试按 UID 解析
                val roomIdByUid = BiliLiveApi.getRoomIdByUid(num)
                if (roomIdByUid == null) return -1
                val info2 = BiliLiveApi.roomInit(roomIdByUid) ?: return -1
                return addCardFromRoomInit(info2, num)
            }
            return addCardFromRoomInit(info, num)
        }
    }

    private fun addCardFromRoomInit(info: JSONObject, inputNum: Long): Int {
        val realRoomId = Json.objLong(info, "room_id", inputNum)
        if (realRoomId == 0L) return -1
        if (rooms.containsKey(realRoomId)) return 0
        val card = RoomCard(
            roomId = realRoomId,
            shortId = Json.objLong(info, "short_id", 0),
            uid = Json.objLong(info, "uid", 0),
            liveStatus = Json.objInt(info, "live_status", 0),
            name = "房间 $realRoomId",
            autoRecord = settings.autoRecordDefault,
            quality = settings.defaultQuality,
        )
        rooms[realRoomId] = card
        saveRooms()
        notifyRoomsChanged()
        // 同步补全主播名/标题（room_init 不含名字；不取的话录制目录会先用“房间 X”占位）
        // 失败也不阻塞添加：名字会在轮询/手动刷新时补上，占位目录随后自动迁移
        try {
            val detail = BiliLiveApi.getRoomDetail(realRoomId)
            if (detail != null) {
                // 名字绝不允许空（空名会导致录制目录变 Room<id>）：detail 拿不到就用占位，轮询补全后自动迁移
                card.name = detail.uploader.ifEmpty { "房间 $realRoomId" }
                card.title = detail.title
                card.cover = detail.cover
                card.areaName = detail.area
                card.areaId = detail.areaId
                card.popularity = detail.online
            }
        } catch (_: Exception) {}
        // 添加时已在直播 → 立即启动弹幕/录制/心跳，不等下一轮轮询（最长 pollInterval 秒）
        // 添加时已真实开播(status=1) → 立即启动弹幕/录制/心跳，不等下一轮轮询；
        // 轮播(2)不录制不连弹幕，等真实开播
        // 并把 liveStatusPrev 置为已开播，避免首轮轮询重复触发“开播了”事件
        if (card.liveStatus == 1) {
            card.liveStatusPrev = true
            if (card.danmakuOpen) ensureDanmaku(card)
            if (settings.watchHeartbeat) WatchHeartbeat.register(card.roomId, card.uid)
            if (card.autoRecord) ensureRecording(card)
        }
        return 1
    }

    /**
     * 从搜索结果直接建卡（名字/头像/标题已知，无需 room_init 补全，也不会有“房间 xxx”占位期）
     */
    fun addRoomFromSearch(u: SearchLiveUser): Int {
        if (u.roomId <= 0) return -1
        synchronized(lock) {
            if (rooms.containsKey(u.roomId)) return 0
            val card = RoomCard(
                roomId = u.roomId,
                shortId = u.shortId,
                uid = u.uid,
                name = u.uname.ifEmpty { "房间 ${u.roomId}" },
                face = u.face,
                title = u.title,
                liveStatus = u.liveStatus,
                popularity = u.online,
                autoRecord = settings.autoRecordDefault,
                quality = settings.defaultQuality,
            )
            rooms[u.roomId] = card
            saveRooms()
            notifyRoomsChanged()
            // 添加时已真实开播(status=1) → 立即启动弹幕/录制/心跳，不等下一轮轮询；轮播(2)不录制
            if (card.liveStatus == 1) {
                card.liveStatusPrev = true
                if (card.danmakuOpen) ensureDanmaku(card)
                if (settings.watchHeartbeat) WatchHeartbeat.register(card.roomId, card.uid)
                if (card.autoRecord) ensureRecording(card)
            }
            return 1
        }
    }

    /** 批量添加（关注导入用） */
    fun addRoomsBatch(uidList: List<Long>): Int {
        // 并行解析 uid→房间信息（网络请求不持锁）：勾选多个在播 UP 时逐个 roomInit 会串行等待
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, maxOf(uidList.size, 1)))
        val resolved = java.util.concurrent.ConcurrentLinkedQueue<Triple<Long, Long, JSONObject>>()
        uidList.forEach { uid ->
            if (uid <= 0) return@forEach
            pool.execute {
                try {
                    val roomId = BiliLiveApi.getRoomIdByUid(uid) ?: return@execute
                    val info = BiliLiveApi.roomInit(roomId) ?: return@execute
                    resolved.add(Triple(uid, roomId, info))
                } catch (_: Exception) {}
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        // 串行落地卡片（保持 uid 去重）
        var added = 0
        resolved.forEach { (uid, roomId, info) ->
            synchronized(lock) {
                if (rooms.values.any { it.uid == uid }) return@forEach
                if (addCardFromRoomInit(info, roomId) == 1) added++
            }
        }
        if (added > 0) {
            saveRooms()
            notifyRoomsChanged()
        }
        return added
    }

    /**
     * 批量添加（关注导入用，带房间号版本）。
     * 直播中的关注在拉取时已带 roomId（jump_url 提取），直接使用避免再调
     * x/relation/stat 转换（该接口在风控下易失败，导致"正在开播导入失败"）。
     * roomId 为 0 的仍走 uid 转换兜底。
     * 并行解析（网络请求不持锁），完成后串行落地卡片。
     */
    fun addRoomsBatchWithRoomIds(items: List<Pair<Long, Long>>): Int {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, maxOf(items.size, 1)))
        val resolved = java.util.concurrent.ConcurrentLinkedQueue<Triple<Long, Long, JSONObject>>()
        items.forEach { (uid, roomId) ->
            if (uid <= 0) return@forEach
            pool.execute {
                try {
                    val rid = if (roomId > 0) roomId else (BiliLiveApi.getRoomIdByUid(uid) ?: return@execute)
                    val info = BiliLiveApi.roomInit(rid) ?: return@execute
                    resolved.add(Triple(uid, rid, info))
                } catch (_: Exception) {}
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        var added = 0
        resolved.forEach { (uid, rid, info) ->
            synchronized(lock) {
                if (rooms.values.any { it.uid == uid }) return@forEach
                if (addCardFromRoomInit(info, rid) == 1) added++
            }
        }
        if (added > 0) {
            saveRooms()
            notifyRoomsChanged()
        }
        return added
    }

    /** 批量刷新房间详细信息（标题/主播名/封面等），并更新直播状态（单个房间手动刷新用） */
    fun refreshRoomInfo(roomId: Long) {
        try {
            val card = rooms[roomId] ?: return
            val info = BiliLiveApi.roomInit(roomId) ?: return
            applyRoomInit(card, info)
            // 拉详情（标题/封面/主播名）
            val nameBefore = card.name
            try {
                val detail = BiliLiveApi.getRoomDetail(roomId)
                if (detail != null) {
                    card.title = detail.title
                    card.cover = detail.cover
                    card.name = detail.uploader.ifEmpty { card.name.ifEmpty { "房间 ${card.roomId}" } }
                    card.areaName = detail.area
                    card.areaId = detail.areaId
                    card.popularity = detail.online
                }
            } catch (_: Exception) {}
            if (card.name != nameBefore) saveRooms()  // 轮询/刷新补上的名字要持久化，否则重启又回占位
            migratePlaceholderFolder(card)
            notifyRoomUpdate(roomId)
        } catch (e: Exception) {
            Logger.w("Room", "刷新房间 $roomId 失败: ${e.message}")
        }
    }

    /** 将 room_init 数据应用到卡片（不触发事件） */
    private fun applyRoomInit(card: RoomCard, info: JSONObject) {
        card.liveStatus = Json.objInt(info, "live_status", card.liveStatus)
        card.liveTime = Json.objLong(info, "live_time", card.liveTime)
        card.uid = Json.objLong(info, "uid", card.uid)
        card.shortId = Json.objLong(info, "short_id", card.shortId)
    }

    /** 将 get_status_info_by_uids 的数据应用到卡片 */
    private fun applyStatusInfo(card: RoomCard, data: JSONObject) {
        val nameBefore = card.name
        card.liveStatus = Json.objInt(data, "live_status", card.liveStatus)
        card.liveTime = Json.objLong(data, "live_time", card.liveTime)
        card.title = Json.obj(data, "title", card.title)
        card.uid = Json.objLong(data, "uid", card.uid)
        card.shortId = Json.objLong(data, "short_id", card.shortId)
        card.popularity = Json.objLong(data, "online", card.popularity)
        card.areaName = Json.obj(data, "area_v2_name", card.areaName)
        card.name = Json.obj(data, "uname", "").ifEmpty { card.name }
        card.face = Json.obj(data, "face", card.face)
        card.cover = Json.obj(data, "cover_from_user", card.cover).ifEmpty { Json.obj(data, "keyframe", card.cover) }
        if (card.name != nameBefore) saveRooms()  // 轮询补上的主播名必须持久化，否则重启又回占位目录
        migratePlaceholderFolder(card)
    }

    /** 把“房间 <id>/Room<id>”占位目录迁移为真实主播名目录；目标已存在时合并内容
     *  force=true：由录制器在段边界调用（上一个分片已关闭，无打开句柄，可安全迁移），绕过录制中保护
     *
     *  占位名（空/"房间 X"/"RoomX"）时无法迁移（没有真名可迁），只把历史遗留的
     *  “房间 <id>”目录归一化为 Room<id> 统一形态，等真名刷出后的下一次调用再迁移。
     *  目录命名（RoomCard.dirName()）保证新录制目录只可能是真名或 Room<id>。 */
    fun migratePlaceholderFolder(card: RoomCard, force: Boolean = false) {
        val root = outputDir
        if (!root.isDirectory) return
        if (!force && LiveRecorder.isRecording(card.roomId)) return
        val name = card.name.trim()
        if (name.isEmpty() || name.startsWith("房间 ") || name.startsWith("Room")) {
            val ph = java.io.File(root, "房间 ${card.roomId}")
            val roomDir = java.io.File(root, "Room${card.roomId}")
            if (ph.isDirectory && !roomDir.exists() && ph.renameTo(roomDir)) {
                Logger.i("Room", "占位目录归一化: ${ph.name} → ${roomDir.name}")
            }
            return
        }
        val target = java.io.File(root, sanitizeFileName(name))
        listOf("房间 ${card.roomId}", "Room${card.roomId}").forEach { old ->
            val oldDir = java.io.File(root, old)
            if (!oldDir.isDirectory) return@forEach
            try {
                if (!target.exists()) {
                    if (oldDir.renameTo(target)) {
                        Logger.i("Room", "录制目录已改名: $old → ${target.name}")
                    }
                } else {
                    // 目标已存在（名字就绪后录过新段）：把占位目录内容合并进目标，同名文件保留目标
                    // 合并后清掉移空的子目录，否则旧目录永远非空、空壳残留（历史 bug：日期目录同名时子文件
                    // 移走但空目录不删，Room<id> 一直留着）
                    oldDir.listFiles()?.forEach { child ->
                        val dest = java.io.File(target, child.name)
                        if (child.isDirectory) {
                            if (!dest.exists()) {
                                child.renameTo(dest)
                            } else {
                                child.listFiles()?.forEach { f ->
                                    val d2 = java.io.File(dest, f.name)
                                    if (!d2.exists()) f.renameTo(d2)
                                }
                                if (child.listFiles()?.isEmpty() == true && child.delete()) {
                                    Logger.d("Room", "合并后清理空子目录: $old/${child.name}")
                                }
                            }
                        } else {
                            if (!dest.exists()) child.renameTo(dest)
                        }
                    }
                    if (oldDir.listFiles()?.isEmpty() == true && oldDir.delete()) {
                        Logger.i("Room", "录制目录已合并: $old → ${target.name}")
                    } else {
                        Logger.w("Room", "占位目录合并未清空，留待下次迁移: $old → ${target.name}")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun removeRoom(roomId: Long) {
        synchronized(lock) {
            rooms.remove(roomId)?.let { card ->
                stopRecording(card)
                stopDanmaku(roomId)
                WatchHeartbeat.unregister(roomId)
                saveDanmakuFile(card)
            }
            danmakuBuffer.remove(roomId)  // 清理弹幕缓冲，释放内存
            saveRooms()
            notifyRoomsChanged()
        }
    }

    fun updateRoom(roomId: Long, block: (RoomCard) -> Unit) {
        rooms[roomId]?.let {
            block(it)
            saveRooms()
            notifyRoomUpdate(roomId)
        }
    }

    // ============ 轮询检测（移植 DetectRoom.RoomLoopDetection） ============

    fun startPolling() {
        if (running.getAndSet(true)) return
        pollThread = Thread({
            Logger.i("Room", "轮询检测启动，间隔 ${settings.pollInterval}s，首轮强制触发已开播房间")
            while (running.get()) {
                // 重入保护：上一轮未结束则跳过本轮
                if (pollingNow.compareAndSet(false, true)) {
                    try {
                        pollOnce()
                    } catch (e: Exception) {
                        Logger.w("Room", "轮询异常: ${e.message}")
                    } finally {
                        pollingNow.set(false)
                        isFirstRound = false
                    }
                }
                try { Thread.sleep(settings.pollInterval * 1000L) } catch (_: InterruptedException) { break }
            }
        }, "RoomPoll").also { it.start() }
    }

    fun stopPolling() {
        running.set(false)
        pollThread?.interrupt()
        pollThread = null
    }

    private fun pollOnce() {
        val list = getRooms()
        if (list.isEmpty()) return
        // 批量查询（按 UID）
        val statusMap = BiliLiveApi.getStatusInfoByUids(list.map { it.uid })
        list.forEach { card ->
            val data = statusMap[card.uid]
            if (data != null) {
                applyStatusInfo(card, data)
                checkLiveTransition(card)
            } else {
                // UID 查询失败（如未登录或接口异常）→ 单房间 room_init 兜底
                try {
                    val info = BiliLiveApi.roomInit(card.roomId)
                    if (info != null) {
                        applyRoomInit(card, info)
                        checkLiveTransition(card)
                    }
                } catch (_: Exception) {}
            }
            notifyRoomUpdate(card.roomId)
            // 安全网：真实直播(status 1)未断但录制意外停止（线程异常、文件被外部删除等）时自动重启；手动停止的房间不打扰。
            // 轮播(2)不自动重启：无流轮播房会无限"获取流失败"循环（有流的轮播由开播事件/状态切换拉起）
            if (card.liveStatus == 1 && card.autoRecord && !LiveRecorder.isRecording(card.roomId) && !card.manualStop) {
                ensureRecording(card)
            }
        }
    }

    /** 开播/下播事件判定（含首轮强制触发）；轮播(2)不算直播：不录制/不连弹幕/不上报心跳 */
    private fun checkLiveTransition(card: RoomCard) {
        val isLive = card.liveStatus == 1
        val wasLive = card.liveStatusPrev
        if (isLive && !wasLive) {
            // 开播（日志经 notifyLog 打一条;不再单独 Logger.i 避免前端/DebugServer 双打）
            card.manualStop = false  // 新一轮直播：清除手动停止标记，恢复自动录制
            notifyLog(card.roomId, "info", "检测到开播: ${card.title}")
            // 首轮：已开播且自动录制 → 强制触发（原版 IsFirst 兜底）
            if (isFirstRound && !card.autoRecord) {
                notifyLiveStart(card.roomId)
            } else if (!isFirstRound) {
                notifyLiveStart(card.roomId)
            }
            card.liveStatusPrev = true
            if (card.autoRecord) ensureRecording(card)
            if (card.danmakuOpen) ensureDanmaku(card)
            if (settings.watchHeartbeat) WatchHeartbeat.register(card.roomId, card.uid)
        } else if (!isLive && wasLive) {
            // 下播（日志经 notifyLog 打一条"直播结束";不再单独 Logger.i 避免前端/DebugServer 双打）
            notifyLog(card.roomId, "info", "直播结束")
            card.liveStatusPrev = false
            stopRecording(card)
            saveDanmakuFile(card)
            notifyLiveEnd(card.roomId)
        }
    }

    /** 记录录制历史（由 LiveRecorder 结束事件统一调用，避免与下播事件重复） */
    fun recordHistory(card: RoomCard, files: List<String>) {
        if (files.isEmpty()) return
        addHistory(card, files)
        migratePlaceholderFolder(card)  // 录制刚结束：立刻迁移占位目录，不等下一轮轮询
        if (card.audioOnly) {
            // 仅音频录制结束：立即补提取残留的未提取音频（中断/被杀场景），不等下次启动
            LiveRecorder.extractPendingAudioFiles()
        }
    }

    // ============ 录制历史（对应原版 Detect.histories + HistoryPage） ============

    fun getHistories(): List<HistoryItem> = synchronized(historyItems) { historyItems.toList() }

    /** 删除一条录制历史（index 对应 getHistories 返回顺序） */
    fun deleteHistory(index: Int): Boolean {
        synchronized(historyItems) {
            if (index < 0 || index >= historyItems.size) return false
            historyItems.removeAt(index)
        }
        saveHistories()
        notifyRoomsChanged()
        return true
    }

    /** 历史封面缓存：name -> (缓存时间, content://URI)，60s 过期；空串=无封面也缓存，避免反复扫盘 */
    private val historyCoverCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** 某主播录制目录下最新一张 _cover.jpg 的 content:// URI（无则 null） */
    fun latestCoverFor(name: String): String? {
        val now = System.currentTimeMillis()
        historyCoverCache[name]?.let { if (now - it.first < 60000) return it.second.ifEmpty { null } }
        val uri = try {
            val ctx = appContext
            // 历史条目可能存的是占位名（"房间 <id>"），此时实际目录是 Room<id>，从占位名解析 id 兜底
            val dirName = name.trim().let { n ->
                if (n.startsWith("房间 ") || n.startsWith("Room")) {
                    n.removePrefix("房间 ").removePrefix("Room").toLongOrNull()?.let { "Room$it" } ?: n
                } else n
            }
            val dir = java.io.File(outputDir, sanitizeFileName(dirName))
            if (ctx == null || !dir.isDirectory) null
            else {
                var best: java.io.File? = null
                dir.listFiles()?.forEach { day ->
                    if (day.isDirectory) {
                        day.listFiles()?.forEach { f ->
                            if (f.isFile && f.name.endsWith("_cover.jpg") &&
                                (best == null || f.lastModified() > best!!.lastModified())) best = f
                        }
                    }
                }
                best?.let {
                    androidx.core.content.FileProvider.getUriForFile(
                        ctx, "com.ddtv.app.fileprovider", it).toString()
                }
            }
        } catch (_: Exception) { null }
        historyCoverCache[name] = now to (uri ?: "")
        return uri
    }

    private fun addHistory(card: RoomCard, files: List<String>) {
        synchronized(historyItems) {
            historyItems.add(0, HistoryItem(
                name = card.name,
                time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date()),
                title = card.title,
                roomId = card.roomId,
                fileCount = files.size,
            ))
            while (historyItems.size > 100) historyItems.removeAt(historyItems.size - 1)
        }
        saveHistories()
        notifyRoomsChanged()
    }

    private fun saveHistories() {
        try {
            val arr = JSONArray()
            synchronized(historyItems) {
                historyItems.forEach { h ->
                    arr.put(JSONObject().apply {
                        put("name", h.name)
                        put("time", h.time)
                        put("title", h.title)
                        put("roomId", h.roomId)
                        put("fileCount", h.fileCount)
                    })
                }
            }
            prefs.edit().putString("histories", arr.toString()).apply()
        } catch (e: Exception) {
            Logger.w("Room", "保存历史失败: ${e.message}")
        }
    }

    private fun loadHistories() {
        try {
            val raw = prefs.getString("histories", "[]") ?: "[]"
            val arr = JSONArray(raw)
            synchronized(historyItems) {
                historyItems.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    historyItems.add(HistoryItem(
                        name = Json.obj(o, "name"),
                        time = Json.obj(o, "time"),
                        title = Json.obj(o, "title"),
                        roomId = Json.objLong(o, "roomId"),
                        fileCount = Json.objInt(o, "fileCount"),
                    ))
                }
            }
            Logger.i("Room", "加载录制历史 ${historyItems.size} 条")
        } catch (e: Exception) {
            Logger.w("Room", "加载历史失败: ${e.message}")
        }
    }

    // ============ 数据统计（对应原版 DefaultPage/DataPage 统计） ============

    /**
     * 统计数据：监控/直播/录制中房间数、今日录制文件数/大小、存储占用
     */
    fun getStats(): JSONObject {
        val all = getRooms()
        val live = all.count { it.liveStatus == 1 || it.liveStatus == 2 }
        val recording = all.count { it.recState == "recording" }
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())
        var todayFiles = 0
        var todayBytes = 0L
        var totalBytes = 0L
        try {
            val root = outputDir
            if (root.exists()) {
                root.listFiles()?.forEach { liver ->
                    if (liver.isDirectory) {
                        liver.listFiles()?.forEach { day ->
                            if (day.isDirectory) {
                                day.listFiles()?.forEach { f ->
                                    if (f.isFile && (f.name.endsWith(".flv") || f.name.endsWith(".mp4"))) {
                                        totalBytes += f.length()
                                        if (day.name == today) {
                                            todayFiles++
                                            todayBytes += f.length()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return JSONObject().apply {
            put("monitoring", all.size)
            put("live", live)
            put("recording", recording)
            put("historyCount", getHistories().size)
            put("todayFiles", todayFiles)
            put("todayBytes", todayBytes)
            put("totalBytes", totalBytes)
        }
    }

    // ============ 录制调度 ============

    fun ensureRecording(card: RoomCard) {
        if (LiveRecorder.isRecording(card.roomId)) return
        // recState 由录制线程置位：start 失败时不会出现“显示录制中但实际没录”的假状态
        LiveRecorder.start(card)
    }

    fun stopRecording(card: RoomCard) {
        if (LiveRecorder.isRecording(card.roomId)) {
            card.recState = "stopping"
            LiveRecorder.stop(card.roomId)
        }
    }

    // ============ 弹幕调度 ============

    fun ensureDanmaku(card: RoomCard) {
        if (danmakuClients.containsKey(card.roomId)) return
        val client = DanmakuClient(card)
        client.listener = object : DanmakuClient.Listener {
            override fun onDanmaku(item: DanmakuItem) {
                RoomManager.onDanmaku(item)
            }
            override fun onStatus(roomId: Long, connected: Boolean, msg: String) {
                RoomManager.onDanmakuStatus(roomId, connected, msg)
            }
        }
        danmakuClients[card.roomId] = client
        client.start()
    }

    fun stopDanmaku(roomId: Long) {
        danmakuClients.remove(roomId)?.stop()
    }

    /** 手动重连弹幕（风控停止自动重试后由 UI 触发） */
    fun retryDanmaku(roomId: Long) {
        val client = danmakuClients[roomId] ?: run {
            val card = rooms[roomId] ?: return
            ensureDanmaku(card)
            return
        }
        client.retry()
    }

    fun stopAllDanmaku() {
        danmakuClients.values.forEach { it.stop() }
        danmakuClients.clear()
    }

    /** 发送弹幕（需登录），返回 (是否成功, 原因) */
    fun sendDanmaku(roomId: Long, text: String): Pair<Boolean, String> {
        val client = danmakuClients[roomId] ?: return (false to "弹幕未连接(未开启该房间弹幕)")
        if (!client.connected) return (false to "弹幕连接未就绪")
        return client.sendDanmaku(text)
    }

    private fun onDanmaku(item: DanmakuItem) {
        if (item.type == "LIVE_POPULARITY") {
            rooms[item.roomId]?.livePopularity = item.content.toLongOrNull() ?: 0
            notifyRoomUpdate(item.roomId)
        } else {
            // 屏蔽词过滤（对应原版 BarrageBlockWords，仅过滤文本弹幕）
            if (item.type == "DANMU_MSG" && isBarrageBlocked(item.content)) return
            danmakuBuffer.getOrPut(item.roomId) { mutableListOf() }.let { buf ->
                buf.add(item)
                // 弹幕缓冲上限：长直播早期礼物/上舰/SC 易被逐出，提升到 2000 保内容完整
                while (buf.size > 2000) buf.removeAt(0)
            }
            notifyDanmaku(item)
        }
    }

    /** 弹幕屏蔽词判断（| 分隔，对应原版 BarrageBlockWords） */
    fun isBarrageBlocked(text: String): Boolean {
        val cfg = settings.blockBarrage
        if (cfg.isBlank() || text.isBlank()) return false
        return cfg.split('|').any { it.isNotBlank() && text.contains(it) }
    }

    private fun onDanmakuStatus(roomId: Long, connected: Boolean, msg: String) {
        danmakuStatus[roomId] = connected to msg
        notifyLog(roomId, if (connected) "info" else "warn", "弹幕: $msg")
        notifyDanmakuStatus(roomId, connected, msg)
    }

    /** 最近一次弹幕连接状态（供 UI 打开面板时主动查询，避免错过一次性事件） */
    private val danmakuStatus = java.util.concurrent.ConcurrentHashMap<Long, Pair<Boolean, String>>()
    fun getDanmakuStatus(roomId: Long): Pair<Boolean, String> = danmakuStatus[roomId] ?: (false to "未连接")
    /** 最近弹幕缓冲（供 UI 拉取） */
    val danmakuBuffer = ConcurrentHashMap<Long, MutableList<DanmakuItem>>()

    /**
     * 保存弹幕存档（对应原版 DDTV Danmu.SaveDanmu）
     * 写入 {输出目录}/{主播}/{日期}/danmu_{HH-mm-ss}.json
     */
    fun saveDanmakuFile(card: RoomCard) {
        val items = danmakuBuffer[card.roomId] ?: return
        if (items.isEmpty()) return
        try {
            val dir = java.io.File(outputDir, sanitizeFileName(card.dirName()) +
                    java.io.File.separator + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date()))
            dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("HH-mm-ss", java.util.Locale.CHINA).format(java.util.Date())
            val base = java.io.File(dir, "danmu_$stamp")
            // 1) 全量 JSON（保留原格式）
            val arr = JSONArray()
            items.forEach { it ->
                arr.put(JSONObject().apply {
                    put("time", it.time)
                    put("type", it.type)
                    put("user", it.user)
                    put("uid", it.uid)
                    put("content", it.content)
                    put("color", it.color)
                    put("extra", it.extra)
                })
            }
            base.apply { writeText("""{"roomId":${card.roomId},"name":"${card.name}","count":${arr.length()},"items":${arr.toString(2)}}""") }

            // 2) 分类 CSV（对齐原版 Danmu.SaveDanmu / SevaGift / SevaGuardBuy / SC）
            fun fmtTime(t: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(t))
            fun esc(v: String) = v.replace(",", "，").replace("\r", " ").replace("\n", " ")
            fun writeCsv(suffix: String, header: String, rows: List<DanmakuItem>) {
                if (rows.isEmpty()) return
                val sb = StringBuilder(header)
                rows.forEach { t ->
                    sb.append("\r\n").append(fmtTime(t.time)).append(",").append(esc(t.user)).append(",").append(t.uid).append(",").append(esc(t.content))
                    if (t.extra.isNotEmpty()) sb.append(",").append(esc(t.extra))
                }
                java.io.File(base.absolutePath + suffix).writeText(sb.toString(), Charsets.UTF_8)
            }
            val gifts = items.filter { it.type == "SEND_GIFT" }
            val guards = items.filter { it.type == "GUARD_BUY" || it.type == "GUARD_RENEW" }
            val scs = items.filter { it.type == "SUPER_CHAT_MESSAGE" }
            writeCsv("_弹幕.csv", "时间,昵称,Uid,弹幕内容", items.filter { it.type == "DANMU_MSG" })
            writeCsv("_礼物.csv", "时间,送礼人昵称,送礼人Uid,礼物名称,礼物数量,礼物单价,时间戳", gifts)
            writeCsv("_上舰.csv", "时间,昵称,Uid,类型,内容", guards)
            writeCsv("_SC.csv", "时间,昵称,Uid,内容,价格", scs)
            val files = listOf(base.name + ".json") + listOf("_弹幕.csv", "_礼物.csv", "_上舰.csv", "_SC.csv").map { base.name + it }.filter { java.io.File(dir, base.name + it).exists() }
            Logger.i("Room", "[${card.name}] 弹幕存档: ${files.joinToString()} (弹幕${items.count { it.type == "DANMU_MSG" }}条/礼物${gifts.size}个/上舰${guards.size}次/SC${scs.size}条)")
            notifyLog(card.roomId, "info", "弹幕存档: ${files.joinToString()}")
        } catch (e: Exception) {
            Logger.w("Room", "弹幕存档失败: ${e.message}")
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Live" }
    }

    fun getRecentDanmaku(roomId: Long, limit: Int = 100): List<DanmakuItem> {
        return danmakuBuffer[roomId]?.takeLast(limit) ?: emptyList()
    }

    // ============ 持久化 ============

    fun loadSettings() {
        settings = AppSettings(
            pollInterval = prefs.getInt("poll_interval", 15),
            recordMode = prefs.getString("record_mode", "auto") ?: "auto",
            defaultQuality = prefs.getInt("quality_default", 10000),
            autoRecordDefault = prefs.getBoolean("auto_record_default", true),
            splitByTitle = prefs.getBoolean("split_by_title", false),
            splitSeconds = prefs.getLong("split_seconds", 0),
            splitSizeMB = prefs.getLong("split_size_mb", 0),
            minFileSizeMB = prefs.getLong("min_file_size_mb", 0),
            remuxAfterLive = prefs.getBoolean("remux_after_live", true),
            watchHeartbeat = prefs.getBoolean("watch_heartbeat", false),
            saveCover = prefs.getBoolean("save_cover", true),
            remindLive = prefs.getBoolean("remind_live", true),
            blockBarrage = prefs.getString("block_barrage", "") ?: "",
            fileNameFormat = prefs.getString("file_name_format", "") ?: "",
            repairDeleteSource = prefs.getBoolean("repair_delete_source", true),
            debugServer = prefs.getBoolean("debug_server", false),
            keepScreenOn = prefs.getBoolean("keep_screen_on", false),
            updateRepo = prefs.getString("update_repo", "xialiag/DDTVAndroid")?.takeIf { it.isNotBlank() } ?: "xialiag/DDTVAndroid",
            autoUpdate = prefs.getBoolean("auto_update", true),
            autoStart = prefs.getBoolean("auto_start", true),
        )
    }

    fun saveSettings() {
        prefs.edit()
            .putInt("poll_interval", settings.pollInterval)
            .putString("record_mode", settings.recordMode)
            .putInt("quality_default", settings.defaultQuality)
            .putBoolean("auto_record_default", settings.autoRecordDefault)
            .putBoolean("split_by_title", settings.splitByTitle)
            .putLong("split_seconds", settings.splitSeconds)
            .putLong("split_size_mb", settings.splitSizeMB)
            .putLong("min_file_size_mb", settings.minFileSizeMB)
            .putBoolean("remux_after_live", settings.remuxAfterLive)
            .putBoolean("watch_heartbeat", settings.watchHeartbeat)
            .putBoolean("save_cover", settings.saveCover)
            .putBoolean("remind_live", settings.remindLive)
            .putString("block_barrage", settings.blockBarrage)
            .putString("file_name_format", settings.fileNameFormat)
            .putBoolean("repair_delete_source", settings.repairDeleteSource)
            .putBoolean("debug_server", settings.debugServer)
            .putBoolean("keep_screen_on", settings.keepScreenOn)
            .putString("update_repo", settings.updateRepo)
            .putBoolean("auto_update", settings.autoUpdate)
            .putBoolean("auto_start", settings.autoStart)
            .apply()
        LiveRecorder.applySettings(settings)
        // 小心心开关即时生效
        if (settings.watchHeartbeat) {
            getRooms().filter { it.liveStatus == 1 }.forEach { WatchHeartbeat.register(it.roomId, it.uid) }
        } else {
            WatchHeartbeat.stopAll()
        }
    }

    fun saveRooms() {
        try {
            val arr = JSONArray()
            rooms.values.forEach { card ->
                arr.put(JSONObject().apply {
                    put("roomId", card.roomId)
                    put("shortId", card.shortId)
                    put("uid", card.uid)
                    // 展示信息一并持久化：否则进程重启后列表回退“房间 xxx”占位，要等轮询才补回名字/封面
                    put("name", card.name)
                    put("face", card.face)
                    put("sign", card.sign)
                    put("title", card.title)
                    put("cover", card.cover)
                    put("areaId", card.areaId)
                    put("areaName", card.areaName)
                    put("popularity", card.popularity)
                    put("liveStatus", card.liveStatus)
                    put("autoRecord", card.autoRecord)
                    put("quality", card.quality)
                    put("danmakuOpen", card.danmakuOpen)
                    put("remind", card.remind)
                    put("audioOnly", card.audioOnly)
                    put("cutSeconds", card.cutSeconds)
                    put("cutSizeMB", card.cutSizeMB)
                })
            }
            prefs.edit().putString("rooms", arr.toString()).apply()
        } catch (e: Exception) {
            Logger.w("Room", "保存房间失败: ${e.message}")
        }
    }

    private fun loadRooms() {
        try {
            val raw = prefs.getString("rooms", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val card = RoomCard(
                    roomId = Json.objLong(o, "roomId"),
                    shortId = Json.objLong(o, "shortId"),
                    uid = Json.objLong(o, "uid"),
                    name = Json.obj(o, "name").ifEmpty { "房间 ${Json.objLong(o, "roomId")}" },
                    face = Json.obj(o, "face"),
                    sign = Json.obj(o, "sign"),
                    title = Json.obj(o, "title"),
                    cover = Json.obj(o, "cover"),
                    areaId = Json.objLong(o, "areaId"),
                    areaName = Json.obj(o, "areaName"),
                    popularity = Json.objLong(o, "popularity"),
                    liveStatus = Json.objInt(o, "liveStatus", 0),
                    autoRecord = Json.objBool(o, "autoRecord", true),
                    quality = Json.objInt(o, "quality", 10000),
                    danmakuOpen = Json.objBool(o, "danmakuOpen", true),
                    remind = Json.objBool(o, "remind", true),
                    audioOnly = Json.objBool(o, "audioOnly", false),
                    cutSeconds = Json.objLong(o, "cutSeconds", 0),
                    cutSizeMB = Json.objLong(o, "cutSizeMB", 0),
                    // liveStatusPrev 是会话内开播/下播边沿检测变量,不跨会话携带:
                    // 若从持久化 liveStatus==1 推断,App 退出期间直播结束 → 重启后首轮轮询
                    // 会误报"直播结束"(用户视角:该直播间从未开播)。冷启动一律视为"之前未播"。
                    liveStatusPrev = false,
                )
                rooms[card.roomId] = card
            }
            Logger.i("Room", "加载房间 ${rooms.size} 个")
        } catch (e: Exception) {
            Logger.w("Room", "加载房间失败: ${e.message}")
        }
    }
}
