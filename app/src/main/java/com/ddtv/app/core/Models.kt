package com.ddtv.app.core

import org.json.JSONObject

/** 录制模式（对应 DDTV Config.RecordingMode） */
enum class RecordingMode(val id: String, val label: String) {
    AUTO("auto", "自动（HLS优先，降级FLV）"),
    FLV_ONLY("flv", "仅FLV"),
    HLS_ONLY("hls", "仅HLS")
}

/** 全局设置（对应 DDTV Config.Core_RunConfig） */
data class AppSettings(
    var pollInterval: Int = 15,          // 轮询间隔秒
    var recordMode: String = "flv",     // RecordingMode.id: flv/hls/auto
    var defaultQuality: Int = 10000,     // 默认清晰度
    var autoRecordDefault: Boolean = true,   // 新房间默认自动录制
    var splitByTitle: Boolean = false,   // 标题变化分割
    var splitSeconds: Long = 0,          // 按时长分割(秒, 0=关)
    var splitSizeMB: Long = 0,           // 按大小分割(MB, 0=关)
    var minFileSizeMB: Long = 0,         // 自动清理阈值:小于该大小的文件删除(0=关)
    var remuxAfterLive: Boolean = true,  // 结束后转封装 mp4
    var watchHeartbeat: Boolean = false, // 小心心挂机
    var saveCover: Boolean = true,       // 保存封面(每段录制存 cover.jpg)
    var remindLive: Boolean = true,      // 开播/下播系统通知提醒
    var blockBarrage: String = "",       // 弹幕屏蔽词，| 分隔（对应原版 _BlockBarrageList）
    var fileNameFormat: String = "",     // 自定义文件名格式(关键字替换, 空=默认)
    var repairDeleteSource: Boolean = true,  // 修复/转码成功后删除源文件(对齐原版 _DeleteOriginalFileAfterRepair)
    var flvAppendOnReconnect: Boolean = true, // FLV 断流重连后同文件续写(原版 Append 行为);关=断流即切新文件分段
    var debugServer: Boolean = false,    // 调试服务器开关(19864 端口,状态/日志查看;默认关,防局域网他人访问)
    var keepScreenOn: Boolean = false,   // 屏幕常亮(设置页开关,FLAG_KEEP_SCREEN_ON)
    var updateRepo: String = "xialiag/DDTVAndroid",  // 自动更新源 GitHub owner/repo(空=不检查)
    var autoUpdate: Boolean = false,      // 启动时静默检查更新
    var autoStart: Boolean = true,       // 开机自启:重启后自动恢复直播监控前台服务
    var danmakuSrt: Boolean = false,     // 录制结束后自动生成弹幕字幕
    var danmakuSubFormat: String = "srt",  // 结束自动字幕格式: srt|ass
    var subSpeed: String = "normal",     // ASS弹幕滚动速度: slow|normal|fast
    var subFontSize: Int = 26,           // 字幕字号(22/26/30)
    var subTracks: Int = 6,              // 弹幕轨道数(4/6/8)
    var subShowName: Boolean = true,     // 字幕显示昵称
    var subContentAll: Boolean = true,   // 全部内容(弹幕+礼物+SC+上舰);false=仅普通弹幕
    var subWhiteColor: Boolean = false,  // 统一白色;false=跟随B站原色
    var subFont: String = "",            // 自定义字体(空=默认 Microsoft YaHei)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pollInterval", pollInterval)
        put("recordMode", recordMode)
        put("defaultQuality", defaultQuality)
        put("autoRecordDefault", autoRecordDefault)
        put("splitByTitle", splitByTitle)
        put("splitSeconds", splitSeconds)
        put("splitSizeMB", splitSizeMB)
        put("minFileSizeMB", minFileSizeMB)
        put("remuxAfterLive", remuxAfterLive)
        put("watchHeartbeat", watchHeartbeat)
        put("saveCover", saveCover)
        put("remindLive", remindLive)
        put("blockBarrage", blockBarrage)
        put("fileNameFormat", fileNameFormat)
        put("repairDeleteSource", repairDeleteSource)
        put("flvAppendOnReconnect", flvAppendOnReconnect)
        put("debugServer", debugServer)
        put("keepScreenOn", keepScreenOn)
        put("updateRepo", updateRepo)
        put("autoUpdate", autoUpdate)
        put("autoStart", autoStart)
        put("danmakuSrt", danmakuSrt)
        put("danmakuSubFormat", danmakuSubFormat)
        put("subSpeed", subSpeed)
        put("subFontSize", subFontSize)
        put("subTracks", subTracks)
        put("subShowName", subShowName)
        put("subContentAll", subContentAll)
        put("subWhiteColor", subWhiteColor)
        put("subFont", subFont)
    }
}

