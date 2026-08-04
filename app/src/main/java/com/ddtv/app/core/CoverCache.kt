package com.ddtv.app.core

/**
 * 封面图片磁盘缓存：
 * - 网络封面/头像（房间 cover/face）→ cacheDir/covers/<md5(url)>.img，WebView 经 FileProvider 加载
 * - 音频嵌入封面（m4a 元数据 embeddedPicture）→ 同目录 <md5(路径|mtime)>.img，0 字节 = 无封面负缓存
 * 目标：录制文件列表/房间列表秒出封面，不依赖网络重复拉取；失败 30s 内不重试。
 */
object CoverCache {
    @Volatile private var dir: java.io.File? = null
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val negative = java.util.concurrent.ConcurrentHashMap<String, Long>()  // key -> 失败时间戳

    fun init(context: android.content.Context) {
        dir = java.io.File(context.cacheDir, "covers").apply { mkdirs() }
    }

    private fun md5(s: String): String {
        return try {
            val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            d.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            s.hashCode().toString()
        }
    }

    /** 缓存文件路径（不存在也会返回，调用方自行判断） */
    fun cacheFile(key: String): java.io.File? {
        val d = dir ?: return null
        return java.io.File(d, md5(key) + ".img")
    }

    /** 命中的网络封面缓存文件，未命中返回 null（不发起下载） */
    fun cachedFile(url: String): java.io.File? {
        if (url.isBlank() || !url.startsWith("http")) return null
        val f = cacheFile(url) ?: return null
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 原子写缓存文件（temp + rename，避免 WebView 读到半截文件） */
    fun writeCache(key: String, bytes: ByteArray): java.io.File? {
        val f = cacheFile(key) ?: return null
        return try {
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(f) || f.exists()) f else null
        } catch (_: Exception) { null }
    }

    /** 异步下载并缓存网络图片；完成回调 onDone(File?)；并发去重 + 失败负缓存 */
    fun cacheAsync(url: String, onDone: ((java.io.File?) -> Unit)? = null) {
        if (url.isBlank() || !url.startsWith("http")) return
        if (cachedFile(url) != null) return
        val now = System.currentTimeMillis()
        if (now - (negative[url] ?: 0) < 30_000) return
        if (!inflight.add(url)) return
        Thread({
            try {
                val bytes = Http.getBytes(url, referer = "https://www.bilibili.com/", timeoutMs = 15000)
                if (bytes.isNotEmpty()) onDone?.invoke(writeCache(url, bytes)) else onDone?.invoke(null)
            } catch (e: Exception) {
                negative[url] = System.currentTimeMillis()
                onDone?.invoke(null)
            } finally {
                inflight.remove(url)
            }
        }, "CoverCache").apply { isDaemon = true; start() }
    }

    /** 缓存文件 → content:// URI（WebView 可加载） */
    fun uriFor(context: android.content.Context, f: java.io.File): String? {
        return try {
            androidx.core.content.FileProvider.getUriForFile(context, "com.ddtv.app.fileprovider", f).toString()
        } catch (_: Exception) { null }
    }
}
