# DEVELOPMENT.md — DDTV Android 开发文档

面向开发者的架构、机制与发布流程说明。行为规范另见 [AGENTS.md](AGENTS.md)。

## 架构总览

```
┌────────────────────────── WebView ──────────────────────────┐
│  assets/index.html + app.css + app.js   (全部 UI 逻辑)       │
│  JS ↔ 桥: AndroidBridge.xxx() 同步返回 JSON 字符串           │
│  Kotlin 主动推送: pushToJs({type:...}) → window.onNativeEvent │
└──────────────────────────────┬──────────────────────────────┘
                               │ DDTVBridge.kt (@JavascriptInterface)
┌──────────────────────────────┴──────────────────────────────┐
│  core/ 纯 Kotlin 逻辑(网络/录制/FFmpeg/持久化)               │
│  MainActivity(WebView 宿主) · LiveService(前台服务,后台录制) │
└─────────────────────────────────────────────────────────────┘
```

- **UI 全部在 assets 三件套**,禁止引入原生 UI 框架
- 网络/IO/FFmpeg/WebSocket/持久化全部在 Kotlin 侧,JS 只做渲染与交互
- 桥为**同步返回**模式(`@JavascriptInterface fun xxx(): String` 返回 JSON),耗时操作在桥内起线程
- 后台录制必须用 `LiveService` 前台服务(mediaPlayback)

## 目录结构

```
app/src/main/
├── assets/
│   ├── index.html        # VSCode 风格 UI 骨架(标题栏/活动栏/侧边栏/编辑器/状态栏)
│   ├── app.css           # 设计令牌(顶部 :root)+ 深/浅双主题,卡片规范 .task-item/.vc-item
│   └── app.js            # 视图渲染 + 桥调用;ICONS 图标集;10 个活动视图
├── java/com/ddtv/app/
│   ├── MainActivity.kt   # WebView 宿主、权限、启动检查更新/补提取/调试服务器、任务恢复
│   ├── DDTVBridge.kt     # JS↔Kotlin 桥(约 60 个接口)
│   ├── LiveService.kt    # 前台服务:录制进程载体 + 通知(ic_stat_ddtv)
│   ├── CrashHandler.kt   # 全局崩溃捕获 → logs/crash_*.txt(含设备/版本/内存/最近日志)
│   ├── BootReceiver.kt   # 开机自启:BOOT_COMPLETED 拉起 LiveService,尊重设置 auto_start
│   └── core/
│       ├── RoomManager.kt    # 房间 CRUD + 批量轮询 + 录制调度 + 历史/统计 + 弹幕缓冲
│       ├── LiveRecorder.kt   # 录制核心(FLV/HLS 分段、收尾、音频提取、补提取)
│       ├── BiliLiveApi.kt    # B站接口(取流/房间信息/关注/签到),Wbi 签名
│       ├── DanmakuClient.kt  # 弹幕 WebSocket(protover=3 brotli),弹幕/礼物/SC 解析
│       ├── DanmakuExport.kt  # 弹幕→字幕/弹幕(.srt/.ass/.ass弹幕滚动),工具页导出+结束自动生成
│       ├── WatchHeartbeat.kt # x25Kn 观看心跳(HmacChain 链式 HMAC + 纯 Kotlin SHA-224)
│       ├── AccountManager.kt # 扫码登录(web/tv 双态)+ Cookie 粘贴
│       ├── FFmpegRepair.kt   # 修复/转码命令构造 + 错误提取 errorOf()
│       ├── FFmpegRemux.kt    # 转封装 + 音频提取(元数据/封面/三级降级)
│       ├── RepairTaskManager.kt # 修复任务队列(串行 worker)+ 退出持久化
│       ├── Logger.kt         # 内存环形缓冲(500)+ 落盘 ddtv_YYYYMMDD.log(7天)+ seq 增量
│       ├── DebugServer.kt    # 调试服务器(19864)
│       ├── Models.kt         # RoomCard/AppSettings 等数据类
│       └── Http.kt / Wbi.kt / QrCodeUtil.kt
└── res/                   # 图标(自适应 + 旧版 PNG + 通知单色图标)
```

