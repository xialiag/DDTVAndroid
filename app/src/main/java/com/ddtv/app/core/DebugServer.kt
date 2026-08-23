package com.ddtv.app.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Enumeration
import java.util.Locale

/**
 * 本地调试服务器:同一 WiFi 下浏览器访问 http://<手机IP>:19864/ 实时查看运行状态。
 * 面向"调试时实时检测"设计:
 *  - /                HTML 实时面板(JS 自动轮询刷新,不用手动刷新)
 *  - /json            全量状态 JSON(登录双态/房间/录制/设置/统计 + 日志水位)
 *  - /logs?tail=200   最近日志;?after=<seq> 增量日志(长轮询:无新日志最多挂起 15 秒,
 *                    有日志立即返回,适合脚本/助手持续跟踪)
 *  - /logs?clear=1    清空内存日志;?export=1 下载当日日志文件(附件)
 *  - /crash           崩溃日志列表 JSON(name/size/mtime);?view=<name> 查看;
 *                    ?delete=<name> 删除(日志保存管理,BBDownAndroid 同款)
 *  - /room?roomId=    单房间详情
 *  - /stats           统计 JSON
 *  - /stream          SSE 实时流(日志事件 + 每 5 秒房间心跳),浏览器 EventSource 消费
 */
object DebugServer {
    const val PORT = 19864
    private const val POLL_HOLD_MS = 15_000L

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val lock = Any()

    /** 缓存本机局域网 IP（监听前探测一次，供日志/面板/设置卡片显示） */
    @Volatile private var localAddr: String = ""

