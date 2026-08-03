package com.ddtv.app.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 观看时长心跳（x25Kn 协议，移植 DDTV WatchHeartbeatManager.cs + Network/Methods/WatchHeartbeat.cs + HmacChain.cs）
 * 网页播放器通过 live-trace.bilibili.com 的 E(进入)/X(周期心跳) 上报观看行为，
 * 是B站结算"观看时长"(粉丝勋章亲密度等)的依据；裸拉流录制不会触发，由此类补充上报。
 * 需登录（SESSDATA + bili_jct）。
 */
object WatchHeartbeat {

    @Volatile var logListener: ((String) -> Unit)? = { msg -> Logger.i("Heartbeat", msg) }

    private class Session(val roomId: Long, val anchorUid: Long) {
        val stopFlag = AtomicBoolean(false)
        @Volatile var thread: Thread? = null
    }

    private val sessions = ConcurrentHashMap<Long, Session>()

    fun register(roomId: Long, anchorUid: Long) {
        if (roomId <= 0) return
        if (sessions.containsKey(roomId)) return
        val session = Session(roomId, anchorUid)
        sessions[roomId] = session
        session.thread = Thread({ run(session) }, "Heartbeat-$roomId").also { it.isDaemon = true; it.start() }
        log("房间 $roomId 观看时长心跳启动")
    }

    fun unregister(roomId: Long) {
        sessions.remove(roomId)?.let { it.stopFlag.set(true) }
    }

    fun stopAll() {
        sessions.values.forEach { it.stopFlag.set(true) }
        sessions.clear()
    }

    private fun log(msg: String) {
        logListener?.invoke(msg)
    }