## 核心机制

### 录制链路(LiveRecorder.kt)

```
recordLoop(外层, 取流→选线路→分段)
 ├─ flvSegment: 直连 FLV 流 Append 写入
 │   └─ 断流处理: HTTP 4xx(403)→ 立即 retry_exhausted 换线(不等退避)
 │   │          网络断流 → 指数退避重试(跨段累计 3 次)
 │   │          flvAppendOnReconnect=开 → 同文件续写;关 → 收尾切新文件
 │   └─ 收尾 finishSegment → afterSegmentFinalized(仅音频时提取 m4a)
 └─ hlsSegment: m3u8 增量分片(init 拼接 + 分片下载)
     └─ 连续 5 次失败 → 收尾换线;IOException 按真实直播状态分流(不再一律 live_ended)
```

- **文件命名**:`{输出目录}/{主播名}/{yyyy-MM-dd}/{HH-mm-ss}_{标题}_original.{flv|mp4}`
  - 主播名未就绪时**同步拉一次详情**(`newSegmentFile`),杜绝占位目录
  - 历史占位目录(`房间 <id>`/`Room<id>`)在名字到达后自动迁移/合并(`migratePlaceholderFolder`,录制中不迁,结束立即迁)
- **封面去重**:同目录已有相同大小 `*_cover.jpg` 不重复保存(一次直播一个封面)
- **收尾统一**:`finishSegment`(FLV 先 fixFlvTail 截尾 → 小文件清理 → notifySegmentEnd → afterSegmentFinalized)
- **仅录音频**:`afterSegmentFinalized` → `FFmpegRemux.extractAudio(file, title, artist)`
  - 三级降级:完整提取(封面 attached_pic + 元数据)→ `-err_detect ignore_err` 容错 copy → AAC 192k 重编码
  - 成功删原文件输出 `*_original_audio.m4a`;失败 <256KB 清理、大文件保留
  - 补提取 `extractPendingAudioFiles`:启动 + 每次仅音频录制结束(防抖 60s),扫描残留 `*_original.flv/mp4` 无对应 m4a 的自动提取

### 修复任务(RepairTaskManager + FFmpegRepair)

- 串行 worker 队列(pending→running→done/failed/cancelled),支持取消/重试/删除
- **录制中文件守卫**:`LiveRecorder.isFileBeingRecorded(path)` 在提交、执行、删源三处检查,防止修复删掉正在录制的文件
- `repairDeleteSource` 开关控制成功后删源(同样带录制守卫)
- **退出持久化**:`persistPending`(onDestroy 保存 pending/running)→ `restorePending`(启动恢复,-y 覆盖半成品)
- 命令:repair = `-c copy -err_detect ignore_err`;transcode = `mpeg4 -q:v 3 + aac`(**非 GPL 构建无 libx264**,勿改回);remux = `-c copy +faststart`
- `errorOf()` 提取 ffmpeg 真实错误行(跳过 banner),失败日志可读

### 日志与调试服务器

- `Logger`:内存 500 条环形缓冲(seq 增量)+ **落盘** `logs/ddtv_YYYYMMDD.log`(按天轮转保留 7 天,启动清理)
- `DebugServer`(19864,设置开关默认关):
  - `/` HTML 面板(2s 轮询)、`/json`、`/stream` SSE
  - `/logs?tail=N`、`?after=<seq>` 增量、`?clear=1` 清空、`?list=1` 历史列表、`?view=<name>` 查看(白名单 `ddtv_[0-9]+\.log`,2MB 截断)、`?delete=<name>` 删除
  - `/crash` 崩溃日志列表/查看/删除、`/room?roomId=`、`/stats`
- **面板 JS 转义铁律**:Kotlin 三引号字符串里嵌 JS 字符串,onclick 参数用 `\'`(单反斜杠);改完必须提取 `<script>` 做 `node --check` + python 模拟服务器实测,否则会"加载中"事故

### 房间轮询(RoomManager)

- `pollOnce`:批量 `get_status_info_by_uids`(50/批)→ 逐房间 `applyStatusInfo` + `checkLiveTransition`(开播/下播事件)
- **安全网**:直播中 + autoRecord + 未在录 + 非 manualStop → 自动重启录制(手动停止打 `manualStop` 标记)
- `addCardFromRoomInit` 同步拉 `getRoomDetail` 填名字/标题(room_init 接口不含名字)

