# DDTV Android

按原版 [CHKZL/DDTV](https://github.com/CHKZL/DDTV)（B站直播录制工具）移植到 Android 的原生应用。
UI 为 VSCode 风格 + B站粉（#FB7299）主题，项目结构参考 [xialiag/BBDownAndroid](https://github.com/xialiag/BBDownAndroid)（WebView + Kotlin 桥接）。

## 功能（对应原版模块）

| 原版 DDTV 模块 | Android 移植 |
|---|---|
| `DetectRoom.cs` 开播检测 | `RoomManager`：批量 `get_status_info_by_uids` 轮询，首轮强制触发已开播房间，重入保护 |
| `Download/FLV.cs` | `LiveRecorder.flvSegment`：FLV 直连流 Append 写入，重试3次指数退避，断流查状态 |
| `Download/HLS.cs` | `LiveRecorder.hlsSegment`：m3u8 增量分片下载，二级 m3u8、ENDLIST 收尾 |
| `Download/Basics.cs` | Auto 模式 HLS 优先降级 FLV；标题/时长/大小分割；主播重推流切分；小文件清理 |
| `Tools/Transcode.cs` | `FFmpegRemux`：ffmpeg-kit `-c copy` 转封装 flv→mp4 |
| `LiveChat/LiveChatListener.cs` | `DanmakuClient`：弹幕/礼物/SC/舰长/续费/进场 + 发送弹幕（需登录） |
| `Account/Kernel/ByQRCode.cs` | `AccountManager`：扫码登录（ZXing 生成 B站粉二维码）+ Cookie 粘贴登录 |
| `Network/Methods/Follow.cs` | 关注分组拉取 + 批量导入监控 |
| `WatchHeartbeatManager.cs` + `HmacChain.cs` | `WatchHeartbeat`：x25Kn E/X 心跳（链式 HMAC，含纯 Kotlin SHA-224） |
| `Download/Cover.cs` | 每段录制保存封面 cover.jpg |
| `LogModule/log.cs` | `Logger`：内存环形缓冲 + UI 实时推送 |

## 构建

```bash
./build-apk.sh        # release
./build-apk.sh debug  # debug
```

产物在 `dist/DDTV-<版本>-<类型>.apk`。脚本自动处理 FFmpegKit AAR 反斜杠路径修复、native 库自检、签名验证。
依赖 SDK：`/opt/android-sdk`（Platform 33 + Build-Tools 34.0.0，ARM64 需要替换 x86_64 二进制，见 android-sdk-setup skill）。

## 目录结构

```
app/src/main/
├── assets/
│   ├── index.html          # VSCode 风格 UI（titlebar/activitybar/sidebar/editor tabs/statusbar）
│   ├── app.css             # 深色/浅色双主题 + B站粉（#FB7299），窄屏抽屉式侧边栏
│   └── app.js              # UI 逻辑：10 个活动视图（监控/弹幕/关注/文件/统计/历史/工具/账号/设置/日志）
├── java/com/ddtv/app/
│   ├── MainActivity.kt     # WebView 宿主 + 权限 + 返回键（页面栈深度感知）
│   ├── DDTVBridge.kt       # JS ↔ Kotlin 桥
│   ├── LiveService.kt      # 前台服务（mediaPlayback，后台持续录制）
│   └── core/               # 核心逻辑（见上表）
└── res/                    # 主题资源
```

## UI 说明

- 布局仿 VSCode：标题栏 → 活动栏（左侧图标列）→ 侧边栏（资源管理器）+ 编辑器（标签页 + 内容）→ 状态栏
- 状态栏使用 B站粉 #FB7299，实时显示录制中/监控房间/轮询间隔/登录账号
- 手机窄屏（<768px）下侧边栏自动收起为抽屉：点击当前活动图标展开/收起，选中房间后自动关闭；系统返回键优先收抽屉
- 深色/浅色/跟随系统三档主题，标题栏右侧切换
- v0.6.1：修复 HLS 录制缺 init segment 导致无法播放；录制目录改为文字输入；播放时 FLV 自动转封装；侧边栏断点 991px；点击当前图标刷新视图
- v0.6.0 UI 重构：全面改用设计令牌（app.css 顶部变量）驱动配色，模板内不再有内联样式；图标统一为 SVG 图标集（app.js `ICONS`/`ic()`），替换全部 emoji；配色对齐 VSCode Dark+/Light+ 官方色板

## 已知限制

- 弹幕接口 `getDanmuInfo` 在数据中心 IP 下可能被风控（-352），家庭宽带/移动网络正常；App 已注入官方 buvid3/buvid4 指纹降低概率
- 付费直播录制需要登录
- 小心心挂机（x25Kn）需要登录，连续失败 3 次自动停止
