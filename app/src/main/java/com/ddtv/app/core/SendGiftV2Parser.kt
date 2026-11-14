package com.ddtv.app.core

import org.json.JSONObject
import java.util.Base64

/**
 * SEND_GIFT_V2(protobuf) 礼物消息解析器（移植 DDTV Core/LiveChat/SendGiftV2Parser.cs）。
 * 2026-07 起 B 站灰度上线 SEND_GIFT_V2，字段塞在 data.pb(base64+protobuf)。
 * 这里解码并映射为旧版 SEND_GIFT 的 data 结构（uid/uname/giftName/num/price/coin_type 等），
 * 下游礼物处理逻辑无需改动。proto 结构为逆向推断，未确认字段不输出。
 */
object SendGiftV2Parser {

    /** @return 旧版 SEND_GIFT 格式的 data JSONObject；解析失败返回 null */
    fun decodeToLegacyGiftData(obj: JSONObject): JSONObject? {
        val data = obj.optJSONObject("data") ?: return null
        // 兼容 data.pb 与 data.data.pb 两种位置
        val pbBase64 = data.optString("pb", "").ifEmpty {
            data.optJSONObject("data")?.optString("pb", "") ?: ""
        }
        if (pbBase64.isEmpty()) return null
        val root = try {
            Pb.parse(Base64.getDecoder().decode(pbBase64))
        } catch (_: Exception) {
            return null
        }
        val gift = root.message(10) ?: Pb.empty
        val medal = root.message(8)
        val blind = root.message(9)
        val receiver = gift.message(29)

        return JSONObject().apply {
            put("uid", root.varint(1))
            put("uname", root.str(2))
            put("face", root.str(3))
            put("giftId", gift.varint(1).toInt())
            put("giftName", gift.str(2))
            put("num", gift.varint(3).toInt())
            put("giftType", gift.varint(4).toInt())
            put("price", gift.varint(5).toFloat())
            put("total_coin", gift.varint(6).toInt())
            put("coin_type", gift.str(8))
            put("tid", gift.str(9))
            put("timestamp", gift.varint(10))
            put("rnd", gift.str(12))
            put("action", gift.str(18))
            put("guard_level", medal?.varint(11)?.toInt() ?: 0)
            if (medal != null) put("medal_info", JSONObject().apply {
                put("anchor_uid", medal.varint(1))
                put("medal_level", medal.varint(5).toInt())
                put("medal_name", medal.str(6))
                put("guard_level", medal.varint(11).toInt())
            })
            if (blind != null) put("blind_gift", JSONObject().apply {
                // 盲盒：giftName 是实际爆出的礼物，total_coin 为盲盒购买价(实付)
                put("original_gift_id", blind.varint(2).toInt())
                put("original_gift_name", blind.str(3))
                put("blind_price", blind.varint(6).toInt())
            })
            if (receiver != null) put("receiver", JSONObject().apply {
                put("uname", receiver.str(1))
                put("uid", receiver.varint(2))
            })
        }
    }

    /** 极简 protobuf wire format 解析：仅 varint / fixed32 / fixed64 / length-delimited */
    private class Pb {
        private class Field(val field: Int, val wire: Int, val varint: Long, val bytes: ByteArray?)

        private val fields = ArrayList<Field>()

        fun varint(field: Int): Long = fields.firstOrNull { it.field == field && it.wire == 0 }?.varint ?: 0L
        fun str(field: Int): String =
            fields.firstOrNull { it.field == field && it.wire == 2 }?.bytes?.let { String(it, Charsets.UTF_8) } ?: ""
        fun message(field: Int): Pb? =
            fields.firstOrNull { it.field == field && it.wire == 2 }?.bytes?.let {
                try { parse(it) } catch (_: Exception) { null }
            }

        companion object {
            val empty = Pb()

            fun parse(data: ByteArray): Pb {
                val msg = Pb()
                var pos = 0
                while (pos < data.size) {
                    val key = readVarint(data, pos) ?: return msg
                    pos = key.second
                    val field = (key.first ushr 3).toInt()
                    when (key.first.toInt() and 0x7) {
                        0 -> {
                            val v = readVarint(data, pos) ?: return msg
                            pos = v.second
                            msg.fields.add(Field(field, 0, v.first, null))
                        }
                        1 -> {
                            if (pos + 8 > data.size) return msg
                            var v = 0L
                            for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                            pos += 8
                            msg.fields.add(Field(field, 1, v, null))
                        }
                        2 -> {
                            val l = readVarint(data, pos) ?: return msg
                            pos = l.second
                            val len = l.first.toInt()
                            if (len < 0 || pos + len > data.size) return msg
                            msg.fields.add(Field(field, 2, 0, data.copyOfRange(pos, pos + len)))
                            pos += len
                        }
                        5 -> {
                            if (pos + 4 > data.size) return msg
                            var v = 0L
                            for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                            pos += 4
                            msg.fields.add(Field(field, 5, v, null))
                        }
                        else -> return msg  // 3/4(废弃组)等未知类型终止，避免死循环
                    }
                }
                return msg
            }

            /** @return (值, 新位置)；数据不足返回 null */
            private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
                var result = 0L
                var shift = 0
                var pos = start
                while (pos < data.size && shift < 64) {
                    val b = data[pos++].toInt()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result to pos
                    shift += 7
                }
                return null
            }
        }
    }
}