    private fun run(session: Session) {
        val roomId = session.roomId
        val anchorUid = session.anchorUid
        var failures = 0
        try {
            // 未登录直接退出
            if (!AccountManager.isLoggedIn()) {
                log("房间 $roomId 未登录，观看时长心跳不启动")
                return
            }
            if (RoomManager.getLiveStatus(roomId) != 1) {
                log("房间 $roomId 未开播，观看时长心跳停止")
                return
            }
            // 分区信息（失败按 0,0 兜底）
            val area = BiliLiveApi.getRoomAreaInfo(roomId)
            if (area == null) log("房间 $roomId 获取分区信息失败，按默认参数继续")
            val parentArea = area?.first ?: 0L
            val areaId = area?.second ?: 0L

            val account = AccountManager.account ?: return
            val buvid = extractBuvid(account.cookie).ifEmpty { genBuvid() }
            val deviceJson = "[\"$buvid\",\"${java.util.UUID.randomUUID()}\"]"
            val idJson = "[$parentArea,$areaId,0,$roomId]"
            Logger.d("Heartbeat", "构造 device buvid=${buvid.take(6)}..(${buvid.length}) 含特殊字符=${buvid.any { it == '"' || it == '\\' || it == '%' }} idJson=$idJson")
            val csrf = account.csrf.ifEmpty { BiliLiveApi.extractCsrf(account.cookie) }

            var secret = sendE(idJson, deviceJson, anchorUid, csrf)
            if (secret == null) {
                failures++
                log("房间 $roomId 心跳E请求失败（连续第${failures}次）")
                if (failures >= 3) return
                Thread.sleep(10000)
                secret = sendE(idJson, deviceJson, anchorUid, csrf)
                if (secret == null) return
            }
            failures = 0
            log("房间 $roomId 观看时长心跳已启动(间隔${secret.interval}秒)")

            while (!session.stopFlag.get()) {
                Thread.sleep(secret!!.interval * 1000L)
                if (session.stopFlag.get()) break
                if (RoomManager.getLiveStatus(roomId) != 1) {
                    log("房间 $roomId 已下播，观看时长心跳停止")
                    return
                }
                if (!AccountManager.isLoggedIn()) {
                    log("房间 $roomId 登录态失效，观看时长心跳停止")
                    return
                }
                val acc = AccountManager.account ?: return
                val next = sendX(idJson, deviceJson, anchorUid, acc.csrf.ifEmpty { BiliLiveApi.extractCsrf(acc.cookie) }, secret!!)
                if (next == null) {
                    failures++
                    log("房间 $roomId 心跳X请求失败（连续第${failures}次），准备重建心跳链")
                    if (failures >= 3) {
                        log("房间 $roomId 观看心跳连续失败已达上限，停止尝试")
                        return
                    }
                    secret = sendE(idJson, deviceJson, anchorUid, csrf) ?: continue
                    failures = 0
                } else {
                    secret = next
                    failures = 0
                }
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            log("房间 $roomId 观看心跳异常: ${e.message}")
        } finally {
            sessions.remove(roomId, session)
        }
    }

    private fun extractBuvid(cookie: String): String {
        cookie.split(";").forEach { pair ->
            val kv = pair.trim()
            if (kv.startsWith("buvid3=")) return kv.substringAfter('=')
        }
        return ""
    }

    private fun genBuvid(): String {
        val uuid = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
        return "XY${uuid}infoc"
    }

    private data class Secret(val timestamp: Long, val interval: Int, val secretKey: String, val rule: IntArray)

    /** E 请求：进入房间，返回 secret 三要素 */
    private fun sendE(idJson: String, deviceJson: String, anchorUid: Long, csrf: String): Secret? {
        return try {
            val form = linkedMapOf<String, String>(
                "id" to idJson,
                "device" to deviceJson,
                "ts" to System.currentTimeMillis().toString(),
                "is_patch" to "0",
                "heart_beat" to "[]",
                "ua" to Http.userAgent,
                "csrf_token" to csrf,
                "csrf" to csrf,
                "visit_id" to "",
                "ruid" to anchorUid.toString(),
            )
            val body = Http.postFormCompat(
                "${BiliLiveApi.TRACE_DOMAIN}/xlive/data-interface/v1/x25Kn/E",
                form, referer = BiliLiveApi.LIVE_WEB_DOMAIN
            )
            parseSecret(body)
        } catch (e: Exception) {
            Logger.w("Heartbeat", "E请求异常: ${e.message}")
            null
        }
    }

    /** X 请求：周期心跳，基于上次 secret 计算 s 签名 */
    private fun sendX(idJson: String, deviceJson: String, anchorUid: Long, csrf: String, prev: Secret): Secret? {
        return try {
            val ts = System.currentTimeMillis()
            // B 站 x25Kn 签名 Body（2020 年改版后的格式，wasm 算法同款）：
            // id 拆成 parent_id/area_id/seq_id/room_id，device 拆成 buvid/uuid，
            // 字段序固定且无空格；benchmark(secret_key) 是 HMAC key 不参与 Body。
            val idParts = idJson.removePrefix("[").removeSuffix("]").split(",").map { it.trim() }
            val parentId = idParts.getOrElse(0) { "0" }
            val areaId = idParts.getOrElse(1) { "0" }
            val seqId = idParts.getOrElse(2) { "0" }
            val roomId = idParts.getOrElse(3) { "0" }
            val devParts = deviceJson.removePrefix("[").removeSuffix("]").split(",")
            val buvid = devParts.getOrElse(0) { "\"\"" }.trim().removeSurrounding("\"")
            val uuid = devParts.getOrElse(1) { "\"\"" }.trim().removeSurrounding("\"")
            val signBody = "{\"platform\":\"web\",\"parent_id\":$parentId,\"area_id\":$areaId,\"seq_id\":$seqId,\"room_id\":$roomId," +
                    "\"buvid\":\"$buvid\",\"uuid\":\"$uuid\",\"ets\":${prev.timestamp},\"time\":${prev.interval},\"ts\":$ts}"
            val s = HmacChain.compute(signBody, prev.secretKey, prev.rule)
            val devM = deviceJson.replace(Regex("(\"[^\"]{0,8})[^\"]*(\")"), "$1..$2")
            Logger.d("Heartbeat", "X诊断 signBody=$signBody")
            Logger.d("Heartbeat", "X诊断摘要 device=$devM csrf=${if (csrf.isEmpty()) "空" else csrf.take(2) + ".."} rule=${prev.rule.joinToString(",")} key=${prev.secretKey.take(4)}.. ets=${prev.timestamp} intv=${prev.interval} s=${s.take(10)}..")
            val form = linkedMapOf<String, String>(
                "id" to idJson,
                "device" to deviceJson,
                "ets" to prev.timestamp.toString(),
                "benchmark" to prev.secretKey,
                "time" to prev.interval.toString(),
                "ts" to ts.toString(),
                "ua" to Http.userAgent,
                "is_patch" to "0",
                "heart_beat" to "[]",
                "csrf_token" to csrf,
                "csrf" to csrf,
                "visit_id" to "",
                "ruid" to anchorUid.toString(),
                "s" to s,
            )
            val body = Http.postFormCompat(
                "${BiliLiveApi.TRACE_DOMAIN}/xlive/data-interface/v1/x25Kn/X",
                form, referer = BiliLiveApi.LIVE_WEB_DOMAIN
            )
            parseSecret(body)
        } catch (e: Exception) {
            Logger.w("Heartbeat", "X请求异常: ${e.message}")
            null
        }
    }

    private fun parseSecret(body: String): Secret? {
        return try {
            val obj = org.json.JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.w("Heartbeat", "心跳被服务器拒绝 code=${obj.optInt("code")} msg=${obj.optString("message")}")
                return null
            }
            val d = obj.getJSONObject("data")
            val ruleArr = d.optJSONArray("secret_rule")
            val rule = IntArray(ruleArr?.length() ?: 0) { ruleArr!!.optInt(it) }
            Logger.d("Heartbeat", "E/X诊断: key=${d.optString("secret_key", "").take(6)}.. rule=${rule.joinToString(",")} ts=${d.optLong("timestamp", 0)} interval=${d.optInt("heartbeat_interval", 0)}")
            val interval = d.optInt("heartbeat_interval", 60).let { if (it <= 0) 60 else it }
            Secret(
                timestamp = d.optLong("timestamp", 0),
                interval = interval,
                secretKey = d.optString("secret_key", ""),
                rule = rule,
            )
        } catch (e: Exception) {
            Logger.w("Heartbeat", "心跳响应解析失败: ${e.message}")
            null
        }
    }
}

/**
 * 链式 HMAC 签名（移植 DDTV HmacChain.cs）
 * secret_rule 索引与算法: 0=MD5 1=SHA1 2=SHA256 3=SHA224 4=SHA512 5=SHA384
 * 每一步以上一步的小写 hex 输出作为下一步输入，secret_key 始终作为 HMAC key
 */
object HmacChain {

