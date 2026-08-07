package com.ddtv.app.core

import org.brotli.dec.BrotliInputStream
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * 弹幕 WebSocket 客户端（移植 DDTV Core/LiveChat/LiveChatListener.cs）
 * 协议：认证 op=7 → 心跳 op=2(10s) → 接收 op=3(人气)/op=5(消息)
 * 压缩：version=2 zlib / version=3 brotli；支持 op=1 发送弹幕（需登录）
 */
class DanmakuClient(private val card: RoomCard) {

    interface Listener {
        fun onDanmaku(item: DanmakuItem)
        fun onStatus(roomId: Long, connected: Boolean, msg: String)
    }

    @Volatile var listener: Listener? = null
    private var ws: WebSocketClient? = null
    @Volatile var connected = false
    @Volatile private var stopped = false
    @Volatile private var connectThread: Thread? = null

    fun start() {
        stopped = false
        if (connectThread?.isAlive == true) return
        connectThread = Thread({ connectLoop() }, "Danmaku-${card.roomId}").apply { isDaemon = true; start() }
    }

    /** 手动重连（风控停止自动重试后由 UI 触发） */
    fun retry() = start()

    fun stop() {
        stopped = true
        connected = false
        try { ws?.close() } catch (_: Exception) {}
        ws = null
        listener?.onStatus(card.roomId, false, "已断开")
    }

    /** 发送弹幕（需登录，返回是否已发出） */
    fun sendDanmaku(text: String): Boolean {
        val client = ws ?: return false
        if (!connected || text.isBlank()) return false
        val acc = AccountManager.account ?: return false
        return try {
            val body = JSONObject().apply {
                put("mode", 1)
                put("msg", text)
                put("roomid", card.roomId)
                put("bubble", 0)
                put("csrf", acc.csrf)
                put("csrf_token", acc.csrf)
                put("rnd", (System.currentTimeMillis() / 1000))
                put("color", 16777215)
                put("fontsize", 25)
            }
            client.send(pack(1, body.toString().toByteArray()))
            true
        } catch (e: Exception) {
            Logger.w("Danmaku", "[${card.roomId}] 发送弹幕失败: ${e.message}")
            false
        }
    }

