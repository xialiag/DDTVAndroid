package com.ddtv.app.core

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 账号管理:单一 Web 扫码登录态(与原版 DDTV ByQRCode 一致)。
 *  - 登录方式只有一种:哔哩哔哩 App 扫码登录网页版
 *  - 曾用 TV 版登录的老用户:启动时自动把 tv_cookie 迁移为 web 登录态(两者 cookie 同构),不掉登录
 *  - account/Http.cookie 指向当前登录态,所有 API 调用不受影响
 */
object AccountManager {

    interface Listener {
        fun onLoginStateChanged(account: AccountInfo?)
        fun onQrcodeUpdated(imageData: String?, message: String)
    }

    @Volatile var listener: Listener? = null

    private lateinit var prefs: SharedPreferences

    // ===== 登录态 =====
    @Volatile var webCookie = ""
    @Volatile var webUname = ""
    @Volatile var webFace = ""
    @Volatile var webUid = 0L
    @Volatile var webLevel = 0

    /** 当前活跃账号(account/Http.cookie 指向它) */
    @Volatile var account: AccountInfo? = null

    fun isLoggedIn(): Boolean = account?.isLoggedIn == true
    fun webLoggedIn(): Boolean = webCookie.isNotEmpty()

    /** WEB 登录态账号(供 UI 显示) */
    fun getWebAccount(): AccountInfo? =
        if (webLoggedIn()) AccountInfo(uid = webUid, uname = webUname, face = webFace, level = webLevel,
            cookie = webCookie, csrf = BiliLiveApi.extractCsrf(webCookie)) else null

    // 扫码登录轮询
    @Volatile private var pollThread: Thread? = null
    private val polling = AtomicBoolean(false)
    @Volatile private var currentKey = ""
    @Volatile private var qrUrl = ""

    /** 当前二维码授权 URL(供"跳转B站确认"使用) */
    fun getCurrentQrUrl(): String = qrUrl

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ddtv_settings", Context.MODE_PRIVATE)
        // 恢复登录态（纯本地读取，同步完成：WebView 加载后 UI 立刻可见）
        webCookie = prefs.getString("web_cookie", "") ?: ""
        webUname = prefs.getString("web_uname", "") ?: ""
        webFace = prefs.getString("web_face", "") ?: ""
        webUid = prefs.getLong("web_uid", 0)
        webLevel = prefs.getInt("web_level", 0)
        // 老版本 TV 登录态迁移：TV cookie 与 Web cookie 同构，直接转为 Web 登录态（原版只有扫码登录）
        if (webCookie.isEmpty()) {
            val tvCookie = prefs.getString("tv_cookie", "") ?: ""
            if (tvCookie.isNotEmpty()) {
                webCookie = tvCookie
                webUname = prefs.getString("tv_uname", "") ?: ""
                webFace = prefs.getString("tv_face", "") ?: ""
                webUid = prefs.getLong("tv_uid", 0)
                webLevel = prefs.getInt("tv_level", 0)
                prefs.edit()
                    .putString("web_cookie", webCookie).putString("web_uname", webUname)
                    .putString("web_face", webFace).putLong("web_uid", webUid).putInt("web_level", webLevel)
                    .remove("tv_cookie").remove("tv_access_token").remove("tv_uname")
                    .remove("tv_face").remove("tv_uid").remove("tv_level").apply()
                Logger.i("Account", "已迁移旧 TV 登录态为 Web 登录态")
            }
        }
        // 清理可能残留的 TV 字段
        prefs.edit().remove("active_type").apply()

        if (webLoggedIn()) {
            account = getWebAccount()
            Http.cookie = webCookie
        } else {
            Http.cookie = ""
            account = null
        }
        Logger.i("Account", "登录态(本地): WEB=${webLoggedIn()} 用户=${account?.uname ?: "无"}")