    fun compute(message: String, key: String, rule: IntArray): String {
        if (rule.isEmpty()) throw IllegalArgumentException("secret_rule不能为空")
        var data = message.toByteArray(Charsets.UTF_8)
        var hex = ""
        for (r in rule) {
            val digest = when (r) {
                0 -> hmac("HmacMD5", key, data)
                1 -> hmac("HmacSHA1", key, data)
                2 -> hmac("HmacSHA256", key, data)
                3 -> hmacSha224(key, data)
                4 -> hmac("HmacSHA512", key, data)
                5 -> hmac("HmacSHA384", key, data)
                else -> throw IllegalArgumentException("未知的secret_rule索引:$r")
            }
            hex = toLowerHex(digest)
            data = hex.toByteArray(Charsets.UTF_8)
        }
        return hex
    }

    private fun hmac(algo: String, key: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), algo))
        return mac.doFinal(data)
    }

    /** HMAC-SHA224：JVM 无内置，按 RFC2104 用 SHA-224 手动实现（块大小 64） */
    fun hmacSha224(key: String, message: ByteArray): ByteArray {
        val blockSize = 64
        var k = key.toByteArray(Charsets.UTF_8)
        if (k.size > blockSize) k = sha224(k)
        val kPad = ByteArray(blockSize)
        System.arraycopy(k, 0, kPad, 0, k.size)

        val inner = ByteArray(blockSize + message.size)
        val outerPad = ByteArray(blockSize)
        for (i in 0 until blockSize) {
            inner[i] = (kPad[i].toInt() xor 0x36).toByte()
            outerPad[i] = (kPad[i].toInt() xor 0x5c).toByte()
        }
        System.arraycopy(message, 0, inner, blockSize, message.size)
        val innerHash = sha224(inner)
        val outer = ByteArray(blockSize + innerHash.size)
        System.arraycopy(outerPad, 0, outer, 0, blockSize)
        System.arraycopy(innerHash, 0, outer, blockSize, innerHash.size)
        return sha224(outer)
    }

    /** SHA-224：与 SHA-256 共用压缩函数，仅初始向量与输出长度(28字节)不同 */
    fun sha224(data: ByteArray): ByteArray {
        val K = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0xfc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x6ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
        )
        var h0 = 0xc1059ed8.toInt(); var h1 = 0x367cd507.toInt(); var h2 = 0x3070dd17.toInt(); var h3 = 0xf70e5939.toInt()
        var h4 = 0xffc00b31.toInt(); var h5 = 0x68581511.toInt(); var h6 = 0x64f98fa7.toInt(); var h7 = 0xbefa4fa4.toInt()

        val bitLen = data.size.toLong() * 8
        val padLen = (56 - (data.size + 1) % 64 + 64) % 64
        val padded = ByteArray(data.size + 1 + padLen + 8)
        System.arraycopy(data, 0, padded, 0, data.size)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = (bitLen shr (8 * i)).toByte()
        }

        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (t in 0 until 16) {
                val idx = offset + t * 4
                w[t] = ((padded[idx].toInt() and 0xFF) shl 24) or ((padded[idx + 1].toInt() and 0xFF) shl 16) or
                        ((padded[idx + 2].toInt() and 0xFF) shl 8) or (padded[idx + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = Integer.rotateRight(w[t - 15], 7) xor Integer.rotateRight(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = Integer.rotateRight(w[t - 2], 17) xor Integer.rotateRight(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4; var f = h5; var g = h6; var h = h7
            for (t in 0 until 64) {
                val S1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + S1 + ch + K[t] + w[t]
                val S0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = S0 + maj
                h = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
            offset += 64
        }
        // 只输出前 7 个字（28 字节）
        val hs = intArrayOf(h0, h1, h2, h3, h4, h5, h6)
        val result = ByteArray(28)
        for (i in 0 until 7) {
            result[i * 4] = (hs[i] shr 24).toByte()
            result[i * 4 + 1] = (hs[i] shr 16).toByte()
            result[i * 4 + 2] = (hs[i] shr 8).toByte()
            result[i * 4 + 3] = hs[i].toByte()
        }
        return result
    }

    private fun toLowerHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX = charArrayOf('0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f')
}
