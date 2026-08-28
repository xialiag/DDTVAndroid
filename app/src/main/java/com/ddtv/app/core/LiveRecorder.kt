package com.ddtv.app.core

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直播录制引擎（移植 DDTV Core/RuntimeObject/Download/FLV.cs + HLS.cs + Basics.cs）
 *
 * 录制模式（对应 DDTV RecordingMode）：
 *  - auto：HLS(fmp4) 优先，无 HLS 流时降级 FLV
 *  - flv：仅 FLV（http_stream+flv+avc 直连流）
 *  - hls：仅 HLS（http_hls+fmp4+avc 分片下载）
 *
 * 特性：
 *  - FLV 断流重试 3 次（2s/4s/8s 指数退避），重试前重新获取流地址
 *  - HLS 增量拉取 m3u8 分片，支持二级 m3u8、ENDLIST 收尾
 *  - 分割：标题变化 / 按时长 / 按大小（房间级优先，全局兜底）
 *  - 主播重推流（live_time 变化）自动切分
 *  - 断点续写 Append 模式；结束后按阈值清理小文件
 *  - 可选保存封面 cover.jpg（每段录制开始时）
 */
object LiveRecorder {

    interface Listener {
        fun onStateChange(roomId: Long, state: String, file: String)
        fun onProgress(roomId: Long, size: Long, speed: Long)
        fun onSegmentEnd(roomId: Long, file: String)
        fun onLiveEnded(roomId: Long, files: List<String>, reason: String)
        fun onError(roomId: Long, error: String)
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

    /** 多监听器注册 */
    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyStateChange(roomId: Long, state: String, file: String) =
        synchronized(listeners) { listeners.toList() }.forEach { it.onStateChange(roomId, state, file) }

    // 进度推送节流：最多每 500ms 推一次，避免高频 read 循环把 WebView/主线程打满
    private val lastProgressPush = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private fun notifyProgress(roomId: Long, size: Long, speed: Long) {
        val now = System.currentTimeMillis()
        val last = lastProgressPush[roomId] ?: 0L
        if (now - last < 500) return
        lastProgressPush[roomId] = now
        synchronized(listeners) { listeners.toList() }.forEach { it.onProgress(roomId, size, speed) }
    }
    private fun notifySegmentEnd(roomId: Long, file: String) =
        synchronized(listeners) { listeners.toList() }.forEach { it.onSegmentEnd(roomId, file) }
    private fun notifyLiveEnded(roomId: Long, files: List<String>, reason: String) =
        synchronized(listeners) { listeners.toList() }.forEach { it.onLiveEnded(roomId, files, reason) }
    private fun notifyError(roomId: Long, error: String) =
        synchronized(listeners) { listeners.toList() }.forEach { it.onError(roomId, error) }

    @Volatile var outputRoot: File = File("")

    @Volatile var recordMode: String = "flv"
    @Volatile var flvAppendOnReconnect: Boolean = true
    @Volatile var splitByTitle: Boolean = false
    @Volatile var splitSeconds: Long = 0
    @Volatile var splitSizeMB: Long = 0
    @Volatile var minFileSizeMB: Long = 0
    @Volatile var remuxAfterLive: Boolean = true
    @Volatile var saveCover: Boolean = true

    fun applySettings(s: AppSettings) {
        recordMode = s.recordMode
        flvAppendOnReconnect = s.flvAppendOnReconnect
        splitByTitle = s.splitByTitle
        splitSeconds = s.splitSeconds
        splitSizeMB = s.splitSizeMB
        minFileSizeMB = s.minFileSizeMB
        remuxAfterLive = s.remuxAfterLive
        saveCover = s.saveCover
    }

    private val running = HashMap<Long, RecTask>()

    class RecTask(val card: RoomCard) {
        val cancel = AtomicBoolean(false)
        @Volatile var thread: Thread? = null
        /** 当前活跃网络连接（stop 时断开以打断阻塞读流） */
        @Volatile var conn: HttpURLConnection? = null
        /** 断流重试计数（跨分段累计，避免每次分段重置导致无限重连） */
        @Volatile var retryCount = 0
    }

    fun isRecording(roomId: Long): Boolean = running.containsKey(roomId)

    /** 判断文件是否正被某房间录制写入（修复/删除等操作必须避开，否则会打断录制） */
    fun isFileBeingRecorded(path: String): Boolean {
        val target = File(path).absolutePath
        synchronized(running) {
            return running.values.any { it.card.recFile == target }
        }
    }

    /** 房间当前是否在录制（Listen 在线听直播取流时据此错开线路，避免同一直播间并发取流被 B 站限流 403） */
    fun isRecordingRoom(roomId: Long): Boolean = synchronized(running) { running.containsKey(roomId) }

    @Volatile private var lastBackfillAt = 0L