        // 网络部分异步执行：spi 指纹注入 + nav 校验补全。
        // 铁律：主线程发网络请求抛 NetworkOnMainThreadException（启动日志里 spi/nav 失败即此因），
        // 且缺少 buvid 指纹会连锁导致弹幕 getDanmuInfo 被风控拒绝（-352）。
        Thread({
            try {
                // 未登录时也注入官方 buvid3/buvid4(模拟浏览器,降低 API 风控概率)
                if (Http.cookie.isEmpty()) {
                    val spi = BiliLiveApi.getSpi()
                    if (spi != null) {
                        Http.cookie = buildString {
                            if (spi.first.isNotEmpty()) append("buvid3=").append(spi.first)
                            if (spi.second.isNotEmpty()) append("; buvid4=").append(spi.second)
                        }
                        Logger.i("Account", "已注入官方 buvid 指纹")
                    }
                }
                val acc = account
                if (acc != null) {
                    // 参照 BBDownAndroid:本地有 cookie 即保留登录态;nav 仅补全资料,失败不否定
                    when (val st = BiliLiveApi.checkNavLogin()) {
                        is BiliLiveApi.NavLoginState.LoggedIn -> activate(st.info)
                        else -> {
                            val enriched = enrichAccount(acc)
                            account = enriched
                            Http.cookie = enriched.cookie
                            saveType(enriched)
                            Logger.w("Account", "启动时 nav 未确认,以本地 cookie 兜底恢复(UID ${enriched.uid})")
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w("Account", "启动网络校验异常: ${e.message}")
            }
            // 补一次通知，让 UI 拿到异步补全后的账号/指纹
            listener?.onLoginStateChanged(account)
        }, "AccountInit").apply { isDaemon = true; start() }
    }

    /** 将登录态设为活跃(account + Http.cookie) */
    private fun activate(acc: AccountInfo) {
        account = acc
        Http.cookie = acc.cookie
        listener?.onLoginStateChanged(acc)
    }

    /** 持久化登录态的资料缓存 */
    private fun saveType(acc: AccountInfo) {
        prefs.edit().putString("web_cookie", acc.cookie).putString("web_uname", acc.uname)
            .putString("web_face", acc.face).putLong("web_uid", acc.uid).putInt("web_level", acc.level)
            .apply()
    }

    /** 合并指纹 cookie(buvid3/buvid4/b_lsid/_uuid/b_nut)到登录 cookie,防止风控 */
    private fun mergeFingerprint(cookie: String): String {
        try {
            val fp = BiliLiveApi.ensureFingerprintCookie()
            if (fp.isEmpty()) return cookie
            val missing = fp.split(";").map { it.trim() }.filter { kv ->
                val name = kv.substringBefore('=')
                !cookie.split(";").any { it.trim().startsWith("$name=") }
            }
            if (missing.isEmpty()) return cookie
            return (missing.joinToString("; ") + "; " + cookie)
        } catch (e: Exception) {
            return cookie
        }
    }

    /**
     * 资料缺失时用 uid 补拉(nav 失败兜底,账户页头像/ID 显示的前提)。
     * 有 uid 但缺 uname/face 时调 space/acc/info 补全,失败保持原样。
     */
    private fun enrichAccount(acc: AccountInfo): AccountInfo {
        if (acc.uid <= 0) return acc
        if (acc.uname.isNotEmpty() && acc.face.isNotEmpty()) return acc
        val info = BiliLiveApi.getUserInfo(acc.uid) ?: return acc
        if (info.first.isEmpty() && info.second.isEmpty()) return acc
        return acc.copy(
            uname = info.first.ifEmpty { acc.uname },
            face = info.second.ifEmpty { acc.face },
        )
    }

    /** 本地是否保存过登录 cookie(定时校验前判断) */
    fun hasSavedCookie(): Boolean = webCookie.isNotEmpty()

    /**
     * 校验当前登录态是否仍有效(调 nav 接口)。
     * 仅"明确未登录"才清除登录态;网络异常不算失效,避免误报。
     * @return true=有效
     */
    fun checkLoginValid(): Boolean {
        val acc = account ?: return true
        if (!acc.isLoggedIn) return false
        return when (val st = BiliLiveApi.checkNavLogin()) {
            is BiliLiveApi.NavLoginState.LoggedIn -> {
                saveType(st.info)
                activate(st.info) // 顺带刷新资料
                true
            }
            BiliLiveApi.NavLoginState.LoggedOut -> {
                // 登录态失效:清除
                Logger.w("Account", "登录态已失效,清除登录信息")
                removeType()
                account = null
                Http.cookie = ""
                listener?.onLoginStateChanged(null)
                false
            }
            BiliLiveApi.NavLoginState.Error -> {
                // 网络异常不算失效
                Logger.w("Account", "登录态校验网络异常")
                true
            }
        }
    }

    /** 清除登录态(内存 + prefs) */
    private fun removeType() {
        webCookie = ""; webUname = ""; webFace = ""; webUid = 0; webLevel = 0
        prefs.edit().remove("web_cookie").remove("web_uname").remove("web_face")
            .remove("web_uid").remove("web_level").apply()
    }

    // ============ 登录成功收尾 ============

    /**
     * 登录成功收尾(移植 BBDownAndroid pollQrLogin 语义):
     * 轮询 code=0 且有 cookie 即登录成功;nav 仅用于补全资料,任何失败都不否定登录结果。
     * 资料缺失时以 uid 兜底,uid 也缺失时保留 cookie 空资料。
     */
    private fun finishLogin(cookie: String) {
        val merged = mergeFingerprint(cookie)
        Http.cookie = merged
        var acc: AccountInfo
        when (val st = BiliLiveApi.checkNavLogin()) {
            is BiliLiveApi.NavLoginState.LoggedIn -> acc = st.info
            else -> {
                acc = accountFromCookie(merged)
                    ?: AccountInfo(cookie = merged, csrf = BiliLiveApi.extractCsrf(merged))
                acc = enrichAccount(acc)
            }
        }
        acc = acc.copy(cookie = merged)
        webCookie = merged
        webUname = acc.uname; webFace = acc.face; webUid = acc.uid; webLevel = acc.level
        saveType(acc)
        activate(acc)
        listener?.onQrcodeUpdated(null, if (acc.uname.isNotEmpty()) "登录成功：${acc.uname}" else "登录成功")
    }

    /** 从指定 cookie 提取 DedeUserID 构造账号,无有效 uid 返回 null */
    private fun accountFromCookie(cookie: String): AccountInfo? {
        val uid = BiliLiveApi.extractUid(cookie)
        if (uid <= 0) return null
        return AccountInfo(uid = uid, cookie = cookie, csrf = BiliLiveApi.extractCsrf(cookie))
    }

    /** 判断是否过期消息 */
    private fun isExpired(code: Int, msg: String): Boolean =
        code == 86038 || msg.contains("过期") || msg.contains("expire")

    // ============ 扫码登录(与原版 DDTV ByQRCode 一致) ============

    /** 开始扫码登录:生成二维码并启动轮询 */
    fun startQrcodeLogin() {
        if (polling.get()) return
        val pair = BiliLiveApi.qrcodeGenerate() ?: run {
            listener?.onQrcodeUpdated(null, "获取二维码失败,请检查网络")
            return
        }
        qrUrl = pair.first
        currentKey = pair.second
        polling.set(true)
        val png = QrCodeUtil.generateBase64Png(qrUrl, size = 512)
        listener?.onQrcodeUpdated(png, "请使用 B站 App 扫码登录")

        pollThread = Thread({
            var expired = false
            while (polling.get()) {
                try {
                    Thread.sleep(1500)
                    val (code, msg, cookie) = BiliLiveApi.qrcodePollWithCookie(currentKey)
                    when {
                        code == 0 -> {
                            polling.set(false)
                            if (cookie.isNotEmpty()) {
                                finishLogin(cookie)
                            } else {
                                listener?.onQrcodeUpdated(null, "登录失败:未获取到 Cookie")
                            }
                            break
                        }
                        code == 86090 -> listener?.onQrcodeUpdated(null, "已扫码,请在手机上确认…")
                        isExpired(code, msg) -> { expired = true; break }
                        else -> listener?.onQrcodeUpdated(null, "等待扫码…($msg)")
                    }
                } catch (e: Exception) {
                    Logger.w("Account", "二维码轮询异常: ${e.message}")
                }
            }
            if (expired) {
                polling.set(false)
                listener?.onQrcodeUpdated(null, "二维码已过期,请点击重新获取")
            }
        }, "QrPoll").apply { isDaemon = true; start() }
    }

    /** 取消扫码登录 */
    fun cancelQrcodeLogin() {
        polling.set(false)
        pollThread?.interrupt()
        pollThread = null
    }

    /** 从外部粘贴 Cookie 登录 */
    fun loginWithCookie(cookie: String): Boolean {
        val trimmed = cookie.trim()
        if (trimmed.isEmpty()) return false
        Http.cookie = mergeFingerprint(trimmed)
        // 粘贴 Cookie 必须明确验证通过才接受(网络异常也视为失败)
        return when (val st = BiliLiveApi.checkNavLogin()) {
            is BiliLiveApi.NavLoginState.LoggedIn -> {
                finishLogin(st.info.cookie)
                Logger.i("Account", "Cookie 登录成功: ${st.info.uname}")
                true
            }
            else -> {
                Http.cookie = ""
                false
            }
        }
    }

    /** 确保有进行中的扫码登录(无则生成),返回当前授权 URL */
    fun ensureQrLogin(): String {
        if (!polling.get()) startQrcodeLogin()
        return qrUrl
    }

    // ============ 退出 ============

    /** 退出登录(清空登录态 + 指纹保留) */
    fun logout() {
        cancelQrcodeLogin()
        removeType()
        Http.cookie = ""
        account = null
        listener?.onLoginStateChanged(null)
        Logger.i("Account", "已退出登录")
    }
}
