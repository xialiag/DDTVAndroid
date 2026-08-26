package com.ddtv.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.TreeMap

/**
 * B站直播 API 封装（移植自 DDTV Core/Network/Methods/room.cs + User.cs + Follow.cs + Account/Kernel/ByQRCode.cs）
 */
object BiliLiveApi {
    const val MAIN_DOMAIN = "https://api.bilibili.com"
    const val LIVE_DOMAIN = "https://api.live.bilibili.com"
    const val LIVE_WEB_DOMAIN = "https://live.bilibili.com"
    const val PASSPORT_DOMAIN = "https://passport.bilibili.com"
    const val TRACE_DOMAIN = "https://live-trace.bilibili.com"

    // WBI 密钥缓存
    @Volatile private var imgKey = ""
    @Volatile private var subKey = ""
    @Volatile private var mixinKey = ""

    private val wbiLock = Any()

    /** 获取 WBI 密钥（从 nav 接口，免登录也可用） */
    fun ensureWbiKey(): Boolean {
        if (mixinKey.isNotEmpty()) return true
        synchronized(wbiLock) {
            if (mixinKey.isNotEmpty()) return true
            try {
                val body = Http.get("$MAIN_DOMAIN/x/web-interface/nav")
                val obj = JSONObject(body)
                if (obj.optInt("code") != 0) {
                    Logger.w("Api", "nav 获取失败: ${obj.optString("message")}，继续尝试无签名请求")
                    return false
                }
                val wbi = obj.getJSONObject("data").getJSONObject("wbi_img")
                imgKey = Wbi.extractKeyFromUrl(wbi.getString("img_url"))
                subKey = Wbi.extractKeyFromUrl(wbi.getString("sub_url"))
                mixinKey = Wbi.getMixinKey(imgKey, subKey)
                return true
            } catch (e: Exception) {
                Logger.w("Api", "获取 WBI 密钥失败: ${e.message}")
                return false
            }
        }
    }

