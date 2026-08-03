package com.ddtv.app.core

import java.security.MessageDigest

/** B站 WBI 签名（来自 BBDownAndroid） */
object Wbi {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    fun extractKeyFromUrl(url: String): String {
        return url.substringAfterLast('/').substringBefore('.')
    }

    /** 注意：mixinKey = 按表重排 img_key+sub_key 后取前32位，不是 md5 */
    fun getMixinKey(imgKey: String, subKey: String): String {
        val s = imgKey + subKey
        val sb = StringBuilder()
        for (i in 0 until 32) {
            sb.append(s[MIXIN_KEY_ENC_TAB[i]])
        }
        return sb.toString()
    }

    fun sign(query: String, mixinKey: String): String {
        val md5 = MessageDigest.getInstance("MD5")
        val digest = md5.digest(("$query$mixinKey").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }
}
