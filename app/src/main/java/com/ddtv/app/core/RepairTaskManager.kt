package com.ddtv.app.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 修复任务队列（修复工具任务列表/管理系统）
 *
 * 设计：提交即入队，worker 单线程串行执行（FFmpeg 转码/修复较重，避免并发吃满 CPU）。
 * 状态机：pending → running → done | failed | cancelled
 * 管理：取消（运行中取消 FFmpeg 会话，排队中直接出队）、重试（failed/cancelled 重新入队）、删除记录。
 * 事件：状态变化经 listener 通知（bridge 推 repair_task_update 给 JS）。
 */
object RepairTaskManager {

    data class Task(
        val id: Long,
        val input: String,
        val mode: String,
        @Volatile var state: String = "pending",   // pending|running|done|failed|cancelled
        @Volatile var output: String = "",
        @Volatile var error: String = "",
        val submitTime: Long = System.currentTimeMillis(),
        @Volatile var finishTime: Long = 0,
        @Volatile var cancelled: Boolean = false,  // 取消请求标记（区分主动取消与意外失败）
        @Volatile var autoRetried: Boolean = false,  // repair 失败后已自动转码兜底（对齐原版高级修复）
    ) {
        fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put("id", id)
            put("input", input)
            put("mode", mode)
            put("state", state)
            put("output", output)
            put("error", error)
            put("submitTime", submitTime)
            put("finishTime", finishTime)
            put("name", input.substringAfterLast('/'))
        }
    }

    private val tasks = CopyOnWriteArrayList<Task>()
    private val queue = LinkedBlockingQueue<Task>()
    private val workerRunning = AtomicBoolean(false)
    private val idSeq = AtomicLong(1)

    /** 状态变化监听（bridge 注册，推送 JS） */
    @Volatile var listener: (() -> Unit)? = null

    fun list(): List<Task> = tasks.toList()

    // ============ 持久化：退出/被杀后恢复未完成任务（中断的修复下次启动自动继续） ============

    /** 保存当前 pending/running 任务到 prefs（MainActivity.onDestroy 调用；系统杀进程不保证） */
    fun persistPending(ctx: android.content.Context) {
        try {
            val arr = org.json.JSONArray()
            tasks.filter { it.state == "pending" || it.state == "running" }.forEach { t ->
                arr.put(org.json.JSONObject().apply { put("input", t.input); put("mode", t.mode) })
            }
            ctx.getSharedPreferences("ddtv_repair", android.content.Context.MODE_PRIVATE)
                .edit().putString("pending", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** 恢复上次未完成的修复任务（重新入队；running 的半成品输出会被 -y 覆盖重跑） */
    fun restorePending(ctx: android.content.Context) {
        try {
            val prefs = ctx.getSharedPreferences("ddtv_repair", android.content.Context.MODE_PRIVATE)
            val s = prefs.getString("pending", "") ?: return
            prefs.edit().remove("pending").apply()
            if (s.isBlank()) return
            val arr = org.json.JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                submit(o.optString("input"), o.optString("mode", "repair"))
            }
        } catch (_: Exception) {}
    }

    /** 提交任务，返回任务对象 */
    fun submit(input: String, mode: String): Task {
        val t = Task(id = idSeq.getAndIncrement(), input = input, mode = mode)
        tasks.add(0, t)
        queue.offer(t)
        notifyChanged()
        ensureWorker()
        return t
    }

    /** 取消任务：running → FFmpeg 会话取消；pending → 直接出队 */
    fun cancel(id: Long) {
        val t = tasks.find { it.id == id } ?: return
        if (t.state == "running") {
            t.cancelled = true
            FFmpegRepair.cancelAsync(id)
        } else if (t.state == "pending") {
            queue.remove(t)
            t.state = "cancelled"
            t.finishTime = System.currentTimeMillis()
            notifyChanged()
        }
    }

    /** 重试：failed/cancelled 任务重新入队（清空结果） */
    fun retry(id: Long) {
        val t = tasks.find { it.id == id } ?: return
        if (t.state != "failed" && t.state != "cancelled") return
        t.state = "pending"
        t.output = ""
        t.error = ""
        t.finishTime = 0
        t.cancelled = false
        t.autoRetried = false
        queue.offer(t)
        notifyChanged()
        ensureWorker()
    }

    /** 删除任务记录（运行中/排队中不允许删除） */
    fun remove(id: Long): Boolean {
        val t = tasks.find { it.id == id } ?: return false
        if (t.state == "running" || t.state == "pending") return false
        tasks.remove(t)
        notifyChanged()
        return true
    }

    /** 清空所有已结束任务记录 */
    fun clearFinished(): Int {
        val before = tasks.size
        tasks.removeIf { it.state != "running" && it.state != "pending" }
        val removed = before - tasks.size
        if (removed > 0) notifyChanged()
        return removed
    }

    private fun ensureWorker() {
        if (!workerRunning.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    val t = queue.take() ?: break
                    if (t.cancelled) {  // pending 阶段被取消
                        t.state = "cancelled"
                        t.finishTime = System.currentTimeMillis()
                        notifyChanged()
                        continue
                    }
                    if (LiveRecorder.isFileBeingRecorded(t.input)) {
                        // 正在录制的文件不能修：修复会读半成品，删源更会打断录制
                        t.state = "failed"
                        t.error = "文件正在录制中，不能修复"
                        t.finishTime = System.currentTimeMillis()
                        notifyChanged()
                        continue
                    }
                    t.state = "running"
                    notifyChanged()
                    FFmpegRepair.repairAsync(t.input, t.mode, t.id) { output, err ->
                        if (t.cancelled) {
                            t.state = "cancelled"
                            t.error = "已取消"
                        } else if (output != null) {
                            t.state = "done"
                            t.output = output
                            // 对齐原版 TranscodeAsync：修复成功且产物大小合理时，按开关删除源文件
                            try {
                                val del = RoomManager.settings.repairDeleteSource
                                val src = java.io.File(t.input)
                                val dst = java.io.File(output)
                                if (del && !LiveRecorder.isFileBeingRecorded(t.input) && src.exists() && dst.exists() && src.canonicalPath != dst.canonicalPath &&
                                    dst.length() > src.length() / 2) {
                                    src.delete()
                                    Logger.i("RepairQueue", "修复成功，已删除源文件: ${t.input.substringAfterLast('/')}")
                                }
                            } catch (_: Exception) {}
                        } else {
                            // 对齐原版高级修复：repair 失败自动转码兜底一次
                            if (t.mode == "repair" && !t.autoRetried) {
                                t.autoRetried = true
                                Logger.w("RepairQueue", "修复失败，自动转码兜底: ${t.input.substringAfterLast('/')}")
                                FFmpegRepair.repairAsync(t.input, "transcode", t.id) { out2, err2 ->
                                    if (t.cancelled) {
                                        t.state = "cancelled"; t.error = "已取消"
                                    } else if (out2 != null) {
                                        t.state = "done"; t.output = out2
                                        try {
                                            val del = RoomManager.settings.repairDeleteSource
                                            val src = java.io.File(t.input); val dst = java.io.File(out2)
                                            if (del && !LiveRecorder.isFileBeingRecorded(t.input) && src.exists() && dst.exists() && src.canonicalPath != dst.canonicalPath &&
                                                dst.length() > src.length() / 2) src.delete()
                                        } catch (_: Exception) {}
                                    } else {
                                        t.state = "failed"; t.error = err2.ifEmpty { err }
                                    }
                                    t.finishTime = System.currentTimeMillis()
                                    notifyChanged()
                                }
                                return@repairAsync  // 转码回调负责收尾
                            }
                            t.state = "failed"
                            t.error = err
                        }
                        t.finishTime = System.currentTimeMillis()
                        notifyChanged()
                    }
                    // 等待该任务结束（回调置 running 状态为终态）：轮询
                    while (t.state == "running") {
                        try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                    }
                    if (queue.isEmpty()) {
                        // 队列空但可能刚有提交（竞态）：短暂等待后再退出
                        try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                        if (queue.isEmpty()) break
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                workerRunning.set(false)
                // 若有积压（取消/重试竞争），重新拉起
                if (!queue.isEmpty()) ensureWorker()
            }
        }, "RepairQueue").apply { isDaemon = true; start() }
    }

    private fun notifyChanged() {
        try { listener?.invoke() } catch (_: Exception) {}
    }
}
