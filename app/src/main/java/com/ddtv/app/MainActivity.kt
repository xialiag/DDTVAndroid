package com.ddtv.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ddtv.app.core.AccountManager
import com.ddtv.app.core.DanmakuItem
import com.ddtv.app.core.DebugServer
import com.ddtv.app.core.FFmpegRemux
import com.ddtv.app.core.FFmpegRepair
import com.ddtv.app.core.LiveRecorder
import com.ddtv.app.core.Logger
import com.ddtv.app.core.RoomManager

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: DDTVBridge

    companion object {
        private const val REQ_NOTIFICATION = 1003
        private const val REQ_BATTERY = 1004

        /** SAF 文件选择回调（修复工具等用）：把选中的文件复制到应用私有目录并回调 JS */
        @Volatile var filePickCallback: ((String?) -> Unit)? = null

        @Volatile private var filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
        /** 由 Activity 注册 SAF launcher（onCreate 中调用） */
        fun registerFilePicker(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
            filePickerLauncher = launcher
        }

        /** 打开系统文件选择器（Bridge 调用） */
        fun pickFileFromSystem(mimeType: String) {
            filePickerLauncher?.launch(arrayOf(mimeType.ifBlank { "*/*" }))
        }

    }

    /** SAF 文件选择（OpenDocument，用户可从文件管理器/最近/下载等选任意文件） */
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            filePickCallback?.invoke(null)
            return@registerForActivityResult
        }
        try {
            // 复制到应用私有目录（FFmpegKit 需要真实文件路径，且避免长期持 URI 权限）
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        Thread({
            var copied: String? = null
            try {
                val displayName = queryDisplayName(uri) ?: "picked_${System.currentTimeMillis()}"
                val ext = displayName.substringAfterLast('.', "mp4")
                val dest = java.io.File(cacheDir, "picked_${System.currentTimeMillis()}.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                copied = dest.absolutePath
                Logger.i("Bridge", "SAF 文件已复制: $copied")
            } catch (e: Exception) {
                Logger.w("Bridge", "SAF 文件复制失败: ${e.message}")
            }
            runOnUiThread { filePickCallback?.invoke(copied) }
        }, "SafCopy").apply { isDaemon = true; start() }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#1E1E1E")

        // 初始化核心
        RoomManager.init(this)
        AccountManager.init(this)
        Logger.init(this)
        // 全局崩溃捕获（BBDownAndroid 同款：崩溃日志写 logs/crash_*.txt，DebugServer/设置页可查看）
        CrashHandler.install(this)
        // 调试服务器默认关闭，设置页可开启（默认关避免局域网他人访问）
        if (RoomManager.settings.debugServer) DebugServer.start()

        // 注册 SAF 文件选择器（修复工具用）
        MainActivity.registerFilePicker(filePicker)

        // 先建 WebView 与桥（setupWebView 内部依赖 bridge，顺序不能反）
        setupWebView()
        bridge = DDTVBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        AccountManager.listener = object : AccountManager.Listener {
            override fun onLoginStateChanged(account: com.ddtv.app.core.AccountInfo?) {
                bridge.pushAccount()
                if (account != null) bridge.pushLog(0, "info", "登录成功: ${account.uname}")
            }
            override fun onQrcodeUpdated(imageData: String?, message: String) {
                bridge.pushQrcode(imageData, message)
            }
        }

        // 权限：通知（Android 13+）
        requestNotificationPermission()
        requestBatteryExemption()

        // 挂接事件 → JS
        RoomManager.addListener(uiRoomListener)
        LiveRecorder.addListener(uiRecorderListener)
        Logger.listener = object : Logger.Listener {
            override fun onLog(line: Logger.LogLine) {
                bridge.pushLog(line.roomId, line.level, line.msg)
            }
        }

        // 启动前台服务
        startService(Intent(this, LiveService::class.java))

        // 启动时打印 ffmpeg 引擎版本（调试日志：确认打包的是 v8 还是 v6）
        Thread({
            try {
                // 用 FFmpegKitConfig 直读运行时版本（BBDownAndroid 同款 API）；execute("-version") 解析输出在部分构建下为空
                val ff = com.arthenica.ffmpegkit.FFmpegKitConfig.getFFmpegVersion()
                val kit = com.arthenica.ffmpegkit.FFmpegKitConfig.getVersion()
                Logger.i("FFmpeg", "FFmpeg $ff (kit $kit)")
            } catch (e: Exception) {
                Logger.w("FFmpeg", "获取 ffmpeg 版本失败: ${e.message}")
            }
        }, "FfmpegVersion").apply { isDaemon = true; start() }

        // 启动后自动检查更新（需已配置 GitHub 仓库且开启开关；等 WebView 就绪再推事件）
        webView.postDelayed({
            val s = RoomManager.settings
            if (s.autoUpdate && s.updateRepo.isNotBlank()) {
                bridge.checkUpdate(s.updateRepo)
            }
        }, 5000)

        // 返回键：先关闭编辑器页面栈（VSCode 式标签页），栈空则退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bridge.currentPageDepth() > 0) {
                    webView.evaluateJavascript("window.__back && window.__back()", null)
                } else {
                    finish()
                }
            }
        })
    }

    private val uiRoomListener = object : RoomManager.Listener {
        override fun onRoomsChanged() = bridge.pushRoomsChanged()
        override fun onRoomUpdate(roomId: Long) = bridge.pushRoomUpdate(roomId)
        override fun onLiveStart(roomId: Long) {
            bridge.pushRoomUpdate(roomId)
            bridge.pushLog(roomId, "info", "检测到开播")
        }
        override fun onLiveEnd(roomId: Long) {
            bridge.pushRoomUpdate(roomId)
            bridge.pushLog(roomId, "info", "⏹ 直播结束")
        }
        override fun onDanmakuEvent(item: DanmakuItem) = bridge.pushDanmaku(item)
        override fun onDanmakuStatus(roomId: Long, connected: Boolean, msg: String) = bridge.pushDanmakuStatus(roomId, connected, msg)
        override fun onLog(roomId: Long, level: String, msg: String) = bridge.pushLog(roomId, level, msg)
        override fun onHeartbeatLog(msg: String) = bridge.pushLog(0, "info", msg)
    }

    private val uiRecorderListener = object : LiveRecorder.Listener {
        override fun onStateChange(roomId: Long, state: String, file: String) {
            bridge.pushRoomUpdate(roomId)
            bridge.pushLog(roomId, "info", when (state) {
                "recording" -> "开始录制: ${file.substringAfterLast('/')}"
                "idle" -> "录制已停止"
                else -> state
            })
        }
        override fun onProgress(roomId: Long, size: Long, speed: Long) {
            bridge.pushRoomUpdate(roomId)
        }
        override fun onSegmentEnd(roomId: Long, file: String) {
            bridge.pushRoomUpdate(roomId)
            bridge.pushLog(roomId, "info", "分段完成: ${file.substringAfterLast('/')}")
        }
        override fun onLiveEnded(roomId: Long, files: List<String>, reason: String) {
            bridge.pushRoomUpdate(roomId)
            bridge.pushLog(roomId, "info", "本次直播共录制 ${files.size} 个文件")
            // 录制历史（对应原版 RecEndEvent + HistoryPage）
            RoomManager.getRoom(roomId)?.let { card ->
                RoomManager.recordHistory(card, files)
            }
            // 直播结束 → 转封装/修复（flv 优先 remux，mp4 直接 repair 修截断尾部）
            if (RoomManager.settings.remuxAfterLive) {
                // 仅录音频模式：段结束已提取 m4a；残留 flv 是提取失败品，不再转无视频流的 mp4
                val audioOnly = RoomManager.getRoom(roomId)?.audioOnly == true
                files.filter { !(audioOnly && it.endsWith(".flv")) }.forEach { f ->
                    Thread({
                        var out: String? = null
                        if (f.endsWith(".flv")) {
                            out = FFmpegRemux.remux(f, roomId)
                            if (out == null) {
                                // FLV 尾部截断常见：用 repair 模式忽略错误兜底
                                Logger.w("Main", "转封装失败，尝试容错修复: ${f.substringAfterLast('/')}")
                                bridge.pushLog(roomId, "warn", "转封装失败，尝试容错修复…")
                                out = FFmpegRepair.repair(f, "repair")
                            }
                        } else if (f.endsWith(".mp4")) {
                            // HLS 拼接的 fmp4：停止时尾部可能不完整，用 repair 生成标准 mp4
                            out = FFmpegRepair.repair(f, "repair")
                        }
                        bridge.pushLog(roomId, if (out != null) "info" else "warn",
                            if (out != null) "转封装完成: ${out.substringAfterLast('/')}"
                            else "转封装失败: ${f.substringAfterLast('/')}")
                        bridge.pushToJs("""{"type":"files_changed"}""")
                    }, "Remux-$roomId").start()
                }
            }
        }
        override fun onError(roomId: Long, error: String) {
            bridge.pushLog(roomId, "error", "录制错误: $error")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        // addJavascriptInterface 与 loadUrl 在 onCreate 中 bridge 赋值后调用
        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = WebChromeClient()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            }
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences("ddtv_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("batteryExemptAsked", false)) return
        prefs.edit().putBoolean("batteryExemptAsked", true).apply()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        // 录制需要后台持续，服务独立于 Activity 生命周期
        super.onDestroy()
    }
}