/** 直播间卡片信息（对应 DDTV RoomInfo.RoomCardClass） */
data class RoomCard(
    var roomId: Long = 0,          // 真实房间号
    var shortId: Long = 0,         // 短号
    var uid: Long = 0,             // 主播 UID
    var name: String = "",         // 主播名
    var face: String = "",         // 主播头像
    var sign: String = "",         // 主播签名
    var title: String = "",        // 直播间标题
    var cover: String = "",        // 封面
    var liveStatus: Int = 0,       // 0未开播 1直播中 2轮播
    var liveStatusPrev: Boolean = false,  // 上一轮是否开播（状态机用）
    var liveTime: Long = 0,        // 开播时间
    var areaId: Long = 0,          // 分区 id
    var areaName: String = "",     // 分区名
    var popularity: Long = 0,      // 人气
    // 房间级配置
    var autoRecord: Boolean = true,
    var quality: Int = 10000,
    var danmakuOpen: Boolean = true,
    var remind: Boolean = true,          // 开播/下播提醒（对应原版 IsRemind）
    var audioOnly: Boolean = false,      // 仅录制音频（media_type=1 纯音频流，省流量）
    var cutSeconds: Long = 0,      // 房间级按时长分割(0=用全局)
    var cutSizeMB: Long = 0,       // 房间级按大小分割(0=用全局)
    // 运行态
    var recState: String = "idle", // idle/recording/stopping
    var recMode: String = "",      // 当前录制模式 flv/hls
    var recFile: String = "",      // 当前录制文件
    var recSize: Long = 0,
    var recSpeed: Long = 0,
    var recStartTime: Long = 0,
    var livePopularity: Long = 0,  // 弹幕通道人气
    var danmakuCount: Int = 0,
    var lastError: String = "",
    var files: MutableList<String> = mutableListOf(),  // 本次直播文件
    var manualStop: Boolean = false,  // 手动停止录制标记：直播不断时轮询不自动重启（自动录制开关仍生效于下轮开播）
) {
    /**
     * 录制目录名：主播真名；名字为空或仍为占位（"房间 X"/"RoomX"）时统一用 Room<id>。
     * 占位名"房间 X"绝不进入目录命名——否则 migratePlaceholderFolder 会因名字以"房间 "开头
     * 永远跳过迁移，占位目录就永远改不回真名。真名刷出后由迁移逻辑把 Room<id> 改名为真名目录。
     */
    fun dirName(): String {
        val n = name.trim()
        return if (n.isEmpty() || n.startsWith("房间 ") || n.startsWith("Room")) "Room$roomId" else n
    }
}

/** 弹幕消息 */
data class DanmakuItem(
    var roomId: Long = 0,
    var type: String = "DANMU_MSG",  // DANMU_MSG/SEND_GIFT/SUPER_CHAT_MESSAGE/GUARD_BUY/GUARD_RENEW/INTERACT_WORD
    var user: String = "",
    var uid: Long = 0,
    var content: String = "",
    var time: Long = System.currentTimeMillis(),
    var color: Int = 0,
    var extra: String = "",
    var isAdmin: Boolean = false,
)

/** 录制文件记录 */
data class RecordFileInfo(
    var name: String = "",
    var path: String = "",
    var size: Long = 0,
    var mtime: Long = 0,
    var uploader: String = "",
    var isFlv: Boolean = true,
)

/** 录制历史记录（对应原版 Detect.History：主播名/时间/标题） */
data class HistoryItem(
    var name: String = "",
    var time: String = "",      // yyyy-MM-dd HH:mm:ss
    var title: String = "",
    var roomId: Long = 0,
    var fileCount: Int = 0,
)

/** 按 UP 名搜索直播间结果（x/web-interface/search/type, search_type=live_user） */
data class SearchLiveUser(
    var roomId: Long = 0,        // 房间号（0 = 未开播过/无房间）
    var uid: Long = 0,           // 主播 UID
    var uname: String = "",      // 主播名
    var face: String = "",       // 头像 URL
    var liveStatus: Int = 0,     // 0未开播 1直播中 2轮播
    var title: String = "",      // 直播间标题
    var online: Long = 0,        // 人气
    var shortId: Long = 0,       // 短号
)

/** 登录账号信息 */
data class AccountInfo(
    var uid: Long = 0,
    var uname: String = "",
    var face: String = "",
    var level: Int = 0,
    var coin: Double = 0.0,
    var cookie: String = "",
    var csrf: String = "",
) {
    // 参照 BBDownAndroid：有 cookie 即视为登录态（SESSDATA 在即可用），uid 仅用于展示/兜底
    val isLoggedIn: Boolean get() = cookie.isNotEmpty()
}

object Json {
    fun obj(o: JSONObject, key: String, def: String = ""): String =
        if (o.has(key) && !o.isNull(key)) o.optString(key, def) else def
    fun objLong(o: JSONObject, key: String, def: Long = 0): Long =
        if (o.has(key) && !o.isNull(key)) o.optLong(key, def) else def
    fun objInt(o: JSONObject, key: String, def: Int = 0): Int =
        if (o.has(key) && !o.isNull(key)) o.optInt(key, def) else def
    fun objBool(o: JSONObject, key: String, def: Boolean = false): Boolean =
        if (o.has(key) && !o.isNull(key)) o.optBoolean(key, def) else def
}
