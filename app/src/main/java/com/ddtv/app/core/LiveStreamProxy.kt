package com.ddtv.app.core

import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 边录边播本地流代理：把正在录制的直播流(Recorder 已读到、正写入文件的字节)
 * 同时转发给"在线听直播"的播放器，**不再单独去 B 站拉一条流**，避免同一直播间并发取流被限流(403)。
 *
 * 机制：Recorder.flvSegment 每读到一块 buf 就 push 到这里；Listen 播放时用
 * `http://127.0.0.1:19898/live?room=<id>` 拉本地流（ExoPlayer 从代理顺序读取，禁视频轨只播音频）。
 */
object LiveStreamProxy {
    const val PORT = 19898
    private const val VLEN = "9223372036854775807"  // 虚拟 Content-Length: 让播放器按流序读，直到录制结束关闭

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    /** roomId → 当前拉流的客户端输出流（同一房间同时仅一个听众） */
    private val clients = ConcurrentHashMap<Long, OutputStream>()

    /** 标准 FLV 头(9 字节头 + 4 字节 prevTagSize0)，客户端连接时补发，ExoPlayer 才能解析流 */
    private val FLV_HEADER = byteArrayOf(
        'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5, 0, 0, 0, 9,
        0, 0, 0, 0
    )

    @Synchronized
    fun ensureServer() {
        if (thread?.isAlive == true) return
        if (started.get() && server != null) return
        started.set(true)
        thread = Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT), 8)
                server = ss
                Logger.i("StreamProxy", "本地直播流代理已启动 :$PORT")
                while (!ss.isClosed) {
                    try {
                        val sock = ss.accept()
                        Thread({ handle(sock) }, "ProxyConn").apply { isDaemon = true; start() }
                    } catch (e: Exception) {
                        if (ss.isClosed) break
                    }
                }
            } catch (e: Exception) {
                Logger.w("StreamProxy", "代理启动失败: ${e.message}")
                started.set(false)
            }
        }, "StreamProxy").apply { isDaemon = true; start() }
    }

    private fun handle(sock: Socket) {
        var myOut: DataOutputStream? = null
        try {
            // 读 HTTP 请求行，解析 room 参数
            val reader = sock.getInputStream().bufferedReader()
            val reqLine = reader.readLine() ?: return
            val roomId = Regex("room=(\\d+)").find(reqLine)?.groupValues?.get(1)?.toLongOrNull() ?: return
            val out = DataOutputStream(sock.getOutputStream())
            myOut = out
            out.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: video/x-flv\r\n" +
                "Cache-Control: no-cache\r\nConnection: keep-alive\r\nContent-Length: $VLEN\r\n\r\n")
            out.flush()
            out.write(FLV_HEADER)   // 补 FLV 头(录制流中途接入无头)
            out.flush()
            clients[roomId] = out
            Logger.d("StreamProxy", "room=$roomId 本地收听接入")
            // 保持连接直到客户端断开(ExoPlayer 停止/录制结束)；读不到即退出
            val inp = sock.getInputStream()
            while (true) { if (inp.read() < 0) break }
        } catch (_: Exception) {
        } finally {
            try { if (myOut != null) clients.entries.removeIf { it.value === myOut } } catch (_: Exception) {}
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** 录制流块推送到正在收听的客户端(无客户端零开销) */
    fun push(roomId: Long, buf: ByteArray, off: Int, len: Int) {
        val c = clients[roomId] ?: return
        try {
            synchronized(c) { c.write(buf, off, len); c.flush() }
        } catch (e: Exception) {
            clients.remove(roomId)
        }
    }

    /** 是否正有本地客户端在收听该房间 */
    fun isActive(roomId: Long): Boolean = clients.containsKey(roomId)

    /** 关闭代理(App 退出/停止时) */
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        started.set(false)
    }
}