    private fun connectLoop() {
        var failCount = 0
        while (!stopped) {
            try {
                val server = BiliLiveApi.getDanmuServer(card.roomId)
                if (server == null) {
                    // 获取弹幕服务器失败（风控 -352 等）：先退避重试；连续 3 次失败后停止自动重试，
                    // 避免持续请求把 IP 信誉越刷越黑（-352 多为 IP/指纹风控，冷却后手动重连即可）
                    failCount++
                    if (stopped) return
                    if (failCount >= 3) {
                        Logger.w("Danmaku", "[${card.roomId}] 弹幕服务器连续获取失败，停止自动重试（弹幕面板可手动重连）")
                        listener?.onStatus(card.roomId, false, "连接失败，点击重试")
                        return
                    }
                    val delay = minOf(5000L * (1L shl minOf(failCount - 1, 3)), 60000L)
                    Logger.w("Danmaku", "[${card.roomId}] 弹幕服务器获取失败(第 $failCount 次)，${delay / 1000}s 后重试")
                    Thread.sleep(delay)
                    continue
                }
                failCount = 0
                connectOnce(server)
            } catch (e: Exception) {
                Logger.w("Danmaku", "[${card.roomId}] 连接异常: ${e.message}")
            }
            if (stopped) return
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    private fun connectOnce(server: BiliLiveApi.DanmuServer) {
        val uri = URI("wss://${server.host}:${server.wssPort}/sub")
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Logger.i("Danmaku", "[${card.roomId}] 弹幕连接成功 $uri")
                connected = true
                card.danmakuCount = 0
                listener?.onStatus(card.roomId, true, "已连接")
                // 认证包（登录则带 uid + buvid）
                val acc = AccountManager.account
                val auth = JSONObject().apply {
                    put("uid", acc?.uid ?: 0L)
                    put("roomid", card.roomId)
                    put("protover", 3)
                    put("platform", "web")
                    put("type", 2)
                    put("key", server.token)
                    if (acc != null) {
                        put("buvid", extractBuvid(acc.cookie).ifEmpty { extractBuvid(BiliLiveApi.ensureFingerprintCookie()) })
                    }
                }
                send(pack(7, auth.toString().toByteArray()))
                // 心跳线程（首个心跳立即发，避免服务器 2 秒无数据断连）
                Thread({
                    try {
                        while (!stopped && connected && !isClosed) {
                            send(pack(2, "[object Object]".toByteArray()))
                            try { Thread.sleep(10000) } catch (_: InterruptedException) { break }
                        }
                    } catch (_: Exception) {}
                }, "Heartbeat-${card.roomId}").also { it.isDaemon = true; it.start() }
            }

            /** 服务器 ping：打日志确认到达，super 自动回 pong（服务器 2 秒无 pong 会断连） */
            override fun onWebsocketPing(webSocket: org.java_websocket.WebSocket?, f: org.java_websocket.framing.Framedata?) {
                Logger.d("Danmaku", "[${card.roomId}] 收到服务器 ping")
                super.onWebsocketPing(webSocket, f)
            }

            override fun onMessage(bytes: ByteBuffer?) {
                if (bytes == null) return
                val data = ByteArray(bytes.remaining())
                bytes.get(data)
                try {
                    unpack(data)
                } catch (e: Exception) {
                    // 单包解析异常不影响连接
                    Logger.d("Danmaku", "[${card.roomId}] 解析弹幕包异常: ${e.message}")
                }
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                // 完整原因进日志；UI 只显示简洁中文（不刷屏一长串英文）
                Logger.w("Danmaku", "[${card.roomId}] 连接关闭 code=$code remote=$remote: $reason")
                connected = false
                listener?.onStatus(card.roomId, false, "已断开")
            }

            override fun onError(ex: Exception?) {
                Logger.w("Danmaku", "[${card.roomId}] 错误: ${ex?.message}")
            }
        }
        ws = client
        try {
            // 禁用库自带 lost-connection detection（默认 60s 发 ws ping 等 pong）：
            // B 站弹幕服务器不响应 ws ping 帧（no-pong 靠应用层 op=2 心跳维持），
            // 否则每 60s 被主动断开 code=1006 “did not respond with a pong”。
            client.setConnectionLostTimeout(0)
            // connectBlocking 在连接建立(onOpen)后即返回，必须在此保持连接直到关闭，
            // 否则 connectLoop 会每 3 秒新建连接，旧连接堆积直到各自超时被断（日志表现为反复重连）
            // 30s 连接超时：TCP 黑洞/握手挂起时不会无限阻塞（否则无日志且永不重试）
            if (client.connectBlocking(30, java.util.concurrent.TimeUnit.SECONDS)) {
                while (!stopped && !client.isClosed()) {
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
            } else {
                Logger.w("Danmaku", "[${card.roomId}] 弹幕连接超时(30s) $uri")
                listener?.onStatus(card.roomId, false, "连接超时，重试中")
            }
        } catch (e: Exception) {
            Logger.w("Danmaku", "[${card.roomId}] 连接失败: ${e.message}")
        }
    }

    private fun extractBuvid(cookie: String): String {
        cookie.split(";").forEach { pair ->
            val kv = pair.trim()
            if (kv.startsWith("buvid3=")) return kv.substringAfter('=')
        }
        return ""
    }

    // ============ 协议解析 ============

    /** 16字节头 + body 封包 */
    private fun pack(op: Int, body: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(16 + body.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(16 + body.size)
        buf.putShort(16)
        buf.putShort(1)
        buf.putInt(op)
        buf.putInt(1)
        buf.put(body)
        return buf.array()
    }

    /** 解包：version 1/2(zlib)/3(brotli)，op=3 人气，op=5 消息 */
    private fun unpack(data: ByteArray) {
        if (data.size < 16) return
        val header = ByteBuffer.wrap(data, 0, 16).order(ByteOrder.BIG_ENDIAN)
        val packetLen = header.int
        val headerLen = header.short.toInt()
        val version = header.short.toInt()
        val op = header.int
        if (packetLen < 16 || data.size < packetLen) return
        val body = data.copyOfRange(headerLen, packetLen)

        when (version) {
            1 -> dispatch(op, body)
            2 -> {
                try {
                    // B站压缩包：2 字节头部后是 raw deflate（无 zlib 头），须用 nowrap Inflater
                    val inflater = Inflater(true)
                    inflater.setInput(body, 2, body.size - 2)
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buf)
                        if (n == 0 && inflater.needsInput()) break
                        out.write(buf, 0, n)
                    }
                    inflater.end()
                    parseMultiPack(out.toByteArray())
                } catch (e: Exception) {
                    Logger.w("Danmaku", "[${card.roomId}] zlib 解压失败: ${e.message}")
                }
            }
            3 -> {
                try {
                    val bis = BrotliInputStream(ByteArrayInputStream(body))
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    var n: Int
                    while (bis.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                    bis.close()
                    parseMultiPack(out.toByteArray())
                } catch (e: Exception) {
                    Logger.w("Danmaku", "[${card.roomId}] brotli 解压失败: ${e.message}")
                }
            }
        }
    }

    /** 压缩解压后的数据流包含多个完整包 */
    private fun parseMultiPack(flat: ByteArray) {
        var offset = 0
        while (offset + 16 <= flat.size) {
            val bb = ByteBuffer.wrap(flat, offset, 16).order(ByteOrder.BIG_ENDIAN)
            val len = bb.int
            val headerLen = bb.short.toInt()
            val version = bb.short.toInt()
            val op = bb.int
            if (len < 16 || offset + len > flat.size) break
            if (version == 2) {
                unpack(flat.copyOfRange(offset, offset + len))
            } else {
                val body = flat.copyOfRange(offset + headerLen, offset + len)
                dispatch(op, body)
            }
            offset += len
        }
    }

    private fun dispatch(op: Int, body: ByteArray) {
        when (op) {
            3 -> {
                if (body.size >= 4) {
                    val popularity = (body[0].toLong() and 0xFF shl 24) or
                            (body[1].toLong() and 0xFF shl 16) or
                            (body[2].toLong() and 0xFF shl 8) or
                            (body[3].toLong() and 0xFF)
                    card.livePopularity = popularity
                    listener?.onDanmaku(DanmakuItem(
                        roomId = card.roomId, type = "LIVE_POPULARITY", content = popularity.toString()
                    ))
                }
            }
            5 -> {
                try {
                    parseMessage(String(body, Charsets.UTF_8))
                } catch (e: Exception) {
                    Logger.w("Danmaku", "[${card.roomId}] 消息解析失败: ${e.message}")
                }
            }
            8 -> {
                // 认证回应
                try {
                    val obj = JSONObject(String(body, Charsets.UTF_8))
                    if (obj.optInt("code", -1) == 0) {
                        listener?.onStatus(card.roomId, true, "认证成功")
                    } else {
                        Logger.w("Danmaku", "[${card.roomId}] 认证被拒绝: ${obj.optString("message")}")
                        listener?.onStatus(card.roomId, false, "认证被拒绝: ${obj.optString("message")}")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun parseMessage(json: String) {
        val obj = JSONObject(json)
        val cmd = obj.optString("cmd", "")
        val listener = listener ?: return
        val roomId = card.roomId
        val item = when {
            cmd == "DANMU_MSG" -> {
                val info = obj.optJSONArray("info") ?: return
                val user = info.optJSONArray(2) ?: return
                val uid = user.optLong(0, 0)
                val uname = user.optString(1, "")
                val content = info.optString(1, "")
                val color = info.optInt(0, 0) and 0xFFFFFF
                DanmakuItem(roomId = roomId, type = "DANMU_MSG", user = uname, uid = uid, content = content, color = color)
            }
            cmd == "SEND_GIFT" -> {
                val data = obj.optJSONObject("data") ?: return
                val giftName = data.optString("giftName", "礼物")
                val num = data.optInt("num", 1)
                val price = data.optLong("price", 0)
                // price 单位金瓜子(1000=1元);coin_type=GOLD 才是付费礼物,
                // SILVER(银瓜子)/免费礼物不换算成金额
                val extra = if (data.optString("coin_type", "GOLD") == "GOLD" && price > 0)
                    "¥${price * num / 1000.0}" else ""
                DanmakuItem(
                    roomId = roomId, type = "SEND_GIFT",
                    user = data.optString("uname", ""),
                    uid = data.optLong("uid", 0),
                    content = "赠送了 $giftName ×$num",
                    extra = extra
                )
            }
            cmd == "SUPER_CHAT_MESSAGE" -> {
                val data = obj.optJSONObject("data") ?: return
                DanmakuItem(
                    roomId = roomId, type = "SUPER_CHAT_MESSAGE",
                    user = data.optJSONObject("user_info")?.optString("uname", "") ?: "",
                    uid = data.optJSONObject("user_info")?.optLong("uid", 0) ?: 0,
                    content = data.optString("message", ""),
                    extra = "SC ¥${data.optLong("price", 0)}"
                )
            }
            cmd == "GUARD_BUY" -> {
                val data = obj.optJSONObject("data") ?: return
                DanmakuItem(
                    roomId = roomId, type = "GUARD_BUY",
                    user = data.optString("username", ""),
                    uid = data.optLong("uid", 0),
                    content = "开通了${data.optString("gift_name", "舰长")}",
                    extra = "¥${data.optLong("price", 0) / 1000.0}"
                )
            }
            cmd == "GUARD_RENEW" -> {
                val data = obj.optJSONObject("data") ?: return
                DanmakuItem(
                    roomId = roomId, type = "GUARD_RENEW",
                    user = data.optString("username", ""),
                    uid = data.optLong("uid", 0),
                    content = "续费了${data.optString("gift_name", "舰长")}",
                    extra = "×${data.optInt("num", 1)}"
                )
            }
            cmd == "INTERACT_WORD" -> {
                val data = obj.optJSONObject("data") ?: return
                DanmakuItem(
                    roomId = roomId, type = "INTERACT_WORD",
                    user = data.optString("uname", ""),
                    uid = data.optLong("uid", 0),
                    content = if (data.optInt("msg_type", 0) == 1) "进入了直播间" else "互动"
                )
            }
            else -> null
        }
        if (item != null) {
            card.danmakuCount++
            listener.onDanmaku(item)
        }
    }
}