    /** 对 URL 附加 WBI 签名（wts + w_rid），失败时返回原 URL */
    fun signUrl(baseUrl: String): String {
        try {
            if (!ensureWbiKey()) return baseUrl
            val idx = baseUrl.indexOf('?')
            val params = TreeMap<String, String>()
            if (idx > 0) {
                baseUrl.substring(idx + 1).split("&").forEach { pair ->
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2) params[java.net.URLDecoder.decode(kv[0], "UTF-8")] =
                        java.net.URLDecoder.decode(kv[1], "UTF-8")
                }
            }
            params["wts"] = (System.currentTimeMillis() / 1000).toString()
            val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val wrid = Wbi.sign(query, mixinKey)
            return "${baseUrl.substringBefore('?')}?$query&w_rid=$wrid"
        } catch (e: Exception) {
            Logger.w("Api", "WBI 签名失败: ${e.message}")
            return baseUrl
        }
    }

    // ============ 房间信息 ============

    /**
     * 裸 IP 主机（非域名）：app-room 音频流/部分 web-room 流的 url_info 会混入直接 IP 线路
     * （host=183.232.239.5 等，带 platform=android/uparams 签名参数，实为 PCDN 节点）。
     * 这类节点走 upsig 防盗链网关：带浏览器指纹(UA/Referer)的请求固定 403，
     * 仅 B站 App UA + 无 Referer 放行（实测）。解析时单独收进 PCDN 列表作最后兜底，由录制器换指纹请求。
     */
    private fun isBareIpHost(host: String): Boolean {
        // host 字段带 http:// 前缀：先剥掉 scheme，否则 contains(':') 会把所有 URL 误判成裸 IP
        val h = host.substringAfter("://", host)
        return h.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || h.contains(':')
    }

    /** 房间初始化：解析短号→真实房间号，获取直播状态（免登录） */
    fun roomInit(roomId: Long): JSONObject? {
        return try {
            val body = Http.get("$LIVE_DOMAIN/room/v1/Room/room_init?id=$roomId")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.w("Api", "room_init 失败 room=$roomId: ${obj.optString("message")}")
                return null
            }
            obj.getJSONObject("data")
        } catch (e: Exception) {
            Logger.w("Api", "room_init 异常 room=$roomId: ${e.message}")
            null
        }
    }

    /**
     * 批量查询直播间状态（对应原版 get_status_info_by_uids，按 UID 批量查，一次最多 ~50 个）
     * @return Map<UID, JSONObject(data)>
     */
    fun getStatusInfoByUids(uidList: List<Long>): Map<Long, JSONObject> {
        val result = mutableMapOf<Long, JSONObject>()
        if (uidList.isEmpty()) return result
        try {
            val uids = uidList.filter { it != 0L }
            // 分批，每批 50
            uids.chunked(50).forEach { batch ->
                val body = Http.postJson(
                    "$LIVE_DOMAIN/room/v1/Room/get_status_info_by_uids",
                    "{\"uids\":[${batch.joinToString(",")}]}",
                    referer = LIVE_WEB_DOMAIN
                )
                val obj = JSONObject(body)
                if (obj.optInt("code") == 0) {
                    val data = obj.optJSONObject("data") ?: return@forEach
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val v = data.optJSONObject(key) ?: continue
                        result[key.toLongOrNull() ?: continue] = v
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("Api", "get_status_info_by_uids 异常: ${e.message}")
        }
        return result
    }

    /** 房间详情（标题/封面/在线/主播/分区，免登录） */
    data class RoomDetail(
        val title: String = "",
        val cover: String = "",
        val online: Long = 0,
        val uploader: String = "",
        val area: String = "",
        val areaId: Long = 0,
    )

    fun getRoomDetail(roomId: Long): RoomDetail? {
        return try {
            val body = Http.get("$LIVE_DOMAIN/room/v1/Room/get_info?room_id=$roomId")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) return null
            val d = obj.getJSONObject("data")
            // 新版 get_info 无顶层 uname，主播名在 uploader.uname（部分时段接口两者都缺，返回空由调用方兜底）
            val up = d.optJSONObject("uploader")
            RoomDetail(
                title = Json.obj(d, "title"),
                cover = Json.obj(d, "user_cover").ifEmpty { Json.obj(d, "cover") },
                online = Json.objLong(d, "online"),
                uploader = (up?.optString("uname", "") ?: "").ifEmpty { Json.obj(d, "uname") },
                area = Json.obj(d, "area_name"),
                areaId = Json.objLong(d, "area_id"),
            )
        } catch (e: Exception) {
            Logger.w("Api", "getRoomDetail 异常 room=$roomId: ${e.message}")
            null
        }
    }

    /** getInfoByRoom：分区信息（parent_area_id/area_id，心跳用，需登录） */
    fun getRoomAreaInfo(roomId: Long): Pair<Long, Long>? {
        return try {
            val body = Http.get("$LIVE_DOMAIN/xlive/web-room/v1/index/getInfoByRoom?room_id=$roomId")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) return null
            val info = obj.getJSONObject("data").getJSONObject("room_info")
            Pair(Json.objLong(info, "parent_area_id"), Json.objLong(info, "area_id"))
        } catch (e: Exception) {
            Logger.w("Api", "getRoomAreaInfo 异常 room=$roomId: ${e.message}")
            null
        }
    }

    /** 按 UP 名搜索直播间（search_type=live_user，返回带房间号/UID/头像的结果） */
    fun searchLiveUsers(keyword: String, page: Int = 1): List<SearchLiveUser> {
        val list = mutableListOf<SearchLiveUser>()
        if (keyword.isBlank()) return list
        try {
            val url = "$MAIN_DOMAIN/x/web-interface/search/type?search_type=live_user&page=$page&keyword=" +
                    java.net.URLEncoder.encode(keyword.trim(), "UTF-8")
            val body = Http.get(url, referer = LIVE_WEB_DOMAIN)
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.w("Api", "UP 搜索失败: ${obj.optString("message")}")
                return list
            }
            val result = obj.optJSONObject("data")?.optJSONArray("result") ?: return list
            for (i in 0 until result.length()) {
                val it = result.getJSONObject(i)
                list.add(SearchLiveUser(
                    roomId = it.optLong("roomid", 0),
                    uid = it.optLong("uid", 0),
                    uname = it.optString("uname", ""),
                    face = it.optString("upic", ""),
                    liveStatus = it.optInt("live_status", 0),
                    title = it.optString("title", ""),
                    online = it.optLong("online", 0),
                    shortId = it.optLong("short_id", 0),
                ))
            }
        } catch (e: Exception) {
            Logger.w("Api", "UP 搜索异常: ${e.message}")
        }
        return list
    }

    /** 通过 UID 查直播间（房间号未知时） */
    fun getRoomIdByUid(uid: Long): Long? {
        return try {
            val body = Http.get("$MAIN_DOMAIN/x/relation/stat?vmid=$uid")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) return null
            val roomId = obj.getJSONObject("data").optLong("room_id", 0)
            if (roomId > 0) roomId else null
        } catch (e: Exception) {
            Logger.w("Api", "getRoomIdByUid 异常 uid=$uid: ${e.message}")
            null
        }
    }

    // ============ 直播流 ============

    /** 流地址信息 */
    data class StreamInfo(
        val flvUrl: String = "",
        val hlsUrl: String = "",     // m3u8 地址（http_hls + fmp4 + avc）
        val flvLines: List<String> = emptyList(),  // 全部 FLV CDN 线路（备线切换用）
        val hlsLines: List<String> = emptyList(),  // 全部 HLS CDN 线路
        val flvPcdnLines: List<String> = emptyList(),  // FLV PCDN 线路（裸IP节点，需 App UA 无 Referer 指纹，仅作最后兜底）
        val hlsPcdnLines: List<String> = emptyList(),  // HLS PCDN 线路
        val specialTypes: List<Int> = emptyList(),  // 1=付费直播
        val liveStatus: Int = 0,
    ) {
        val isPaid: Boolean get() = specialTypes.contains(1)
    }

    /**
     * 获取直播流（getRoomPlayInfo，WBI 签名）
     * 同时解析 FLV(http_stream+flv+avc) 与 HLS(http_hls+fmp4+avc)
     * 返回全部 CDN 线路（flvLines/hlsLines），随机取第一条作为主线路，供备线切换使用
     *
     * audioOnly=true 时优先走 App 接口（app-room + media_type=1）拿纯音频流（省流量，逆向自 B站 App 8.98.0）；
     * App 接口失败（无登录态/被拒）回退 web-room。
     */
    fun getStreamInfo(roomId: Long, qn: Int = 10000, audioOnly: Boolean = false): StreamInfo? {
        if (audioOnly) {
            getAudioStreamInfo(roomId, qn)?.let { return it }
            Logger.w("Api", "room=$roomId 纯音频流获取失败，回退常规流")
        }
        return getVideoStreamInfo(roomId, qn)
    }

    /** 常规音视频流（web-room 接口，原逻辑） */
    private fun getVideoStreamInfo(roomId: Long, qn: Int = 10000): StreamInfo? {
        return try {
            val url = signUrl(
                "$LIVE_DOMAIN/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId" +
                        "&protocol=0,1&format=0,1,2&codec=0,1,2&qn=$qn&platform=web&ptype=8"
            )
            val body = Http.get(url, referer = LIVE_WEB_DOMAIN)
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.w("Api", "getRoomPlayInfo 失败 room=$roomId: ${obj.optString("message")}")
                return null
            }
            val data = obj.getJSONObject("data")
            val playUrl = data.optJSONObject("playurl_info")?.optJSONObject("playurl") ?: return null
            val specialTypes = JSONArray2IntList(data.optJSONArray("all_special_types"))
            val flvLines = mutableListOf<String>()
            val hlsLines = mutableListOf<String>()
            val flvPcdnLines = mutableListOf<String>()
            val hlsPcdnLines = mutableListOf<String>()
            val streams = playUrl.optJSONArray("stream") ?: return null
            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                val protocol = Json.obj(stream, "protocol_name")
                if (protocol != "http_stream" && protocol != "http_hls") continue
                val formats = stream.optJSONArray("format") ?: continue
                for (j in 0 until formats.length()) {
                    val fmt = formats.getJSONObject(j)
                    val formatName = Json.obj(fmt, "format_name")
                    val wantFmt = if (protocol == "http_stream") "flv" else "fmp4"
                    if (formatName != wantFmt) continue
                    val codecs = fmt.optJSONArray("codec") ?: continue
                    for (k in 0 until codecs.length()) {
                        val codec = codecs.getJSONObject(k)
                        if (Json.obj(codec, "codec_name") != "avc") continue
                        val urlInfo = codec.optJSONArray("url_info") ?: continue
                        if (urlInfo.length() == 0) continue
                        // 收集全部 CDN 线路（原版 Random.Next 只取一条；这里保留全部供备线切换）。
                        // 裸 IP 主机 = PCDN 节点（upsig 防盗链，仅认 App UA 无 Referer），单独收进 PCDN 列表作最后兜底
                        for (m in 0 until urlInfo.length()) {
                            val info = urlInfo.getJSONObject(m)
                            val host = Json.obj(info, "host")
                            val full = host + Json.obj(codec, "base_url") + Json.obj(info, "extra")
                            val pcdn = isBareIpHost(host)
                            if (protocol == "http_stream") { if (pcdn) flvPcdnLines.add(full) else flvLines.add(full) }
                            else { if (pcdn) hlsPcdnLines.add(full) else hlsLines.add(full) }
                        }
                    }
                }
            }
            Logger.i("Api", "room=$roomId 流解析完成 flv=${flvLines.size}条 hls=${hlsLines.size}条")
            StreamInfo(
                flvUrl = flvLines.firstOrNull() ?: "",
                hlsUrl = hlsLines.firstOrNull() ?: "",
                flvLines = flvLines,
                hlsLines = hlsLines,
                flvPcdnLines = flvPcdnLines,
                hlsPcdnLines = hlsPcdnLines,
                specialTypes = specialTypes,
                liveStatus = Json.objInt(data, "live_status")
            )
        } catch (e: Exception) {
            Logger.w("Api", "getStreamInfo 异常 room=$roomId: ${e.message}")
            null
        }
    }

    /**
     * 纯音频流（App 接口：xlive/app-room/v2/index/getRoomPlayInfo + media_type=1）。
     * 逆向自 B站 App 8.98.0（LiveAudioOnlyWorker → LiveMediaResourceResolver）：
     * media_type 才是音频开关（1=纯音频），ptype 对应 play_type 客户端固定 0，free_type 枚举 0-3。
     * 实测 app-room 需带 App 签名体系参数（platform/build/mobi_app/appkey/ts）否则 -400；
     * access_key 可空。响应结构与 web-room 相同（playurl_info.playurl.stream）。
     * 注：服务端当前即使 media_type=1 仍返回含视频 codec 的流（音频分离或已失效），
     * 纯音频文件由 LiveRecorder 录制后用 FFmpeg extractAudio 提取实现。
     */
        private fun getAudioStreamInfo(roomId: Long, qn: Int = 150): StreamInfo? {
        return try {
            // web-room + ptype=8：B站「仅播声音」关键开关（逆向自 App 8.98.0），
            // 服务端只下发纯音频流（无 video 元素），省流量且 ExoPlayer 稳定播放
            val url = signUrl(
                "$LIVE_DOMAIN/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId" +
                        "&protocol=0,1&format=0,1,2&codec=0,1,2&qn=$qn&platform=web&ptype=8"
            )
            val body = Http.get(url, referer = LIVE_WEB_DOMAIN)
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.w("Api", "音频流 app-room 失败 room=$roomId: ${obj.optString("message")}")
                return null
            }
            val data = obj.getJSONObject("data")
            val playUrl = data.optJSONObject("playurl_info")?.optJSONObject("playurl") ?: return null
            val specialTypes = JSONArray2IntList(data.optJSONArray("all_special_types"))
            val flvLines = mutableListOf<String>()
            val hlsLines = mutableListOf<String>()
            val flvPcdnLines = mutableListOf<String>()
            val hlsPcdnLines = mutableListOf<String>()
            val streams = playUrl.optJSONArray("stream") ?: return null
            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                val protocol = Json.obj(stream, "protocol_name")
                if (protocol != "http_stream" && protocol != "http_hls") continue
                val formats = stream.optJSONArray("format") ?: continue
                for (j in 0 until formats.length()) {
                    val fmt = formats.getJSONObject(j)
                    val formatName = Json.obj(fmt, "format_name")
                    // 音频优先 FLV（App 的 LiveP0PlayUrlSelector audioOnly=1 优先 FLV）；HLS 兼容 fmp4/ts
                    val wantFmt = if (protocol == "http_stream") "flv" else "fmp4"
                    if (formatName != wantFmt && !(protocol == "http_hls" && formatName == "ts")) continue
                    val codecs = fmt.optJSONArray("codec") ?: continue
                    for (k in 0 until codecs.length()) {
                        val codec = codecs.getJSONObject(k)
                        val codecName = Json.obj(codec, "codec_name")
                        if (codecName != "avc" && codecName != "aac") continue
                        val urlInfo = codec.optJSONArray("url_info") ?: continue
                        if (urlInfo.length() == 0) continue
                        for (m in 0 until urlInfo.length()) {
                            val info = urlInfo.getJSONObject(m)
                            val host = Json.obj(info, "host")
                            val full = host + Json.obj(codec, "base_url") + Json.obj(info, "extra")
                            // 裸 IP 主机 = PCDN 节点（upsig 防盗链，仅认 App UA 无 Referer），单独收进 PCDN 列表作最后兜底
                            val pcdn = isBareIpHost(host)
                            if (protocol == "http_stream") { if (pcdn) flvPcdnLines.add(full) else flvLines.add(full) }
                            else { if (pcdn) hlsPcdnLines.add(full) else hlsLines.add(full) }
                        }
                    }
                }
            }
            Logger.i("Api", "room=$roomId 纯音频流解析完成 flv=${flvLines.size}条 hls=${hlsLines.size}条")
            StreamInfo(
                flvUrl = flvLines.firstOrNull() ?: "",
                hlsUrl = hlsLines.firstOrNull() ?: "",
                flvLines = flvLines,
                hlsLines = hlsLines,
                flvPcdnLines = flvPcdnLines,
                hlsPcdnLines = hlsPcdnLines,
                specialTypes = specialTypes,
                liveStatus = Json.objInt(data, "live_status")
            )
        } catch (e: Exception) {
            Logger.w("Api", "getAudioStreamInfo 异常 room=$roomId: ${e.message}")
            null
        }
    }

    private fun JSONArray2IntList(arr: JSONArray?): List<Int> {
        val list = mutableListOf<Int>()
        if (arr == null) return list
        for (i in 0 until arr.length()) list.add(arr.optInt(i))
        return list
    }

    // ============ 弹幕服务器 ============

    data class DanmuServer(val host: String, val wssPort: Int, val token: String)

    fun getDanmuServer(roomId: Long): DanmuServer? {
        return try {
            // WBI 签名（与流解析一致）：getDanmuInfo 无 w_rid 会被风控拒 -352
            // 动态 buvid3 对齐原版 DDTV（每次请求新生成，模拟浏览器行为）
            val url = signUrl(
                "$LIVE_DOMAIN/xlive/web-room/v1/index/getDanmuInfo?id=$roomId&type=0&web_location=444.8"
            )
            val fp = ensureFingerprintCookie().replace(Regex("buvid3=[^;]*"), "buvid3=${freshBuvid()}")
            val body = Http.get(url, extraCookie = fp, referer = LIVE_WEB_DOMAIN)
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.w("Api", "getDanmuInfo 失败 room=$roomId: ${obj.optString("message")}")
                return null
            }
            val data = obj.getJSONObject("data")
            val hosts = data.optJSONArray("host_list") ?: return null
            if (hosts.length() == 0) return null
            val host = hosts.getJSONObject(0)
            DanmuServer(
                host = Json.obj(host, "host"),
                wssPort = Json.objInt(host, "wss_port", 443),
                token = Json.obj(data, "token")
            )
        } catch (e: Exception) {
            Logger.w("Api", "getDanmuServer 异常 room=$roomId: ${e.message}")
            null
        }
    }

    /** 动态 buvid3（对齐原版 DDTV AccountBuvid：UUID + 时间戳 + infoc，每次请求新生成） */
    fun freshBuvid(): String {
        val ts = (System.currentTimeMillis() / 1000).toString().takeLast(5)
        return java.util.UUID.randomUUID().toString().uppercase() + ts + "infoc"
    }

    // ============ 账号/登录（移植 ByQRCode.cs，cookie 提取参照 BBDown BBDownLoginUtil） ============

    // 完整指纹 cookie 缓存（buvid3/buvid4/b_lsid/_uuid/b_nut）
    // 412 风控根因是缺少这些指纹 cookie（BBDown 注释），登录/关键请求带上有助于降低风控概率
    @Volatile private var fingerprintCookie: String? = null

    /**
     * 生成完整 B站指纹 cookie 字符串（buvid3 + buvid4 + b_lsid + _uuid + b_nut）
     * 优先从 SPI API 获取 buvid3/buvid4，失败则本地生成；b_lsid/_uuid/b_nut 本地生成
     */
    fun ensureFingerprintCookie(): String {
        fingerprintCookie?.let { return it }
        val ts = System.currentTimeMillis() / 1000
        val sb = StringBuilder()
        var buvid3 = ""
        var buvid4 = ""
        try {
            val resp = Http.get("$MAIN_DOMAIN/x/frontend/finger/spi")
            val json = JSONObject(resp)
            if (json.optInt("code") == 0) {
                val data = json.optJSONObject("data")
                buvid3 = data?.optString("b_3") ?: ""
                buvid4 = data?.optString("b_4") ?: ""
            }
        } catch (e: Exception) {
            Logger.w("Api", "SPI 获取指纹失败: ${e.message}")
        }
        if (buvid3.isEmpty()) {
            buvid3 = java.util.UUID.randomUUID().toString().uppercase() + "infoc"
        }
        if (buvid4.isEmpty()) {
            val uuid1 = java.util.UUID.randomUUID().toString().uppercase()
            val uuid2 = java.util.UUID.randomUUID().toString().uppercase().replace("-", "").take(16)
            buvid4 = "$uuid1-$uuid2-022062006-"
        }
        val lsid1 = (0 until 8).map { "0123456789ABCDEF"[(Math.random() * 16).toInt()] }.joinToString("")
        val lsid2 = (0 until 8).map { "0123456789ABCDEF"[(Math.random() * 16).toInt()] }.joinToString("")
        val bLsid = "${lsid1}_${lsid2}"
        val uuidPart = java.util.UUID.randomUUID().toString().uppercase()
        val tsHex = ts.toString(16).uppercase()
        val _uuid = "${uuidPart}${tsHex}-${(ts + 1000).toString(16).uppercase()}-1"
        sb.append("buvid3=").append(buvid3)
            .append("; buvid4=").append(buvid4)
            .append("; b_lsid=").append(bLsid)
            .append("; _uuid=").append(_uuid)
            .append("; b_nut=").append(ts)
        fingerprintCookie = sb.toString()
        Logger.d("Api", "生成指纹cookie: buvid3=${buvid3.take(16)}... b_lsid=$bLsid")
        return fingerprintCookie!!
    }

    /** 获取官方 buvid3/buvid4（模拟浏览器指纹，降低风控概率） */
    fun getSpi(): Pair<String, String>? {
        return try {
            val body = Http.get("$MAIN_DOMAIN/x/frontend/finger/spi")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) return null
            val d = obj.optJSONObject("data") ?: return null
            Pair(Json.obj(d, "b_3"), Json.obj(d, "b_4"))
        } catch (e: Exception) {
            Logger.w("Api", "spi 获取失败: ${e.message}")
            null
        }
    }

    /** 生成二维码登录 URL，返回 (url, qrcode_key)。source=main-fe-header 与 BBDown/B站网页端一致 */
    fun qrcodeGenerate(): Pair<String, String>? {
        return try {
            val body = Http.get(
                "$PASSPORT_DOMAIN/x/passport-login/web/qrcode/generate?source=main-fe-header",
                extraCookie = ensureFingerprintCookie(), referer = PASSPORT_DOMAIN
            )
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.w("Api", "qrcode/generate 失败: ${obj.optString("message")}")
                return null
            }
            val d = obj.getJSONObject("data")
            Pair(Json.obj(d, "url"), Json.obj(d, "qrcode_key"))
        } catch (e: Exception) {
            Logger.w("Api", "qrcode/generate 异常: ${e.message}")
            null
        }
    }

    /** 轮询二维码登录状态。返回 (code, message, cookie)。code: 0=登录成功(带cookie) 86090=已扫码待确认 86101=等待扫码 86038=过期 */
    fun qrcodePoll(qrcodeKey: String): Triple<Int, String, String> {
        return try {
            val body = Http.get("$PASSPORT_DOMAIN/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey", referer = PASSPORT_DOMAIN)
            val obj = JSONObject(body)
            val code = obj.optInt("code", -1)
            val msg = obj.optString("message", "")
            val cookie = if (code == 0) {
                // 登录成功：从 Set-Cookie 拿？Http.get 不保留头，改用 getBytes+头解析
                ""
            } else ""
            Triple(code, msg, cookie)
        } catch (e: Exception) {
            Logger.w("Api", "qrcode/poll 异常: ${e.message}")
            Triple(-1, e.message ?: "异常", "")
        }
    }

    /**
     * 登录轮询（BBDown 方式：cookie 从响应体 data.url 的 query 提取 + 响应头 Set-Cookie 补充）。
     * 用单次 HttpURLConnection 同时取 body 与 Set-Cookie 头——登录成功的那次响应头里一定有
     * SESSDATA/DedeUserID（之前用两次独立请求，第二次 poll 时凭证已消费导致 Set-Cookie 为空，
     * 这是网页版登录拿不到 DedeUserID/头像ID 的根源）。
     * 返回 (code, message, cookieStr)。code: 0=登录成功(带cookie) 86090=已扫码待确认 86101=等待扫码 86038=过期
     */
    fun qrcodePollWithCookie(qrcodeKey: String): Triple<Int, String, String> {
        return try {
            val url = "$PASSPORT_DOMAIN/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey&source=main-fe-header"
            var conn: java.net.HttpURLConnection? = null
            val body: String
            val setCookies: List<String>
            try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", Http.userAgent)
                    setRequestProperty("Referer", PASSPORT_DOMAIN)
                    ensureFingerprintCookie().let { if (it.isNotEmpty()) setRequestProperty("Cookie", it) }
                }
                setCookies = conn.headerFields?.filterKeys { it.equals("Set-Cookie", true) }
                    ?.values?.flatten() ?: emptyList()
                body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn?.disconnect()
            }
            val obj = JSONObject(body)
            val data = obj.optJSONObject("data") ?: JSONObject()
            // 状态码以内层 data.code 为准（外层 code 恒为 0 表示请求本身成功）
            val code = Json.objInt(data, "code", -1)
            val msg = Json.obj(data, "message").ifEmpty { obj.optString("message", "") }
            var cookie = ""
            if (code == 0) {
                // BBDown 方式：优先从 crossDomain URL 的 query 参数提取 cookie
                val crossDomainUrl = Json.obj(data, "url")
                cookie = if (crossDomainUrl.contains("?")) {
                    crossDomainUrl.substringAfter("?")
                        .replace("&", ";")
                        .replace(",", "%2C")
                } else ""
                // 本次响应头的 Set-Cookie 是关键来源（登录成功响应必带 SESSDATA/DedeUserID）
                val headerCookies = extractSetCookies(setCookies)
                if (headerCookies.isNotEmpty()) {
                    cookie = mergeCookieStrings(headerCookies, cookie)
                }
                // 后备：个别情况下登录成功的 Set-Cookie 在紧随的另一次 poll 响应中
                if (BiliLiveApi.extractUid(cookie) <= 0) {
                    val retry = qrcodeSetCookies(qrcodeKey)
                    if (retry.isNotEmpty()) cookie = mergeCookieStrings(retry, cookie)
                }
                if (cookie.isEmpty()) {
                    Logger.w("Api", "qrcode/poll 登录成功但未提取到任何 cookie（data.url=$crossDomainUrl）")
                } else {
                    Logger.i("Api", "qrcode/poll 登录成功，提取 cookie: ${cookie.take(80)}...")
                }
            } else {
                Logger.d("Api", "qrcode/poll 状态 code=$code msg=$msg")
            }
            Triple(code, msg, cookie)
        } catch (e: Exception) {
            Logger.w("Api", "qrcode/poll 异常: ${e.message}")
            Triple(-1, e.message ?: "异常", "")
        }
    }

    /** 从 Set-Cookie 响应头列表提取关键登录 cookie */
    private fun extractSetCookies(setCookies: List<String>): String {
        val keep = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3", "buvid4", "b_nut", "buvid_fp", "bili_ticket", "bili_ticket_expires", "b_lsid", "_uuid")
        return setCookies.mapNotNull { sc ->
            val kv = sc.substringBefore(';')
            val name = kv.substringBefore('=')
            if (keep.contains(name)) kv else null
        }.joinToString("; ")
    }

    /** 合并两组 cookie 字符串（后者补充前者缺失的项），去重保序 */
    private fun mergeCookieStrings(a: String, b: String): String {
        val map = linkedMapOf<String, String>()
        listOf(a, b).forEach { s ->
            s.split(";").forEach { kv ->
                val t = kv.trim()
                if (t.isNotEmpty()) {
                    val name = t.substringBefore('=')
                    if (name.isNotEmpty()) map[name] = t
                }
            }
        }
        return map.values.joinToString("; ")
    }

    /** 单独拉取 qrcode/poll 的 Set-Cookie（关键登录 cookie + buvid 指纹补充） */
    private fun qrcodeSetCookies(qrcodeKey: String): String {
        return try {
            val url = "$PASSPORT_DOMAIN/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey&source=main-fe-header"
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", Http.userAgent)
                    setRequestProperty("Referer", PASSPORT_DOMAIN)
                }
                val setCookies = conn.headerFields?.filterKeys { it.equals("Set-Cookie", true) }?.values?.flatten() ?: emptyList()
                extractSetCookies(setCookies)
            } finally {
                conn?.disconnect()
            }
        } catch (e: Exception) {
            ""
        }
    }

    // ============ TV端授权登录（移植 BBDown，cookie 从 cookie_info 数组提取，最可靠） ============

    /** nav 登录态校验结果（三态，移植 BBDownAndroid checkLogin） */
    sealed class NavLoginState {
        /** 明确已登录（nav code=0 且 data.isLogin=true），携带账号资料 */
        data class LoggedIn(val info: AccountInfo) : NavLoginState()

        /** 明确未登录（code!=0 或 data.isLogin=false） */
        object LoggedOut : NavLoginState()

        /** 网络/解析异常，无法判断（不视为失效） */
        object Error : NavLoginState()
    }

    /**
     * 校验登录态并拉取用户信息（nav）。
     * 登录判定依据 data.isLogin —— B站 nav 接口返回的 isLogin 是布尔值 true（不是整数 1），
     * 需兼容两种格式（移植 BBDownAndroid checkLogin 的判定）。
     * @return LoggedIn=已登录（含资料）/ LoggedOut=明确未登录 / Error=网络异常
     */
    fun checkNavLogin(): NavLoginState {
        return try {
            val body = Http.get("$MAIN_DOMAIN/x/web-interface/nav")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) {
                Logger.d("Api", "nav code=${obj.optInt("code")} msg=${obj.optString("message")}，未登录")
                return NavLoginState.LoggedOut
            }
            val d = obj.optJSONObject("data") ?: return NavLoginState.LoggedOut
            val isLogin = d.opt("isLogin")?.toString()?.equals("true", ignoreCase = true) == true ||
                d.optInt("isLogin", 0) == 1
            if (!isLogin) {
                Logger.d("Api", "nav data.isLogin=false，未登录")
                return NavLoginState.LoggedOut
            }
            val money = d.optJSONObject("money") ?: JSONObject()
            NavLoginState.LoggedIn(AccountInfo(
                uid = Json.objLong(d, "mid"),
                uname = Json.obj(d, "uname"),
                face = Json.obj(d, "face"),
                level = d.optJSONObject("level_info")?.optInt("current_level", 0) ?: 0,
                coin = money.optDouble("coin", 0.0),
                cookie = Http.cookie,
                csrf = extractCsrf(Http.cookie),
            ))
        } catch (e: Exception) {
            Logger.w("Api", "nav 异常: ${e.message}")
            NavLoginState.Error
        }
    }

    /** 从 cookie 提取 DedeUserID（uid），失败返回 0 */
    fun extractUid(cookie: String): Long {
        cookie.split(";").forEach { pair ->
            val kv = pair.trim()
            if (kv.startsWith("DedeUserID=")) return kv.substringAfter('=').toLongOrNull() ?: 0L
        }
        return 0L
    }

    /**
     * 用 uid 补拉用户资料（nav 失败时兜底，取 uname/face，WBI 签名）。
     * @return (uname, face)，失败返回 null
     */
    fun getUserInfo(uid: Long): Pair<String, String>? {
        if (uid <= 0) return null
        return try {
            val url = signUrl("$MAIN_DOMAIN/x/space/acc/info?mid=$uid")
            val body = Http.get(url)
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) return null
            val d = obj.optJSONObject("data") ?: return null
            val uname = Json.obj(d, "name")
            val face = Json.obj(d, "face")
            if (uname.isEmpty() && face.isEmpty()) null else Pair(uname, face)
        } catch (e: Exception) {
            Logger.w("Api", "getUserInfo 异常: ${e.message}")
            null
        }
    }

    fun extractCsrf(cookie: String): String {
        cookie.split(";").forEach { pair ->
            val kv = pair.trim()
            if (kv.startsWith("bili_jct=")) return kv.substringAfter('=')
        }
        return ""
    }

    // ============ 关注列表（移植 Follow.cs） ============

    data class FollowGroup(val tagid: Long, val name: String, val count: Int)

    /** 获取关注分组列表（需登录） */
    fun getFollowGroups(): List<FollowGroup> {
        val list = mutableListOf<FollowGroup>()
        try {
            val body = Http.get("$MAIN_DOMAIN/x/relation/tags")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) return list
            val arr = obj.optJSONArray("data") ?: return list
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                list.add(FollowGroup(Json.objLong(g, "tagid"), Json.obj(g, "name"), Json.objInt(g, "count")))
            }
        } catch (e: Exception) {
            Logger.w("Api", "getFollowGroups 异常: ${e.message}")
        }
        return list
    }

    data class FollowUser(val mid: Long, val uname: String, val face: String, val liveStatus: Int, val roomId: Long)

    /** 关注列表缓存（5 分钟，避免反复进入账号页重复全量拉取） */
    @Volatile private var followCache: Pair<Long, List<FollowUser>>? = null
    private const val FOLLOW_CACHE_MS = 5 * 60_000L

    fun getFollowAllCached(uid: Long): List<FollowUser> {
        val c = followCache
        if (c != null && System.currentTimeMillis() - c.first < FOLLOW_CACHE_MS) return c.second
        val list = getFollowAll(uid)
        followCache = System.currentTimeMillis() to list
        return list
    }

    fun clearFollowCache() { followCache = null }

    /** 获取全部关注用户（并行遍历所有分组合并、自动翻页，账号页「全部」用） */
    fun getFollowAll(uid: Long): List<FollowUser> {
        val all = java.util.Collections.synchronizedList(mutableListOf<FollowUser>())
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        val groups = getFollowGroups()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        groups.forEach { g ->
            pool.execute {
                try {
                    var page = 1
                    while (page <= 20) { // 安全上限
                        val list = getFollowList(uid, g.tagid, page, 500)
                        if (list.isEmpty()) break
                        list.forEach { if (seen.add(it.mid)) all.add(it) }
                        if (list.size < 500) break
                        page++
                    }
                } catch (_: Exception) {}
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        Logger.i("Api", "获取全部关注 ${all.size} 人")
        // x/relation/tag 的 live 状态是缓存值（常滞后为 0），用实时接口批量刷新
        return refreshLiveStatus(all.sortedBy { it.mid })
    }

    /**
     * 用 get_status_info_by_uids 批量刷新关注列表的直播状态（实时，并行分批，失败保留原值）。
     * 修复账号页「在播」计数恒为 0 的问题：relation/tag 接口的 live_status 不可靠。
     */
    fun refreshLiveStatus(users: List<FollowUser>): List<FollowUser> {
        if (users.isEmpty()) return users
        val result = java.util.concurrent.ConcurrentHashMap<Long, FollowUser>()
        users.forEach { result[it.mid] = it }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        users.map { it.mid }.filter { it != 0L }.chunked(50).forEach { batch ->
            pool.execute {
                try {
                    val body = Http.postJson(
                        "$LIVE_DOMAIN/room/v1/Room/get_status_info_by_uids",
                        "{\"uids\":[${batch.joinToString(",")}]}",
                        referer = LIVE_WEB_DOMAIN
                    )
                    val obj = JSONObject(body)
                    if (obj.optInt("code") == 0) {
                        val data = obj.optJSONObject("data") ?: return@execute
                        val keys = data.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val v = data.optJSONObject(key) ?: continue
                            val mid = key.toLongOrNull() ?: continue
                            result.computeIfPresent(mid) { _, u ->
                                u.copy(liveStatus = v.optInt("live_status", u.liveStatus), roomId = v.optLong("room_id", u.roomId))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return users.map { result[it.mid] ?: it }
    }

    /** 获取关注分组中的用户列表（pn 从 1 开始，ps 最大 500） */
    fun getFollowList(uid: Long, tagid: Long, page: Int = 1, ps: Int = 500): List<FollowUser> {
        val list = mutableListOf<FollowUser>()
        try {
            val body = Http.get("$MAIN_DOMAIN/x/relation/tag?mid=$uid&tagid=$tagid&pn=$page&ps=$ps")
            val obj = JSONObject(body)
            if (obj.optInt("code") != 0) return list
            val arr = obj.optJSONArray("data") ?: return list
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i)
                val live = u.optJSONObject("live") ?: JSONObject()
                val jumpUrl = Json.obj(live, "jump_url")
                // jump_url 形如 //live.bilibili.com/22625025?visit_id=... 或 https://live.bilibili.com/22625025
                val roomId = Regex("live\\.bilibili\\.com/(\\d+)").find(jumpUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                list.add(FollowUser(
                    mid = Json.objLong(u, "mid"),
                    uname = Json.obj(u, "uname"),
                    face = Json.obj(u, "face"),
                    liveStatus = Json.objInt(live, "live_status"),
                    roomId = roomId,
                ))
            }
        } catch (e: Exception) {
            Logger.w("Api", "getFollowList 异常: ${e.message}")
        }
        return list
    }
}
