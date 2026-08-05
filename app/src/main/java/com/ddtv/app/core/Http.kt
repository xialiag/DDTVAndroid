package com.ddtv.app.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * HTTP 工具（沿用 BBDownAndroid 方案）
 * 支持 gzip/deflate、自动重定向、Cookie/Referer/UA 头
 */
object Http {
    @Volatile var cookie: String = ""
    @Volatile var userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0"
    /** PCDN 节点要求的 B站 App 指纹（upsig 防盗链网关校验 UA 且不接受浏览器 Referer；实测仅 App UA+无 Referer 放行） */
    @Volatile var appUserAgent: String =
        "Bilibili/8980200 (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; com.bilibili.app.blue; 8980200) " +
            "Dalvik/2.1.0 (Linux; U; Android ${android.os.Build.VERSION.RELEASE}; Build/${android.os.Build.ID})"

    /** GET 文本，自动解压 */
    fun get(url: String, extraCookie: String = "", referer: String = "", ua: String = userAgent): String {
        val bytes = getBytes(url, "GET", null, extraCookie, referer, ua = ua)
        return String(bytes, Charsets.UTF_8)
    }

    /** POST 表单数据，返回文本 */
    fun postForm(url: String, formData: Map<String, String>, extraCookie: String = "", referer: String = ""): String {
        val body = formData.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        val bytes = postBytes(url, body, "application/x-www-form-urlencoded", extraCookie, referer)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * .NET 兼容表单编码（RFC 1738 子集）：保留 `-_.!~*'()` 等字符，空格转 `+`。
     * Java URLEncoder 会把括号/逗号等全转 %XX，若服务端按 .NET 风格解码重算签名（如 x25Kn 心跳的 ua），
     * 两端值不一致会导致 sign check failed —— 心跳类请求必须用本编码。
     */
    fun encodeFormCompat(s: String): String {
        val keep = "-_.!~*'()"
        val sb = StringBuilder(s.length)
        s.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = b.toInt() and 0xFF
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code -> sb.append(c.toChar())
                c == ' '.code -> sb.append('+')
                keep.contains(c.toChar()) -> sb.append(c.toChar())
                else -> sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }

    /** POST 表单（.NET 兼容编码，心跳专用） */
    fun postFormCompat(url: String, formData: Map<String, String>, extraCookie: String = "", referer: String = ""): String {
        val body = formData.entries.joinToString("&") {
            encodeFormCompat(it.key) + "=" + encodeFormCompat(it.value)
        }.toByteArray(Charsets.UTF_8)
        val bytes = postBytes(url, body, "application/x-www-form-urlencoded", extraCookie, referer)
        return String(bytes, Charsets.UTF_8)
    }

    /** POST JSON 字符串（原版 room.cs 的 get_status_info_by_uids 用裸 JSON body） */
    fun postJson(url: String, jsonBody: String, extraCookie: String = "", referer: String = ""): String {
        val bytes = postBytes(url, jsonBody.toByteArray(Charsets.UTF_8), "application/json", extraCookie, referer)
        return String(bytes, Charsets.UTF_8)
    }

    /** POST 字节流，指定 Content-Type */
    fun postBytes(
        url: String, body: ByteArray, contentType: String,
        extraCookie: String = "", referer: String = ""
    ): ByteArray {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Content-Type", contentType)
                val ck = joinCookie(cookie, extraCookie)
                if (ck.isNotEmpty()) setRequestProperty("Cookie", ck)
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                doOutput = true
            }
            conn.outputStream.use { it.write(body) }
            return decodeStream(conn, conn.inputStream)
        } catch (e: Exception) {
            Logger.e("Http", "POST请求失败 $url: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    /** GET 字节流 */
    fun getBytes(
        url: String, method: String = "GET", body: ByteArray? = null,
        extraCookie: String = "", referer: String = "", range: String = "", timeoutMs: Int = 30000,
        ua: String = userAgent
    ): ByteArray {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                val ck = joinCookie(cookie, extraCookie)
                if (ck.isNotEmpty()) setRequestProperty("Cookie", ck)
                val ref = if (referer.isNotEmpty()) referer
                else if (url.contains("api.bilibili.com") || url.contains("bilivideo")) "https://www.bilibili.com/" else ""
                if (ref.isNotEmpty()) setRequestProperty("Referer", ref)
                if (range.isNotEmpty()) setRequestProperty("Range", range)
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            return decodeStream(conn, conn.inputStream)
        } catch (e: Exception) {
            Logger.e("Http", "请求失败 $url: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun decodeStream(conn: HttpURLConnection, raw: InputStream): ByteArray {
        val code = conn.responseCode
        val stream: InputStream = if (code in 200..399) raw else conn.errorStream ?: raw
        val encoding = conn.contentEncoding ?: ""
        val decoded: InputStream = when (encoding) {
            "gzip" -> GZIPInputStream(stream)
            "deflate" -> InflaterInputStream(stream)
            else -> stream
        }
        return decoded.use { it.readBytes() }
    }

    private fun joinCookie(base: String, extra: String): String {
        val parts = mutableListOf<String>()
        if (base.trim().isNotEmpty()) parts.add(base.trim().trim(';').trim())
        if (extra.trim().isNotEmpty()) parts.add(extra.trim().trim(';').trim())
        return parts.joinToString("; ")
    }
}