    /**
     * 获取本机局域网 IPv4 地址（优先 wlan0/eth0 等真实网络接口，回退 loopback）。
     * 拿不到返回空串，用于调试服务器启动时把可访问地址打印出来，免去手动查手机 IP。
     */
    fun localIp(): String {
        if (localAddr.isNotEmpty()) return localAddr
        try {
            val addrs = NetworkInterface.getNetworkInterfaces()
            var anySite: String? = null
            while (addrs.hasMoreElements()) {
                val nif = addrs.nextElement() ?: continue
                val name = nif.name ?: continue
                // 跳过回环/未激活/虚拟网卡，优先真实 WiFi/有线
                if (!nif.isUp || nif.isLoopback) continue
                if (name.startsWith("lo") || name.contains("dummy") || name.contains("docker")) continue
                val en = nif.inetAddresses
                while (en.hasMoreElements()) {
                    val ia = en.nextElement()
                    if (ia is Inet4Address && !ia.isLoopbackAddress) {
                        val host = ia.hostAddress ?: continue
                        // 优先 wlan/eth 等真实接口；记录第一个候选兜底
                        if (anySite == null) anySite = host
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("rndis") || name.startsWith("ap"))
                            return host.also { localAddr = it }
                    }
                }
            }
            if (anySite != null) localAddr = anySite
        } catch (_: Exception) {
        }
        return localAddr
    }

    /** 给 UI/日志用的可访问基址（有局域网 IP 用 IP，否则回退 127.0.0.1） */
    fun accessUrl(): String = "http://" + (localIp().ifEmpty { "127.0.0.1" }) + ":$PORT/"

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            Thread({
                try {
                    server = ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0"))
                    // 提前探测局域网 IP，启动日志直接给出可访问地址，免去手动查手机 IP
                    val url = accessUrl()
                    Logger.i("Debug", "调试服务器已启动: 端口 $PORT（本机浏览器 $url ；同一WiFi电脑/助手访问同上）")
                    while (running) {
                        val sock = server?.accept() ?: break
                        Thread({ handle(sock) }, "DebugConn").apply { isDaemon = true; start() }
                    }
                } catch (e: Exception) {
                    // 主动 stop() 会 close ServerSocket 使 accept 抛 SocketException,属正常关闭
                    if (running) Logger.w("Debug", "调试服务器异常退出: ${e.message}")
                } finally {
                    running = false
                }
            }, "DebugServer").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        Logger.i("Debug", "调试服务器已关闭")
    }

    private fun handle(sock: java.net.Socket) {
        try {
            sock.soTimeout = 30_000
            val line = sock.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return
            val parts = line.split(" ")
            val path = parts.getOrNull(1) ?: "/"
            val query = path.substringAfter('?', "")
            val route = path.substringBefore('?')

            when {
                route == "/stream" -> sseLoop(sock)
                route == "/json" -> respond(sock, statusJson(), "application/json")
                route == "/logs" -> {
                    if (query.contains("clear=1")) {
                        Logger.clear()
                        Logger.i("Debug", "调试面板清空了内存日志")
                        respond(sock, """{"ok":true,"msg":"内存日志已清空(文件日志保留)"}""", "application/json")
                    } else if (query.contains("export=1")) {
                        val f = Logger.currentLogFile()
                        if (f != null) {
                            respondFile(sock, f, "ddtv_log_${SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())}.txt")
                        } else {
                            respond(sock, "暂无日志文件(今日尚未写入日志)", "text/plain")
                        }
                    } else {
                        respond(sock, logsJson(query), if (query.contains("view=")) "text/plain" else "application/json")
                    }
                }
                route == "/crash" -> respond(sock, crashRoute(query), if (query.contains("view=")) "text/plain" else "application/json")
                route == "/room" -> respond(sock, roomJson(query), "application/json")
                route == "/stats" -> respond(sock, RoomManager.getStats().toString(), "application/json")
                else -> respond(sock, statusHtml(), "text/html")
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun respond(sock: java.net.Socket, body: String, ct: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sock.getOutputStream().use { out ->
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: $ct; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            out.write(bytes)
        }
    }

    /** 以附件形式下发日志文件（浏览器触发下载） */
    private fun respondFile(sock: java.net.Socket, f: java.io.File, downloadName: String) {
        try {
            val bytes = f.readBytes()
            sock.getOutputStream().use { out ->
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Disposition: attachment; filename=\"$downloadName\"\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
                out.write(bytes)
            }
        } catch (e: Exception) {
            respond(sock, "读取日志文件失败: ${e.message}", "text/plain")
        }
    }

    /** SSE 实时流:日志事件 + 房间心跳 */
    private fun sseLoop(sock: java.net.Socket) {
        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.flush()
        var seq = Logger.maxSeq()
        // 先推一次全量状态
        out.write("event: state\ndata: ${statusJson().replace("\n", "")}\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
        var lastBeat = System.currentTimeMillis()
        while (running && !sock.isClosed) {
            try {
                val logs = Logger.since(seq)
                if (logs.isNotEmpty()) {
                    seq = logs.last().seq
                    logs.forEach { l ->
                        out.write("event: log\ndata: {\"seq\":${l.seq},\"time\":${l.time},\"level\":\"${l.level}\",\"msg\":\"${esc(l.msg)}\"}\n\n".toByteArray(Charsets.UTF_8))
                    }
                    out.flush()
                }
                val now = System.currentTimeMillis()
                if (now - lastBeat >= 5000) {
                    lastBeat = now
                    out.write("event: beat\ndata: {\"t\":$now,\"rooms\":${roomBriefJson()}}\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                Thread.sleep(500)
            } catch (_: Exception) {
                break
            }
        }
    }

    // ===== 数据 =====

    private fun statusJson(): String {
        val am = AccountManager
        val rooms = RoomManager.getRooms()
        val sb = StringBuilder()
        sb.append("{\"ts\":").append(System.currentTimeMillis())
            .append(",\"version\":\"").append(com.ddtv.app.BuildConfig.VERSION_NAME).append('"')
            .append(",\"activeType\":\"web\"")
            .append(",\"logSeq\":").append(Logger.maxSeq())
            .append(",\"accessUrl\":\"").append(esc(accessUrl())).append('"')
            .append(",\"web\":").append(accountJson(am.getWebAccount()))
        // 房间
        sb.append(",\"rooms\":[")
        rooms.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"roomId\":${r.roomId},\"name\":\"${esc(r.name)}\",\"liveStatus\":${r.liveStatus},")
                .append("\"title\":\"${esc(r.title)}\",\"recState\":\"${r.recState}\",\"recMode\":\"${r.recMode}\",")
                .append("\"recSize\":${r.recSize},\"recSpeed\":${r.recSpeed},\"recStartTime\":${r.recStartTime},")
                .append("\"livePopularity\":${r.livePopularity},\"danmakuCount\":${r.danmakuCount},")
                .append("\"danmakuOpen\":${r.danmakuOpen},\"autoRecord\":${r.autoRecord},")
                .append("\"lastError\":\"${esc(r.lastError)}\"}")
        }
        sb.append(']')
        // 录制汇总
        val rec = rooms.filter { it.recState == "recording" }
        sb.append(",\"recordingCount\":").append(rec.size)
        // 设置
        val s = RoomManager.settings
        sb.append(",\"settings\":{\"pollInterval\":${s.pollInterval},\"autoRecordDefault\":${s.autoRecordDefault},")
            .append("\"remuxAfterLive\":${s.remuxAfterLive},\"watchHeartbeat\":${s.watchHeartbeat},")
            .append("\"outputDir\":\"${esc(RoomManager.outputDir.path)}\"}")
        sb.append('}')
        return sb.toString()
    }

    private fun roomJson(query: String): String {
        val id = query.substringAfter("roomId=", "").substringBefore('&').toLongOrNull() ?: 0L
        val r = RoomManager.getRoom(id) ?: return "{\"error\":\"room not found\"}"
        return "{\"roomId\":${r.roomId},\"shortId\":${r.shortId},\"uid\":${r.uid},\"name\":\"${esc(r.name)}\"," +
            "\"title\":\"${esc(r.title)}\",\"liveStatus\":${r.liveStatus},\"liveTime\":${r.liveTime}," +
            "\"areaName\":\"${esc(r.areaName)}\",\"livePopularity\":${r.livePopularity}," +
            "\"autoRecord\":${r.autoRecord},\"quality\":${r.quality},\"danmakuOpen\":${r.danmakuOpen},\"remind\":${r.remind},\"audioOnly\":${r.audioOnly}," +
            "\"recState\":\"${r.recState}\",\"recMode\":\"${r.recMode}\",\"recFile\":\"${esc(r.recFile)}\"," +
            "\"recSize\":${r.recSize},\"recSpeed\":${r.recSpeed},\"recStartTime\":${r.recStartTime}," +
            "\"danmakuCount\":${r.danmakuCount},\"lastError\":\"${esc(r.lastError)}\"," +
            "\"files\":[${r.files.joinToString(",") { "\"${esc(it)}\"" }}]}"
    }

    private fun roomBriefJson(): String {
        val rooms = RoomManager.getRooms()
        val sb = StringBuilder("[")
        rooms.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"roomId\":${r.roomId},\"liveStatus\":${r.liveStatus},\"recState\":\"${r.recState}\"}")
        }
        sb.append(']')
        return sb.toString()
    }

    private fun logsJson(query: String): String {
        // ?view=<name>:历史日志文件内容; ?delete=<name>:删除历史日志; ?list=1:历史文件列表
        val view = query.substringAfter("view=", "").substringBefore('&').take(200)
        if (view.isNotEmpty()) {
            if (!view.matches(Regex("ddtv_[0-9]+\\.log"))) return "非法文件名"
            val dir = Logger.logDir() ?: return "日志目录不存在"
            val f = java.io.File(dir, view)
            if (!f.exists()) return "历史日志不存在: $view"
            return try {
                // 超过 2MB 只读末尾 2MB，避免一次性读爆内存
                if (f.length() > 2L * 1024 * 1024) {
                    f.inputStream().use { ins ->
                        ins.skip(f.length() - 2L * 1024 * 1024)
                        ins.readBytes().toString(Charsets.UTF_8)
                    }
                } else f.readText(Charsets.UTF_8)
            } catch (e: Exception) { "读取失败: ${e.message}" }
        }
        val del = query.substringAfter("delete=", "").substringBefore('&').take(200)
        if (del.isNotEmpty()) {
            if (!del.matches(Regex("ddtv_[0-9]+\\.log"))) return """{"ok":false,"msg":"非法文件名"}"""
            val dir = Logger.logDir() ?: return """{"ok":false,"msg":"日志目录不存在"}"""
            val f = java.io.File(dir, del)
            if (f.exists()) {
                f.delete()
                Logger.i("Debug", "已删除历史日志: $del")
                return """{"ok":true,"deleted":"$del"}"""
            }
            return """{"ok":false,"msg":"文件不存在"}"""
        }
        if (query.contains("list=1")) {
            val dir = Logger.logDir()
            val sb = StringBuilder("[")
            val files = dir?.listFiles { f -> f.name.startsWith("ddtv_") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            files.forEachIndexed { i, f ->
                if (i > 0) sb.append(',')
                sb.append("{\"name\":\"${f.name}\",\"size\":${f.length()},\"mtime\":${f.lastModified()}}")
            }
            return sb.append(']').toString()
        }
        // ?after=<seq>:增量 + 长轮询(最多挂起 15s);?tail=<n>:最近 n 条
        val after = query.substringAfter("after=", "").substringBefore('&').toLongOrNull()
        if (after != null) {
            val deadline = System.currentTimeMillis() + POLL_HOLD_MS
            while (System.currentTimeMillis() < deadline) {
                val logs = Logger.since(after)
                if (logs.isNotEmpty()) return logsArray(logs)
                Thread.sleep(800)
            }
            return "[]"
        }
        val tail = (query.substringAfter("tail=", "").substringBefore('&').toIntOrNull() ?: 200).coerceIn(1, 500)
        return logsArray(Logger.recent(tail))
    }

    private fun logsArray(logs: List<Logger.LogLine>): String {
        val sb = StringBuilder("[")
        logs.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append("{\"seq\":${l.seq},\"time\":${l.time},\"level\":\"${l.level}\",\"msg\":\"${esc(l.msg)}\"}")
        }
        sb.append(']')
        return sb.toString()
    }

    // ===== 崩溃日志管理 =====

    private fun crashDir(): java.io.File? = Logger.logDir()

    private fun crashList(): List<java.io.File> {
        val dir = crashDir() ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** /crash 路由：无参=列表 JSON；view=<name>=内容纯文本；delete=<name>=删除 */
    private fun crashRoute(query: String): String {
        val view = query.substringAfter("view=", "").substringBefore('&')
        if (view.isNotEmpty()) {
            val f = crashList().firstOrNull { it.name == view } ?: return "崩溃日志不存在: $view"
            return try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "读取失败: ${e.message}" }
        }
        val del = query.substringAfter("delete=", "").substringBefore('&')
        if (del.isNotEmpty()) {
            val f = crashList().firstOrNull { it.name == del }
            if (f == null) {
                return """{"ok":false,"msg":"崩溃日志不存在: $del"}"""
            }
            if (f.delete()) {
                Logger.i("Debug", "已删除崩溃日志: ${f.name}")
                return """{"ok":true,"msg":"已删除 ${f.name}"}"""
            }
            return """{"ok":false,"msg":"删除失败: $del"}"""
        }
        val sb = StringBuilder("[")
        crashList().forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":\"${f.name}\",\"size\":${f.length()},\"mtime\":${f.lastModified()}}")
        }
        sb.append(']')
        return sb.toString()
    }

    private fun accountJson(acc: AccountInfo?): String {
        if (acc == null) return "{\"logged\":false}"
        return "{\"logged\":true,\"uid\":${acc.uid},\"uname\":\"${esc(acc.uname)}\"," +
            "\"face\":\"${esc(acc.face)}\",\"level\":${acc.level}}"
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    // ===== HTML 实时面板 =====

    private fun statusHtml(): String {
        return """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DDTV 实时调试面板</title>
<style>
body{font-family:system-ui,sans-serif;background:#1e1e1e;color:#ccc;margin:0;padding:16px;font-size:13px}
h1{font-size:18px;color:#FB7299;margin:0 0 4px}
.sub{color:#969696;font-size:12px;margin-bottom:12px}
h2{font-size:13px;color:#bbb;border-bottom:1px solid #333;padding-bottom:6px;margin:18px 0 10px}
.card{background:#252526;border:1px solid #333;border-radius:8px;padding:12px 16px;margin-bottom:8px}
.row{display:flex;justify-content:space-between;gap:12px;padding:3px 0;font-size:13px}
.row .k{color:#969696;flex:0 0 auto}.row .v{color:#e8e8e8;word-break:break-all;text-align:right}
.ok{color:#89d185}.off{color:#f48771}.dim{color:#969696}.live{color:#f48771;font-weight:600}
.rec{color:#FB7299;font-weight:600}
.log{font-family:monospace;font-size:11px;line-height:1.6;background:#1b1b1f;border-radius:6px;padding:10px;height:280px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
.log .warn{color:#d7ba7d}.log .error{color:#f48771}.log .debug{color:#6cc7f0}
.crash-item{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:8px 12px;background:#252526;border:1px solid #333;border-radius:6px;margin-bottom:6px;font-size:12px}
.crash-item a{color:#FB7299;text-decoration:none}
.btn{display:inline-block;background:#3a3a3d;color:#e8e8e8;border:1px solid #4a4a4e;border-radius:6px;padding:4px 12px;font-size:12px;cursor:pointer;text-decoration:none}
.btn:hover{background:#4a4a4e}
a{color:#FB7299}
.badge{display:inline-block;padding:1px 8px;border-radius:8px;font-size:11px;margin-left:6px}
.badge.on{background:#1f3a1f;color:#89d185}.badge.off{background:#3a1f1f;color:#f48771}
</style></head><body>
<h1>DDTV 实时调试面板 <span id="ver"></span></h1>
<div class="sub">自动每 2 秒刷新 · 接口: <a href="/json">/json</a> <a href="/logs">/logs</a> <a href="/logs?after=0">/logs?after=</a> <a href="/crash">/crash</a> <a href="/room?roomId=21452505">/room</a> <a href="/stats">/stats</a> <a href="/stream">/stream(SSE)</a></div>
<h2>登录态</h2>
<div class="card" id="loginCard">加载中…</div>
<h2>录制状态</h2>
<div class="card" id="recCard">加载中…</div>
<h2>房间</h2>
<div id="roomList">加载中…</div>
<h2>崩溃日志 <span id="crashCount" class="badge off"></span></h2>
<div id="crashList">加载中…</div>
<h2>历史日志 <span id="histCount" class="badge off"></span></h2>
<div id="histList">加载中…</div>
<h2>实时日志 <span id="logState" class="badge off">连接中</span></h2>
<div class="log" id="logBox"></div>
<div style="margin-top:8px"><button class="btn" onclick="clearLogs()">清空日志</button> <a class="btn" href="/logs?export=1">下载日志</a></div>
<script>
let logSeq = 0, logBox, first = true;
function esc(s){return String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
function stateHtml(s){
  const acc = a => a&&a.logged ? '<span class="ok">'+esc(a.uname)+' (UID '+a.uid+')</span>' : '<span class="off">未登录</span>';
  document.getElementById('ver').textContent = 'v'+s.version;
  document.getElementById('loginCard').innerHTML =
    '<div class="row"><span class="k">登录方式</span><span class="v">扫码登录(网页版)</span></div>'+
    '<div class="row"><span class="k">当前账号</span><span class="v">'+acc(s.web)+'</span></div>'+
    (s.accessUrl?'<div class="row"><span class="k">本机地址</span><span class="v">'+esc(s.accessUrl)+'</span></div>':'');
  document.getElementById('recCard').innerHTML =
    '<div class="row"><span class="k">监控房间</span><span class="v">'+s.rooms.length+'</span></div>'+
    '<div class="row"><span class="k">录制中</span><span class="v '+(s.recordingCount?'ok':'off')+'">'+s.recordingCount+'</span></div>'+
    '<div class="row"><span class="k">轮询间隔</span><span class="v">'+s.settings.pollInterval+'s</span></div>'+
    '<div class="row"><span class="k">录制目录</span><span class="v">'+esc(s.settings.outputDir)+'</span></div>';
  document.getElementById('roomList').innerHTML = s.rooms.map(r=>{
    const st = r.liveStatus===1?'<span class="live">● 直播中</span>':r.liveStatus===2?'<span class="live">● 轮播中</span>':'<span class="dim">未开播</span>';
    const rec = r.recState==='recording'?'<span class="rec">● 录制中</span>':'<span class="dim">空闲</span>';
    const err = r.lastError?'<div class="row"><span class="k">错误</span><span class="v off">'+esc(r.lastError)+'</span></div>':'';
    return '<div class="card"><div class="row"><span class="k">'+esc(r.name)+'</span><span class="v">'+st+' '+rec+'</span></div>'+
      '<div class="row"><span class="k">roomId</span><span class="v">'+r.roomId+'</span></div>'+
      '<div class="row"><span class="k">人气/弹幕</span><span class="v">'+r.livePopularity+' / '+r.danmakuCount+'</span></div>'+
      (r.recState==='recording'?'<div class="row"><span class="k">录制</span><span class="v">'+fmtSize(r.recSize)+' @ '+fmtSize(r.recSpeed)+'/s</span></div>':'')+err+'</div>';
  }).join('');
}
function fmtSize(b){if(!b)return'0 B';const u=['B','KB','MB','GB','TB'];let i=0;while(b>=1024&&i<4){b/=1024;i++;}return b.toFixed(i?1:0)+' '+u[i];}
async function pollState(){ try{ const s=await (await fetch('/json')).json(); stateHtml(s); }catch(e){} }
async function pollCrash(){
  try{
    const arr=await (await fetch('/crash')).json();
    const cnt=document.getElementById('crashCount');
    if(cnt){ cnt.textContent=arr.length?'共 '+arr.length+' 条':''; cnt.className='badge'+(arr.length?' on':' off'); }
    const box=document.getElementById('crashList'); if(!box) return;
    if(!arr.length){ box.innerHTML='<div class="dim">暂无崩溃日志</div>'; return; }
    box.innerHTML=arr.map(c=>{
      const d=new Date(c.mtime);
      const ts=d.getFullYear()+'-'+('0'+(d.getMonth()+1)).slice(-2)+'-'+('0'+d.getDate()).slice(-2)+' '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);
      return '<div class="crash-item"><a href="/crash?view='+esc(c.name)+'" target="_blank">'+esc(c.name)+'</a>'+
        '<span class="dim">'+ts+' · '+fmtSize(c.size)+'</span>'+
        '<button class="btn" onclick="delCrash(\''+esc(c.name).replace(/\'/g,"\\'")+'\')">删除</button></div>';
    }).join('');
  }catch(e){}
}
async function delCrash(name){
  const r=await (await fetch('/crash?delete='+encodeURIComponent(name))).json();
  pollCrash();
}
async function pollHist(){
  try{
    const arr=await (await fetch('/logs?list=1')).json();
    const cnt=document.getElementById('histCount');
    if(cnt){ cnt.textContent=arr.length?'共 '+arr.length+' 个':''; cnt.className='badge'+(arr.length?' on':' off'); }
    const box=document.getElementById('histList'); if(!box) return;
    if(!arr.length){ box.innerHTML='<div class="dim">暂无历史日志(今日日志自动落盘后出现)</div>'; return; }
    box.innerHTML=arr.map(c=>{
      const d=new Date(c.mtime);
      const ts=d.getFullYear()+'-'+('0'+(d.getMonth()+1)).slice(-2)+'-'+('0'+d.getDate()).slice(-2)+' '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);
      return '<div class="crash-item"><a href="/logs?view='+esc(c.name)+'" target="_blank">'+esc(c.name)+'</a>'+
        '<span class="dim">'+ts+' · '+fmtSize(c.size)+'</span>'+
        '<button class="btn" onclick="delHist(\''+esc(c.name).replace(/\'/g,"\\'")+'\')">删除</button></div>';
    }).join('');
  }catch(e){}
}
async function delHist(name){
  const r=await (await fetch('/logs?delete='+encodeURIComponent(name))).json();
  pollHist();
}
async function clearLogs(){
  await fetch('/logs?clear=1');
  document.getElementById('logBox').innerHTML='';
  logSeq=0;
}
async function pollLogs(){
  try{
    const arr=await (await fetch('/logs?after='+logSeq)).json();
    if(arr.length){ logSeq=arr[arr.length-1].seq; const box=document.getElementById('logBox');
      arr.forEach(l=>{ const d=new Date(l.time), ts=('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2);
        const div=document.createElement('div'); div.className=l.level;
        div.textContent='['+ts+']['+l.level+'] '+l.msg; box.appendChild(div); });
      while(box.children.length>300) box.removeChild(box.firstChild);
      box.scrollTop=box.scrollHeight;
      document.getElementById('logState').textContent='实时'; document.getElementById('logState').className='badge on';
    }
  }catch(e){ document.getElementById('logState').textContent='断开'; document.getElementById('logState').className='badge off'; }
}
setInterval(pollState, 2000); setInterval(pollLogs, 2000); setInterval(pollCrash, 5000); setInterval(pollHist, 5000);
pollState(); pollLogs(); pollCrash();
</script>
</body></html>"""
    }
}