### 心跳(WatchHeartbeat)

- x25Kn E/X 双接口,链式 HMAC 签名(纯 Kotlin SHA-224,HmacChain)
- 对拍验证:Java/Python 复刻签名必须一致;真机 1012001 = 签名计算错误(排查 HmacChain/SHA224 K 表)

## 构建与发布

### 构建

```bash
./build-apk.sh            # release, FFmpeg 9(默认)
./build-apk.sh 6 release  # release, FFmpeg 6
./build-apk.sh 8 release  # release, FFmpeg 8
./build-apk.sh all release# 6 + 8 + 9 三版本
```

- 脚本:gradle 传 `-PffmpegVersion`,自动 AAR 自检(native 库数量)+ apksigner 签名验证
- SDK:`/opt/android-sdk`(Platform 33 + Build-Tools 34.0.0,ARM64 需替换 x86_64 二进制)
- FFmpegKit AAR:`app/libs/ffmpeg-kit-full-v{N}.aar`(v6/v8/v9 三版本并存,来自 xialiag/ffmpeg-kit 同源构建链);Java API 包名 `com.arthenica.ffmpegkit`

### 版本号同步点(改版本必查)

| 位置 | 说明 |
|---|---|
| `app/build.gradle` | `versionCode`(递增)+ `versionName` |
| `DDTVBridge.kt` | `currentVersion`(更新检测对比用,必须与 versionName 一致) |
| `app.js` 首行注释 | UI 版本标注(可选) |

### 发布流程

1. 构建三版本:`./build-apk.sh all release`
2. 解包校验三件套:`unzip -o -q dist/DDTV-*.apk "assets/*" && cmp` 与 `app/src/main/assets/` 一致;APK 内 libavcodec.so md5 对照对应版本 AAR
3. 提交推送:`git add -A && git commit && git push origin main`
4. 打 tag:**必须与 versionName 数字一致**(`v0.7.0`,不要带 `-ff8` 后缀,否则更新检测版本比较会把 tag 解析成更新版本误报)
5. GitHub Release:`releases/latest` 语义 → 发布时**包含 v6 + v8 + v9 三个 APK asset**;同名 asset 不能覆盖,**先 GET assets 拿 id DELETE 旧 asset,再 POST 上传**
6. 更新 README 功能表/更新日志

### 发布权限

- 本地 `~/.git-credentials` 的 fine-grained PAT 已含 Releases 权限(建 Release/传 asset 用 **Authorization: Bearer** 头;git push 走 store helper 自动)
- API 建 Release:POST /repos/xialiag/DDTVAndroid/releases(body 中文 changelog);传 asset:POST uploads.github.com(Content-Type: application/octet-stream)
- Bad credentials 说明 token 无 Releases 权限 → 让用户手动建

## 已知坑

- **数据中心 IP 风控**:弹幕 `getDanmuInfo` 返回 -352;`Wbi.kt`/`BiliLiveApi.kt` 的 buvid 指纹勿移除,新增接口尽量带 buvid
- **无 libx264**:ffmpeg-kit-full(非 GPL)没有 x264 编码器,transcode 必须用 `mpeg4`;libavcodec.so 里的 "x264 - core" 字符串只是版本串,不代表编码器可用
- **弹幕 protover=3** 用 brotli,依赖 `org.brotli:dec`,勿换协议版本
- **JS 模板字符串陷阱**:单引号字符串里不能嵌 `${ic(...)}`(整个 app.js 语法错误,页面白屏);改完必须 `node --check`
- **卡片溢出**:列表卡片容器要 `min-width:0`,meta 行 `nowrap`,否则长标题撑爆布局
- **LogLine 是顶层类**(BBDown 里是 `Logger.LogLine`,DDTV 是顶层 `LogLine`),跨项目移植时注意
- **前台服务通知常驻**:更新 APK 后需完全退出重开,通知图标才刷新
- 每次发布:versionCode/versionName 递增,更新 README