    /**
     * 启动时/录制结束后补提取：audioOnly 房间目录里残留的未提取音频（进程被杀/重启导致收尾未跑），
     * 扫描 *_original.flv|mp4 且无对应 *_audio.m4a 的文件，后台提取为 m4a（内部三级容错：完整→容错copy→重编码）。
     * 防抖：每分钟最多执行一次。
     */
    fun extractPendingAudioFiles() {
        val now = System.currentTimeMillis()
        if (now - lastBackfillAt < 60_000) return
        lastBackfillAt = now
        Thread({
            try {
                RoomManager.getRooms().filter { it.audioOnly }.forEach { card ->
                    val dir = File(RoomManager.outputDir, sanitize(card.dirName()))
                    if (!dir.isDirectory) return@forEach
                    dir.walkTopDown().forEach { f ->
                        if (!f.isFile) return@forEach
                        val n = f.name
                        if (n.endsWith("_original.flv") || n.endsWith("_original.mp4")) {
                            val m4a = f.absolutePath.substringBeforeLast('.') + "_audio.m4a"
                            if (!File(m4a).exists()) {
                                Logger.i("Recorder", "[${card.name}] 补提取音频: ${f.name}")
                                val out = FFmpegRemux.extractAudio(f.absolutePath, card.title, card.name)
                                if (out != null) Logger.i("Recorder", "[${card.name}] 补提取完成: ${File(out).name}")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "AudioBackfill").apply { isDaemon = true; start() }
    }

    /**
     * 启动补转封装：录制目录里残留的 flv(意外退出/中断导致收尾未触发转 mp4)，
     * 且"自动转封装 MP4"设置开启、非录制中、无对应 mp4 的，后台静默加入修复队列。
     */
    fun remuxPendingFlvs() {
        try {
            if (!RoomManager.settings.remuxAfterLive) return
            val flvs = RoomManager.outputDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("flv", true) && !isFileBeingRecorded(it.absolutePath) }
                .toList()
            var pending = 0
            flvs.forEach { f ->
                val mp4 = File(f.absolutePath.removeSuffix(".flv") + ".mp4")
                if (!mp4.exists() || mp4.length() == 0L) { RepairTaskManager.submit(f.absolutePath, "remux"); pending++ }
            }
            if (pending > 0) Logger.i("Recorder", "启动补转: $pending 个未转 mp4 的 FLV 已加入修复队列")
        } catch (_: Exception) {}
    }

    fun start(card: RoomCard): Boolean {
        synchronized(running) {
            if (running.containsKey(card.roomId)) return false
            val task = RecTask(card)
            running[card.roomId] = task
            task.thread = Thread({ run(task) }, "Rec-${card.roomId}").apply { start() }
            return true
        }
    }

    fun stop(roomId: Long) {
        // 先断连（此时 running 仍含该房，能读到 conn），再移除并置取消标志；
        // 原顺序先 remove 会导致 disconnect 读不到 conn，停止最长要等 readTimeout 30s 才能生效
        LiveRecorder.disconnect(roomId)
        synchronized(running) {
            running.remove(roomId)?.cancel?.set(true)
        }
    }

    fun stopAll() {
        synchronized(running) {
            running.values.forEach { it.cancel.set(true) }
            running.clear()
        }
    }

    /** 断开指定房间的活跃连接（打断阻塞中的 read，让停止立即生效） */
    private fun disconnect(roomId: Long) {
        running[roomId]?.conn?.disconnect()
    }

    private fun run(task: RecTask) {
        val card = task.card
        card.recState = "recording"
        card.recStartTime = System.currentTimeMillis()
        card.recSize = 0
        card.recSpeed = 0
        card.files.clear()
        card.lastError = ""
        notifyStateChange(card.roomId, "recording", "")
        try {
            recordLoop(task)
        } catch (e: Exception) {
            Logger.e("Recorder", "[${card.name}] 录制异常: ${e.message}")
            card.lastError = e.message ?: "录制异常"
            card.recState = "idle"
            notifyError(card.roomId, e.message ?: "录制异常")
            notifyStateChange(card.roomId, "idle", "")
            // 异常收尾也迁移占位目录（此时流已断，无打开句柄，可安全改名）
            try { RoomManager.migratePlaceholderFolder(card, force = true) } catch (_: Exception) {}
        } finally {
            // 条件移除：若期间已重新 start（新任务入 map），不要误删新任务
            synchronized(running) { running.remove(card.roomId, task) }
        }
    }

    private fun recordLoop(task: RecTask) {
        val card = task.card
        val roomId = card.roomId
        val maxRetries = 3
        // 备线切换轮转计数：每次取流后换下一条 CDN 线路（对应原版"备线切换"）
        var lineRound = 0

        outer@ while (!task.cancel.get()) {
            // 1. 获取流信息（audioOnly 时优先纯音频流）
            val info = BiliLiveApi.getStreamInfo(roomId, card.quality, card.audioOnly)
            if (info == null) {
                // 流不可用：查直播状态决定
                if (RoomManager.getLiveStatus(roomId) == 0) {
                    Logger.i("Recorder", "[${card.name}] 直播已结束，完成录制")
                    notifyLiveEnded(roomId, card.files.toList(), "live_ended")
                    break
                }
                // 轮播(2)无流：B站侧 playurl 为空（实测 app-room/web-room 均 EMPTY），
                // 继续重试只会每2秒刷"获取失败"日志；停止尝试，等真实开播(status 1)后由轮询/开播事件重新拉起
                if (RoomManager.getLiveStatus(roomId) == 2) {
                    Logger.w("Recorder", "[${card.name}] 轮播中无可用流，停止录制尝试(等待真实开播)")
                    break
                }
                if (!retryDelay(task, 2000)) break
                continue
            }
            if (info.isPaid && !AccountManager.isLoggedIn()) {
                Logger.w("Recorder", "[${card.name}] 付费直播且未登录，跳过")
                card.lastError = "付费直播需要登录"
                notifyError(roomId, "付费直播需要登录")
                break
            }

            // 2. 选择模式
            val mode = when (recordMode) {
                "flv" -> "flv"
                "hls" -> "hls"
                else -> if (info.hlsUrl.isNotEmpty()) "hls" else "flv"
            }

            // 3. 备线切换：CDN 线路优先，PCDN 线路(裸IP节点,需 App UA 指纹)追加在最后仅作兜底
            val primary = if (mode == "hls") {
                info.hlsLines.ifEmpty { listOf(info.hlsUrl) }
            } else {
                info.flvLines.ifEmpty { listOf(info.flvUrl) }
            }
            val pcdnLines = if (mode == "hls") info.hlsPcdnLines else info.flvPcdnLines
            val lines = primary + pcdnLines
            val idx = lineRound % lines.size
            val lineUrl = lines.getOrElse(idx) { lines.first() }
            lineRound++
            val isPcdn = idx >= primary.size
            Logger.i("Recorder", "[${card.name.ifEmpty { card.roomId.toString() }}] 使用线路 ${idx + 1}/${lines.size} (${mode.uppercase()}${if (isPcdn) " PCDN" else ""})")

            // 段边界迁移：上一分片已关闭(无打开句柄)，强制迁移遗留占位目录（名字补全后一个分片内归位）
            RoomManager.migratePlaceholderFolder(card, force = true)

            val result = if (mode == "hls") {
                if (lineUrl.isEmpty()) {
                    Logger.w("Recorder", "[${card.name}] HLS 模式但无 HLS 流")
                    null
                } else hlsSegment(task, card, lineUrl, isPcdn)
            } else {
                if (lineUrl.isEmpty()) {
                    Logger.w("Recorder", "[${card.name}] FLV 模式但无 FLV 流")
                    null
                } else flvSegment(task, card, lineUrl, isPcdn)
            }

            if (task.cancel.get()) {
                Logger.i("Recorder", "[${card.name}] 手动停止录制")
                // 手动停止也触发结束事件（对应原版 RecEndEvent + History 记录）
                if (card.files.isNotEmpty()) notifyLiveEnded(roomId, card.files.toList(), "stopped")
                break
            }

            if (result == null) {
                // 无流可录（auto 模式下降级）
                if (mode == "hls" && recordMode == "auto" && info.flvUrl.isNotEmpty()) {
                    Logger.i("Recorder", "[${card.name}] HLS 无流，降级到 FLV 模式")
                    val r2 = flvSegment(task, card, info.flvUrl)
                    if (task.cancel.get()) break
                    if (r2 == null) {
                        if (RoomManager.getLiveStatus(roomId) == 0) {
                            notifyLiveEnded(roomId, card.files.toList(), "live_ended")
                            break
                        }
                        if (!retryDelay(task, 3000)) break
                    }
                    continue
                }
                if (RoomManager.getLiveStatus(roomId) == 0) {
                    notifyLiveEnded(roomId, card.files.toList(), "live_ended")
                    break
                }
                // 下播前节流：15 秒再检测（原版防刷屏）
                if (!retryDelay(task, 15000)) break
                continue
            }

            when (result) {
                "retry" -> {
                    if (!retryDelay(task, 2000)) break
                }
                "retry_exhausted" -> {
                    // 备线切换：还有未尝试的线路则换线重试，否则结束（原版多条 url_info 备线）
                    if (lines.size > 1) {
                        Logger.w("Recorder", "[${card.name}] 当前线路异常，切换备线重试")
                        if (!retryDelay(task, 2000)) break
                        continue
                    }
                    Logger.w("Recorder", "[${card.name}] 重试耗尽，结束本次录制")
                    notifyLiveEnded(roomId, card.files.toList(), "retry_exhausted")
                    break
                }
                "live_ended" -> {
                    Logger.i("Recorder", "[${card.name}] 直播已结束，完成录制")
                    notifyLiveEnded(roomId, card.files.toList(), "live_ended")
                    break
                }
                "cut" -> {
                    // 分割完成，继续下一段
                    Logger.i("Recorder", "[${card.name}] 分段完成，开始下一段")
                    continue
                }
                else -> {
                    Logger.i("Recorder", "[${card.name}] 段落结束: $result")
                    continue
                }
            }
        }

        card.recState = "idle"
        card.recSpeed = 0
        notifyStateChange(card.roomId, "idle", card.recFile)
    }

    private fun retryDelay(task: RecTask, ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            !task.cancel.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    // ============ 文件命名与分割检查 ============

    /**
     * 生成录制文件路径：{输出目录}/{主播名}/{yyyy-MM-dd}/{文件名}.{ext}
     * 文件名：默认 "HH-mm-ss_{标题}_original"，或按全局自定义格式（关键字替换，对应原版 KeyCharacterReplacement）
     * 支持关键字：{ROOMID} {NAME} {TITLE} {DATE}(yyyy_MM_dd) {TIME}(HH_mm_ss) {YYYY} {YY} {MM} {DD} {HH} {mm} {SS} {FFF}
     */
    private fun newSegmentFile(card: RoomCard, ext: String): String {
        var name = card.name.trim()
        // 名字未就绪(占位)时同步拉一次详情：彻底避免产生 Room<id>/房间 <id> 占位目录（失败才 fallback）
        if (name.isEmpty() || name.startsWith("房间 ") || name.startsWith("Room")) {
            try {
                val d = BiliLiveApi.getRoomDetail(card.roomId)
                if (d != null && d.uploader.isNotBlank()) {
                    card.name = d.uploader
                    if (card.title.isBlank()) card.title = d.title
                    if (card.cover.isBlank()) card.cover = d.cover
                    name = d.uploader.trim()
                }
            } catch (_: Exception) {}
        }
        val dir = File(outputRoot, sanitize(card.dirName()) +
                File.separator + SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()))
        dir.mkdirs()
        val now = Date()
        val fmt = RoomManager.settings.fileNameFormat
        val fileName = if (fmt.isNotBlank()) {
            sanitize(replaceKeywords(fmt, card, now))
        } else {
            val title = sanitize(card.title.ifEmpty { "Live" }).take(40)
            SimpleDateFormat("HH-mm-ss", Locale.CHINA).format(now) + "_" + title + "_original"
        }
        return File(dir, "$fileName.$ext").absolutePath
    }

    /** 文件名关键字替换（对应原版 KeyCharacterReplacement.ReplaceKeyword） */
    private fun replaceKeywords(text: String, card: RoomCard, now: Date): String {
        val d = SimpleDateFormat("yyyy", Locale.CHINA).format(now)
        val yy = SimpleDateFormat("yy", Locale.CHINA).format(now)
        val MM = SimpleDateFormat("MM", Locale.CHINA).format(now)
        val DD = SimpleDateFormat("dd", Locale.CHINA).format(now)
        val HH = SimpleDateFormat("HH", Locale.CHINA).format(now)
        val mm = SimpleDateFormat("mm", Locale.CHINA).format(now)
        val SS = SimpleDateFormat("ss", Locale.CHINA).format(now)
        val FFF = SimpleDateFormat("SSS", Locale.CHINA).format(now)
        return text
            .replace("{ROOMID}", card.roomId.toString())
            .replace("{NAME}", card.name)
            .replace("{TITLE}", card.title)
            .replace("{DATE}", "${d}_${MM}_$DD")
            .replace("{TIME}", "${HH}_${mm}_$SS")
            .replace("{YYYY}", d).replace("{yyyy}", d)
            .replace("{YY}", yy).replace("{yy}", yy)
            .replace("{MM}", MM)
            .replace("{DD}", DD).replace("{dd}", DD)
            .replace("{HH}", HH)
            .replace("{mm}", mm)
            .replace("{SS}", SS).replace("{ss}", SS)
            .replace("{FFF}", FFF).replace("{fff}", FFF)
    }

    /** 保存封面（可选，每段开始时） */
    private fun saveCoverIfNeeded(card: RoomCard, file: String) {
        if (!saveCover || card.cover.isEmpty()) return
        try {
            val coverFile = File(file.substringBeforeLast('.') + "_cover.jpg")
            if (coverFile.exists()) return
            Thread({
                try {
                    val bytes = Http.getBytes(card.cover, referer = "https://live.bilibili.com/")
                    // 同目录已存在相同大小的封面（同一直播多段/换标题）→ 不重复落盘，避免一录播一堆封面
                    val dup = coverFile.parentFile?.listFiles { f ->
                        f.name.endsWith("_cover.jpg") && f.length() == bytes.size.toLong()
                    }?.isNotEmpty() == true
                    if (dup) return@Thread
                    coverFile.writeBytes(bytes)
                } catch (_: Exception) {}
            }, "Cover-${card.roomId}").also { it.isDaemon = true; it.start() }
        } catch (_: Exception) {}
    }

    /** 分割检查：返回 true 表示需要切分 */
    private fun shouldCut(card: RoomCard, segStartLiveTime: Long, segStartTitle: String, elapsedSec: Long, bytes: Long): Boolean {
        // 标题变化
        if (splitByTitle && segStartTitle.isNotEmpty() && segStartTitle != (RoomManager.getRoom(card.roomId)?.title ?: segStartTitle)) {
            Logger.i("Recorder", "[${card.name}] 检测到标题变化，进行切割处理")
            return true
        }
        // 主播重推流（live_time 变化）
        if (segStartLiveTime != 0L && segStartLiveTime != card.liveTime) {
            Logger.i("Recorder", "[${card.name}] 检测到主播重推流，进行切割处理")
            return true
        }
        // 时长分割（房间级优先）
        val cutSec = if (card.cutSeconds > 0) card.cutSeconds else splitSeconds
        if (cutSec > 0 && elapsedSec > cutSec) {
            Logger.i("Recorder", "[${card.name}] 触发时间分割")
            return true
        }
        // 大小分割（房间级优先）
        val cutSize = if (card.cutSizeMB > 0) card.cutSizeMB else splitSizeMB
        if (cutSize > 0 && bytes > cutSize * 1024 * 1024) {
            Logger.i("Recorder", "[${card.name}] 触发大小分割")
            return true
        }
        return false
    }

    /** 收尾检查：无效小文件直接删除（连 FLV 头都不完整，必然无法播放）；过小文件按阈值删除 */
    private fun finalizeSegment(card: RoomCard, file: String, bytes: Long): Boolean {
        val f = File(file)
        if (!f.exists()) return false
        // 硬下限：小于 1KB 不可能是有效录制（FLV 头 9 字节 + 至少一个 tag），403/断流空文件在此清理
        if (f.length() < 1024) {
            Logger.w("Recorder", "[${card.name}] 文件无效(<1KB)，自动删除: $file")
            f.delete()
            card.files.remove(file)
            return false
        }
        val threshold = minFileSizeMB * 1024 * 1024
        if (threshold > 0 && f.length() < threshold) {
            Logger.w("Recorder", "[${card.name}] 文件小于 ${minFileSizeMB}MB，自动删除: $file")
            f.delete()
            card.files.remove(file)
            return false
        }
        return true
    }

    // ============ FLV 尾部收尾 ============

    /**
     * 停止录制时 FLV 可能截断在半个 tag 内，多数播放器（尤其 Android 系统播放器）打不开。
     * 从文件头顺序解析 FLV tag（type + dataSize + data + prevTagSize），找到最后一个完整 tag
     * 的结束位置并截断——只有逐 tag 校验全部通过才截断，绝不猜测尾部（避免误伤整个文件）。
     */
    private fun fixFlvTail(file: File) {
        try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                if (len < 13) return@use
                raf.seek(0)
                val head = ByteArray(9)
                raf.readFully(head)
                if (head[0] != 'F'.code.toByte() || head[1] != 'L'.code.toByte() || head[2] != 'V'.code.toByte()) return@use
                // FLV 头 9 字节 + prevTagSize0 4 字节后开始 tag 序列
                var pos = 13L
                var lastGood = 0L
                while (pos + 11 <= len) {
                    raf.seek(pos)
                    val type = raf.readUnsignedByte()
                    val dataSize = ((raf.readUnsignedByte().toLong() shl 16) or
                        (raf.readUnsignedByte().toLong() shl 8) or raf.readUnsignedByte().toLong())
                    if (type !in 8..18) break  // 非法类型：不是 tag 边界，停止
                    val tagEnd = pos + 11 + dataSize
                    if (tagEnd + 4 > len) break  // data 或 prevTagSize 被截断
                    raf.seek(tagEnd)
                    if (readIntBE(raf) != 11L + dataSize) break  // prevTagSize 不匹配：边界破坏
                    lastGood = tagEnd + 4
                    pos = tagEnd + 4
                }
                // 仅在确有截断（且至少解析出一个完整 tag）时截断
                if (lastGood > 0 && lastGood < len) {
                    raf.setLength(lastGood)
                    Logger.i("Recorder", "FLV 尾部收尾: 截断到最后一个完整 tag (${lastGood}/${len} 字节)")
                }
            }
        } catch (e: Exception) {
            Logger.w("Recorder", "fixFlvTail 异常: ${e.message}")
        }
    }

    private fun readIntBE(raf: java.io.RandomAccessFile): Long =
        ((raf.readUnsignedByte().toLong() shl 24) or (raf.readUnsignedByte().toLong() shl 16) or
            (raf.readUnsignedByte().toLong() shl 8) or raf.readUnsignedByte().toLong())

    /**
     * append 续写重连后跳过服务器重复发送的 FLV 头(9 字节 FLV 头 + 4 字节 prevTagSize0)，
     * 避免文件中段插入 FLV 头破坏 FLV tag 序列。探测读 13 字节；若不是 FLV 头则写回，不丢字节。
     */
    private fun skipReconnectFlvHeader(input: java.io.InputStream, fos: FileOutputStream) {
        val probe = ByteArray(13)
        var n = 0
        try {
            while (n < 13) {
                val r = input.read(probe, n, 13 - n)
                if (r < 0) break
                n += r
            }
        } catch (e: Exception) {
            if (n > 0) fos.write(probe, 0, n)  // 探测异常：写回已读数据，避免丢字节
            return
        }
        val isFlvHeader = n >= 3 && probe[0] == 'F'.code.toByte() &&
            probe[1] == 'L'.code.toByte() && probe[2] == 'V'.code.toByte()
        if (!isFlvHeader && n > 0) fos.write(probe, 0, n)
    }

    /**
     * 段收尾（FLV 先修尾部；仅录音频时提取 m4a）。
     * 文件已被外部删除时清理 card.files 记录，不留幽灵条目。
     * @return 是否有产物留下（供调用方判断是否触发结束事件）
     */
    private fun finishSegment(card: RoomCard, file: String, bytes: Long): Boolean {
        if (!File(file).exists()) {
            card.files.remove(file)
            card.recFile = ""
            return false
        }
        if (file.endsWith(".flv")) fixFlvTail(File(file))
        if (!finalizeSegment(card, file, bytes)) return false
        val finalized = afterSegmentFinalized(card, file)
        if (finalized.isNotEmpty()) notifySegmentEnd(card.roomId, finalized)
        return true
    }

    /**
     * 段定稿后处理（仅录音频模式）：把音视频文件提取为纯音频 m4a。
     * 成功返回新的 m4a 路径并替换 card.files 里的旧项；失败时无效小文件直接清理（403/断流
     * 残留的空 flv 提取必然失败，留着就是"录完格式是 flv 但没视频流"的垃圾文件），
     * 较大的文件保留供手动修复。
     * @return 新 m4a 路径；失败且已清理时返回空串；失败但保留原文件时返回原路径
     */
    private fun afterSegmentFinalized(card: RoomCard, file: String): String {
        if (!card.audioOnly) return file
        val m4a = FFmpegRemux.extractAudio(file, card.title, card.name)
        if (m4a != null) {
            val idx = card.files.indexOf(file)
            if (idx >= 0) card.files[idx] = m4a
            card.recFile = m4a
            return m4a
        }
        // 提取失败：无效小文件（<256KB）直接清理，大文件保留待人工修复
        val f = File(file)
        if (f.exists() && f.length() < 256 * 1024) {
            Logger.w("Recorder", "[${card.name}] 音频提取失败且文件无效，删除: ${f.name} (${f.length()}B)")
            f.delete()
            card.files.remove(file)
            if (card.recFile == file) card.recFile = ""
            return ""
        }
        Logger.w("Recorder", "[${card.name}] 音频提取失败，保留原文件待修复: ${f.name}")
        return file
    }

    // ============ FLV 录制（移植 FLV.cs） ============

    /** @return "retry"/"retry_exhausted"/"live_ended"/"cut"/null(无流) */
    private fun flvSegment(task: RecTask, card: RoomCard, flvUrl: String, pcdn: Boolean = false): String? {
        val roomId = card.roomId
        val file = newSegmentFile(card, "flv")
        card.recFile = file
        card.files.add(file)
        card.recMode = "flv"
        saveCoverIfNeeded(card, file)
        Logger.i("Recorder", "[${card.name}] [FLV] 开始录制: $file")

        val maxRetries = 3
        val segStartLiveTime = card.liveTime
        val segStartTitle = card.title
        val stopWatch = StopWatch()
        var bytesForThisSegment = 0L

        // 外层循环：断流重连（flvAppendOnReconnect 开=原版 Append 同一文件续写；关=断流切新文件）
        while (!task.cancel.get()) {
            var broken = false
            var httpCode = 0  // 0=网络断流/EOF;4xx=地址被拒/过期
            try {
                FileOutputStream(file, true).use { fos ->
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(flvUrl).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 15000
                            readTimeout = 30000
                            // PCDN 节点(裸IP)走 upsig 网关：仅认 App UA 且不能带浏览器 Referer；CDN 线路保持浏览器指纹
                            setRequestProperty("User-Agent", if (pcdn) Http.appUserAgent else Http.userAgent)
                            setRequestProperty("Accept", "*/*")
                            if (!pcdn) setRequestProperty("Referer", "https://www.bilibili.com/")
                            if (Http.cookie.isNotEmpty()) setRequestProperty("Cookie", Http.cookie)
                        }
                        task.conn = conn  // 供 stop() 断开以打断阻塞读流
                        val code = conn.responseCode
                        if (code !in 200..399) {
                            Logger.w("Recorder", "[${card.name}] [FLV] 流 HTTP $code")
                            httpCode = code
                            broken = true
                            return@use
                        }
                        val input = conn.inputStream
                        // 断流重连续写同一文件(append)时，B 站会重新发送 FLV 头(9+4 字节)。
                        // 若直接写文件会把 FLV 头插到文件中段，fixFlvTail 逐 tag 校验时在 'F'(0x46)
                        // 处中断，整段后续数据被判为损坏截除(实测 1.9GB 只留 895MB 丢 30 分钟)。
                        if (flvAppendOnReconnect && java.io.File(file).length() > 0) skipReconnectFlvHeader(input, fos)
                        val buf = ByteArray(81920)
                        while (!task.cancel.get()) {
                            if (shouldCut(card, segStartLiveTime, segStartTitle, stopWatch.elapsedSec(), bytesForThisSegment)) {
                                // 切分：当前文件收尾（按时长/大小/标题分割始终切新文件，同原版）
                                stopWatch.stop()
                                finishSegment(card, file, bytesForThisSegment)
                                return "cut"
                            }
                            val read = try {
                                input.read(buf)
                            } catch (e: Exception) {
                                Logger.w("Recorder", "[${card.name}] [FLV] 读流异常: ${e.message}")
                                broken = true
                                break
                            }
                            if (read < 0) {
                                broken = true
                                break
                            }
                            fos.write(buf, 0, read)
                            bytesForThisSegment += read
                            stopWatch.add(read)
                            // 边录边播：有本地收听(在线听直播)时把同块数据推给本地流代理，不再单独 B 站取流
                            if (LiveStreamProxy.isActive(roomId)) LiveStreamProxy.push(roomId, buf, 0, read)
                            card.recSize += read
                            card.recSpeed = stopWatch.speed()
                            notifyProgress(roomId, card.recSize, card.recSpeed)
                        }
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Logger.w("Recorder", "[${card.name}] [FLV] 写文件异常: ${e.message}")
                broken = true
            }

            if (task.cancel.get()) {
                stopWatch.stop()
                finishSegment(card, file, bytesForThisSegment)
                return "stopped"
            }
            if (!broken) {
                // 正常退出（无断流无 cancel）：外层只会在 cancel 时走到，防御性收尾
                stopWatch.stop()
                finishSegment(card, file, bytesForThisSegment)
                return "stopped"
            }
            // HTTP 4xx（常见 403 防盗链/地址过期）：重试同一条线路没有意义，立即收尾让外层换备线
            if (httpCode in 400..499) {
                stopWatch.stop()
                finishSegment(card, file, bytesForThisSegment)
                return "retry_exhausted"
            }
            // 断流/连接异常：按真实直播状态决定去向
            if (RoomManager.getLiveStatus(roomId) == 0) {
                stopWatch.stop()
                finishSegment(card, file, bytesForThisSegment)
                return "live_ended"
            }
            task.retryCount++
            if (task.retryCount >= maxRetries) {
                stopWatch.stop()
                finishSegment(card, file, bytesForThisSegment)
                return "retry_exhausted"
            }
            val delayMs = (Math.pow(2.0, task.retryCount.toDouble()) * 1000).toInt()
            Logger.i("Recorder", "[${card.name}] [FLV] 流意外中断，${delayMs}ms后第${task.retryCount}次重试")
            if (!retryDelay(task, delayMs.toLong())) {
                stopWatch.stop()
                finishSegment(card, file, bytesForThisSegment)
                return "stopped"
            }
            if (!flvAppendOnReconnect) {
                // 分段模式：断流即收尾切段，recordLoop 重新取流后开新文件
                stopWatch.stop()
                finishSegment(card, file, bytesForThisSegment)
                return "cut"
            }
            // 原版 Append：同一文件续写（append 模式天然从断点继续），继续重连
            Logger.i("Recorder", "[${card.name}] [FLV] 重连后继续写入同一文件: ${file.substringAfterLast('/')}")
        }
        stopWatch.stop()
        finishSegment(card, file, bytesForThisSegment)
        return "stopped"
    }

    // ============ HLS 录制（移植 HLS.cs） ============

    /** @return "retry"/"retry_exhausted"/"live_ended"/"cut"/null(无流) */
    private fun hlsSegment(task: RecTask, card: RoomCard, hlsUrl: String, pcdn: Boolean = false): String? {
        val roomId = card.roomId
        var file = newSegmentFile(card, "mp4")
        card.recFile = file
        card.files.add(file)
        card.recMode = "hls"
        saveCoverIfNeeded(card, file)
        Logger.i("Recorder", "[${card.name}] [HLS] 开始录制: $file")

        val segStartLiveTime = card.liveTime
        val segStartTitle = card.title
        val stopWatch = StopWatch()
        var bytesForThisSegment = 0L
        var currentLocation = -1L
        var initWritten = false  // init segment 每文件只拼一次（提升到 while 外，避免重复 moov 损坏拼接文件）
        var m3u8Url = hlsUrl
        var consecutiveFailures = 0  // 连续失败计数（403/网络）：达到上限收尾换备线，避免同线路无限重试

        try {
            FileOutputStream(file, true).use { fos ->
                while (!task.cancel.get()) {
                    if (shouldCut(card, segStartLiveTime, segStartTitle, stopWatch.elapsedSec(), bytesForThisSegment)) {
                        stopWatch.stop()
                        if (finalizeSegment(card, file, bytesForThisSegment)) notifySegmentEnd(roomId, afterSegmentFinalized(card, file))
                        return "cut"
                    }
                    // 拉取 m3u8（PCDN 线路走 App 指纹：无 Referer + App UA）
                    val m3u8Text = try {
                        Http.get(m3u8Url,
                            referer = if (pcdn) "" else "https://live.bilibili.com/",
                            ua = if (pcdn) Http.appUserAgent else Http.userAgent)
                    } catch (e: Exception) {
                        Logger.w("Recorder", "[${card.name}] [HLS] 拉取 m3u8 失败: ${e.message}")
                        consecutiveFailures++
                        if (consecutiveFailures >= 5) {
                            Logger.w("Recorder", "[${card.name}] [HLS] 连续失败 ${consecutiveFailures} 次，收尾换备线")
                            finishSegment(card, file, bytesForThisSegment)
                            return "retry_exhausted"
                        }
                        Thread.sleep(1000)
                        continue
                    }
                    if (m3u8Text.isBlank()) {
                        Thread.sleep(1000)
                        continue
                    }
                    // 二级 m3u8 处理（原版 Senior_M3U8_Analysis）
                    if (m3u8Text.contains("index.m3u8?")) {
                        val line = m3u8Text.lineSequence().firstOrNull { it.contains("index.m3u8?") }
                        if (line != null) {
                            m3u8Url = line.trim()
                            continue
                        }
                    }
                    // 解析分片
                    val segments = parseM3u8(m3u8Text)
                    if (segments.isEnd && currentLocation < 0 && segments.items.isEmpty()) {
                        // 一开始就 ENDLIST：没有流
                        return null
                    }
                    // 增量下载
                    var downloadedAny = false
                    for (seg in segments.items) {
                        if (task.cancel.get()) break
                        if (seg.index > currentLocation || currentLocation < 0) {
                            // 每个文件只拼一次 init segment（EXT-X-MAP），否则重复 moov 会让拼接文件损坏
                            if (!initWritten && segments.mapUri.isNotEmpty()) {
                                val initUrl = buildSegmentUrl(m3u8Url, segments.mapUri, "mp4")
                                val initStart = fos.channel.position()
                                var okInit = false
                                // 无 init 的文件缺 moov 必坏：重试 3 次，仍失败则放弃本段（外层切线/重试），不留坏文件
                                repeat(3) {
                                    if (task.cancel.get()) return@repeat
                                    okInit = downloadSegment(initUrl, fos, stopWatch, task.cancel, pcdn) { n ->
                                        bytesForThisSegment += n
                                        card.recSize += n
                                        card.recSpeed = stopWatch.speed()
                                        notifyProgress(roomId, card.recSize, card.recSpeed)
                                    }
                                    if (okInit) return@repeat
                                    try { fos.channel.truncate(initStart) } catch (_: Exception) {}
                                    Logger.w("Recorder", "[${card.name}] [HLS] init segment 下载失败，重试中: $initUrl")
                                    Thread.sleep(1500)
                                }
                                initWritten = okInit
                                if (!okInit) {
                                    Logger.w("Recorder", "[${card.name}] [HLS] init segment 重试耗尽，放弃本段")
                                    // 不留 0 字节/残缺文件在磁盘上
                                    try { java.io.File(file).delete() } catch (_: Exception) {}
                                    card.files.remove(file)
                                    card.recFile = ""
                                    return null
                                }
                                Logger.i("Recorder", "[${card.name}] [HLS] init segment 已拼接")
                            }
                            val segUrl = buildSegmentUrl(m3u8Url, seg.fileName, seg.ext)
                            val segStart = fos.channel.position()
                            val ok = downloadSegment(segUrl, fos, stopWatch, task.cancel, pcdn) { n ->
                                bytesForThisSegment += n
                                card.recSize += n
                                card.recSpeed = stopWatch.speed()
                                notifyProgress(roomId, card.recSize, card.recSpeed)
                            }
                            if (!ok) {
                                // 分片下载失败：回退已写字节，刷新 m3u8 再试（原版 host 刷新机制）
                                try { fos.channel.truncate(segStart) } catch (_: Exception) {}
                                consecutiveFailures++
                                if (consecutiveFailures >= 5) {
                                    Logger.w("Recorder", "[${card.name}] [HLS] 连续失败 ${consecutiveFailures} 次（可能 403/线路异常），收尾换备线")
                                    finishSegment(card, file, bytesForThisSegment)
                                    return "retry_exhausted"
                                }
                                Thread.sleep(500)
                                break
                            }
                            consecutiveFailures = 0
                            currentLocation = seg.index
                            downloadedAny = true
                        }
                    }
                    if (task.cancel.get()) break
                    if (segments.isEnd) {
                        // 收到 ENDLIST，收尾
                        if (!downloadedAny && bytesForThisSegment == 0L && currentLocation < 0) {
                            return null
                        }
                        stopWatch.stop()
                        if (finalizeSegment(card, file, bytesForThisSegment)) notifySegmentEnd(roomId, afterSegmentFinalized(card, file))
                        return "cut"  // 下播由外层 live_status 判定；这里切段让外层重新取流
                    }
                    if (!downloadedAny) {
                        // 没有新分片：检查直播状态，仍开播则稍等
                        Thread.sleep(1500)
                        continue
                    }
                    Thread.sleep(500)
                }
            }
        } catch (e: Exception) {
            Logger.w("Recorder", "[${card.name}] [HLS] 异常: ${e.message}")
        }

        // 收尾（含仅录音频时的 m4a 提取；文件被外部删除则清理记录，不留幽灵条目）
        finishSegment(card, file, bytesForThisSegment)
        if (task.cancel.get()) return "stopped"
        // 异常/断流后按真实直播状态分流：仍开播则换新文件继续录，不能一律当"直播已结束"
        // （否则文件被修复/删除等异常打断后，录制会永久中断且轮询不会自动重启）
        return if (RoomManager.getLiveStatus(roomId) != 0) "retry" else "live_ended"
    }

    private class M3u8Result(val items: List<M3u8Segment>, val isEnd: Boolean, val mapUri: String = "")
    private data class M3u8Segment(val index: Long, val fileName: String, val ext: String)

    /** 解析 m3u8：#EXTINF 行后跟分片文件名（数字.fmp4 格式）；提取 EXT-X-MAP init segment */
    private fun parseM3u8(text: String): M3u8Result {
        val items = mutableListOf<M3u8Segment>()
        var isEnd = false
        var mapUri = ""
        val lines = text.split("\n").map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line == "#EXT-X-ENDLIST" -> isEnd = true
                line.startsWith("#EXT-X-MAP") -> {
                    // fmp4 init segment：必须拼在文件开头，否则拼接文件缺 moov 无法解析
                    Regex("URI=\"([^\"]+)\"").find(line)?.let { mapUri = it.groupValues[1] }
                }
                line.startsWith("#EXTINF") -> {
                    // 下一行是分片名
                    val name = lines.getOrNull(i + 1)?.trim() ?: ""
                    if (name.isNotEmpty() && !name.startsWith("#")) {
                        val idx = name.substringBefore('.').toLongOrNull() ?: -1
                        val ext = name.substringAfterLast('.', "fmp4")
                        items.add(M3u8Segment(idx, name, ext))
                        i++
                    }
                }
                else -> {
                    // 直接文件名行（部分 m3u8 无 EXTINF）
                    if (line.isNotEmpty() && !line.startsWith("#") &&
                        line.first().isDigit() && !items.any { it.fileName == line }) {
                        val idx = line.substringBefore('.').toLongOrNull() ?: -1
                        val ext = line.substringAfterLast('.', "fmp4")
                        items.add(M3u8Segment(idx, line, ext))
                    }
                }
            }
            i++
        }
        return M3u8Result(items, isEnd, mapUri)
    }

    /** 拼接分片 URL：m3u8 URL 去掉文件名部分 + 分片名 + 原 query */
    private fun buildSegmentUrl(m3u8Url: String, fileName: String, ext: String): String {
        // fileName 可能已是完整文件名(带扩展名,如 init.mp4)；ext 仅在裸名时补充，否则会拼出双后缀 404
        val query = m3u8Url.substringAfter('?', "")
        val base = m3u8Url.substringBefore('?')
        val basePath = base.substringBeforeLast('/')
        var url = if (fileName.contains('.')) "$basePath/$fileName" else "$basePath/$fileName.$ext"
        if (query.isNotEmpty()) url += "?$query"
        return url
    }

    /** 下载单个分片到 fos，返回是否成功 */
    private fun downloadSegment(url: String, fos: FileOutputStream, stopWatch: StopWatch, cancel: java.util.concurrent.atomic.AtomicBoolean, pcdn: Boolean = false, onBytes: (Int) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 20000
                // PCDN 节点(裸IP)走 upsig 网关：仅认 App UA 且不能带浏览器 Referer；CDN 线路保持浏览器指纹
                setRequestProperty("User-Agent", if (pcdn) Http.appUserAgent else Http.userAgent)
                if (!pcdn) setRequestProperty("Referer", "https://live.bilibili.com/")
                if (Http.cookie.isNotEmpty()) setRequestProperty("Cookie", Http.cookie)
            }
            val code = conn.responseCode
            if (code !in 200..399) return false
            val input = conn.inputStream
            val buf = ByteArray(81920)
            while (!cancel.get()) {  // 停止时立即中断，避免半个分片写入损坏文件尾部
                val read = try {
                    input.read(buf)
                } catch (e: Exception) {
                    return false
                }
                if (read < 0) break
                fos.write(buf, 0, read)
                stopWatch.add(read)
                onBytes(read)
            }
            !cancel.get()
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Live" }
    }
}

/** 简易测速器 */
class StopWatch {
    private var total = 0L
    private var lastTime = System.currentTimeMillis()
    private var lastBytes = 0L
    private var curSpeed = 0L
    @Volatile private var running = true
    private val startTime = System.currentTimeMillis()

    fun add(bytes: Int) {
        if (!running) return
        total += bytes
        val now = System.currentTimeMillis()
        if (now - lastTime >= 1000) {
            curSpeed = (total - lastBytes) * 1000 / (now - lastTime)
            lastTime = now
            lastBytes = total
        }
    }

    fun elapsedSec(): Long = (System.currentTimeMillis() - startTime) / 1000
    val bytes: Long get() = total
    fun speed(): Long = curSpeed
    fun stop() { running = false }
}
