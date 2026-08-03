# AGENTS.md — DDTV Android 开发行为指南

本文件是给 AI 编码代理(以及任何协作者)的行为规范。改代码前先读它。

## 项目定位

把 B 站直播录制工具 [CHKZL/DDTV](https://github.com/CHKZL/DDTV)(C#/.NET)移植到 Android 的原生应用。
UI 采用 **VSCode 风格 + B站粉(#FB7299)** 主题,架构参考 [xialiag/BBDownAndroid](https://github.com/xialiag/BBDownAndroid)。

**移植策略:功能对齐原版 DDTV,UI/架构对齐 BBDownAndroid。**

## 硬性架构约束(不可违反)

1. **UI 必须是 WebView + HTML/CSS/JS**,全部 UI 逻辑在 `app/src/main/assets/` 三件套里
   (`index.html` / `app.css` / `app.js`)。**禁止**引入 Jetpack Compose、XML Fragment、RecyclerView 等原生 UI。
2. **JS ↔ Kotlin 通过 `DDTVBridge.kt` 通信**:JS 侧 `callBridge()` 发起调用,Kotlin 侧 `@JavascriptInterface` 处理;Kotlin 主动推送用 `__onBridge` 回调。新增功能遵循该模式,不要绕过桥直接操作 DOM。
3. 网络请求、文件 IO、FFmpeg、WebSocket、持久化全部在 Kotlin 侧(core 包),JS 只做渲染与交互。
4. 后台录制用 `LiveService.kt` 前台服务(mediaPlayback),不要自行起裸 Service 或 WorkManager。

## 需求来源与对应模块

原版 DDTV(`/root/DDTV-src/DDTV-master`)模块 → 本项目实现,新增/修改功能先查这张表:

| 原版 DDTV 模块 | Android 实现 |
|---|---|
| `Core/Network/` 开播检测 `DetectRoom.cs` | `core/RoomManager.kt`:批量 `get_status_info_by_uids` 轮询,首轮强制触发已开播房间,重入保护 |
| `Core/Download/FLV.cs` | `core/LiveRecorder.kt` flvSegment:FLV 直连流 Append 写入,重试3次指数退避,断流查状态 |
| `Core/Download/HLS.cs` | `core/LiveRecorder.kt` hlsSegment:m3u8 增量分片下载,二级 m3u8、ENDLIST 收尾 |
| `Core/Download/Basics.cs` | Auto 模式 HLS 优先降级 FLV;标题/时长/大小分割;主播重推流切分;小文件清理 |
| `Core/Tools/Transcode.cs` | `core/FFmpegRemux.kt`:ffmpeg-kit `-c copy` 转封装 flv→mp4 |
| `Core/LiveChat/LiveChatListener.cs` | `core/DanmakuClient.kt`:弹幕/礼物/SC/舰长/续费/进场 + 发送弹幕(需登录) |
| `Core/Account/` 扫码登录 | `core/AccountManager.kt` + `QrCodeUtil.kt`:ZXing 生成 B站粉二维码 + Cookie 粘贴登录 |
| `Core/Network/Methods/Follow.cs` | 关注分组拉取 + 批量导入监控 |
| `WatchHeartbeatManager.cs` + `HmacChain.cs` | `core/WatchHeartbeat.kt`:x25Kn E/X 心跳(链式 HMAC,纯 Kotlin SHA-224) |
| `Core/Download/Cover.cs` | 每段录制保存封面 cover.jpg |
| `Core/LogModule/log.cs` | `core/Logger.kt`:内存环形缓冲 + UI 实时推送 |

原版源码在本地 `/root/DDTV-src/DDTV-master/`,接口细节、字段名、业务逻辑对不上时以原版为准。

## UI 规范(VSCode 风格 + B站粉,简洁美观)

- **布局仿 VSCode**:标题栏 → 活动栏(左侧图标列)→ 侧边栏(资源管理器)+ 编辑器(标签页 + 内容)→ 状态栏。
- **主色 #FB7299(B站粉)**,用于状态栏、选中态、活动图标高亮;其余遵循 VSCode 深色/浅色调色板。
- 保持**简洁美观**:一屏一个焦点,不做花哨动效、不加多余边框阴影;控件密度、字号、间距参照 B站粉主题的 DDTV_GUI_React 观感。
- 手机窄屏（<992px，含横屏）不显示侧边栏：列表内容融入编辑器（上列表 + 下详情两段式）；宽屏（≥992px）侧边栏常驻显示。点击当前活动图标 = 重新渲染当前视图（手动刷新）。
- 深色/浅色/跟随系统三档主题,标题栏右侧切换。
- **9 个活动视图,新增视图必须挂进这个体系**:监控 / 弹幕 / 文件 / 统计 / 历史 / 工具 / 账号 / 设置 / 日志(关注列表并入账号页下方)。
- UI 文案用中文,与全站一致。

### 卡片样式规范(沿用 BBDownAndroid,新增列表一律用这两种卡片)

1. **任务卡片 `.task-item`**(用于直播监控房间列表):
   - 有封面时:封面 `<img class="ti-bg">` 铺满卡片背景(绝对定位 + `referrerpolicy="no-referrer"`,onerror 时移除自身并去掉父元素 `has-cover` 类)、磨砂边缘 `.ti-shade`、左上角序号 `.ti-index`(#N)、右上角 REC 徽标 `.ti-rec`、底部标题 `.ti-title`(白字+阴影)与状态行 `.ti-sub`(live-dot + 直播中/轮播中/未开播 + 人气 + 录制速度)、副标题 `.ti-sub2`(直播标题,截断)。
   - 无封面 fallback:`has-cover` 类不出现 → 卡片背景用 `var(--card-bg)` + 边框,文字用前景色无阴影,内容垂直居中。
   - 选中态:`selected` 类 → 粉色 inset 描边(inset box-shadow 2px accent),不要用背景高亮。
   - 排序:直播中+轮播中在前,未开播在后。
2. **统一视频卡片 `.vc-item`**(用于录制文件列表、录制历史等所有"视频条目"列表):
   - 左侧封面 `.vc-cover-wrap`(120×72,窄屏 <992px 缩为 88×56)+ 占位 `.vc-cover-ph`(无封面时显示 FLV/MP4 或图标文字)、可选右下角时长角标 `.vc-duration`;
   - 右侧 `.vc-body`:标题 `.vc-title`(单行截断)+ meta 行 `.vc-meta`(大小/时间/格式)+ 可选 `.vc-date`。
   - 容器用 `.vc-list`(flex column, gap 10px);选中/高亮用 `vc-checked` 类(粉色边框+淡粉背景),工具面板选文件也用这个类。
- 这两种卡片样式定义在 `app.css` 末尾,从 BBDownAndroid 移植;改样式时以 BBDownAndroid 为准,保持视觉统一。

## 构建与验证

```bash
./build-apk.sh        # release → dist/DDTV-<version>-release.apk
./build-apk.sh debug  # debug
```

- 产物在 `dist/`,构建脚本自动做 AAR 反斜杠路径修复、native 库自检、签名验证;脚本失败别绕过,先查原因。
- 本机(Linux ARM64)SDK 在 `/opt/android-sdk`(Platform 33 + Build-Tools 34.0.0),`local.properties` 必须指向它;Windows 环境见 DEV_ENV_NOTES.md(代理 127.0.0.1:7897)。
- 多版本同时构建会互相清 APK,**构建完立即把产物拷到 `dist/`**(脚本已做,别手改输出路径)。
- 改完代码必须 `./build-apk.sh debug` 编译通过再交付;涉及录制的改动说明测试方式(无真机时至少保证编译 + 逻辑审查)。
- 每次发布:versionCode/versionName 递增,更新 README 的功能表和已知限制。

## 已知坑(改相关代码必读)

- **数据中心 IP 风控**:弹幕接口 `getDanmuInfo` 在数据中心 IP 下返回 -352;App 已注入官方 buvid3/buvid4 指纹,`core/Wbi.kt` / `BiliLiveApi.kt` 里不要移除,新增 B 站接口也尽量带 buvid。
- **付费直播录制需要登录**;小心心挂机(x25Kn)需要登录,连续失败 3 次自动停止。
- 弹幕协议 protover=3 用 brotli 压缩,解压依赖 `org.brotli:dec`,别换协议版本。
- FFmpegKit AAR 是本地 `libs/ffmpeg-kit-full-v8.aar`(FFmpeg 8.1.2,非 GPL 构建,无 libx264;transcode 用内置 mpeg4)。同源构建在 BBDownAndroid(`libs/ffmpeg-kit-full-v8.aar` + `ffmpeg-8.1.2-src/` + `ffmpeg-kit-src/`),需要重新生成时去那边找构建链。AAR 内 jni 条目是正斜杠,无需反斜杠修复;Java API 包名仍为 `com.arthenica.ffmpegkit`,与 v6 兼容。

## 代码风格

- Kotlin,与现有 core 包风格一致:顶层函数 + 数据类,注释中文,关键业务逻辑写清缘由。
- 新增文件放 `app/src/main/java/com/ddtv/app/core/`,桥接方法加进 `DDTVBridge.kt`,JS 侧在 `app.js` 统一封装。
- 别做与任务无关的重构;改动前先 `git status` 确认工作区状态。
- 敏感信息(密码、Cookie、密钥)不得写进代码或文档,日志里脱敏。
