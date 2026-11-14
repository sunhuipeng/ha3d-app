package com.sunhuip.ha3dapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.graphics.Color
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.widget.Toast

/**
 * HA 3D Home 壳 App —— WebView 空壳（不做地址探测）：
 *   启动 / 重试 / 从设置页返回时直接加载设置页存的地址；内网主框架加载失败时
 *   若配置了外网入口则自动切过去，再失败显示内置错误页（可重试 / 进服务器设置）。
 *   沉浸式全屏、屏幕常亮。
 *
 * 入口与开关都在 Config.kt。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var currentBase: String? = null     // 当前已加载的入口 URL（null = 错误页态）
    private var recheckPending: Boolean = false // 防抖：错误页态下是否排队了网络恢复重试

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()
        try {
            applyImmersive()
            if (Config.KEEP_SCREEN_ON) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            web = WebView(this)
            setContentView(web)   /* WebView 铺满整屏（含状态栏底下）：不留黑边/白边，
                                     页面内用 --safe-top 自己让出状态区 */
            setupWebView()
            registerNetworkCallback()
            loadConfigured()
        } catch (t: Throwable) {
            /* 老容器（卓易通等）缺 WebView / 不支持新 API 时不闪退：直接把错误画在屏幕上 */
            showFatal(t)
        }
    }

    /* ---------- 崩溃兜底 ---------- */

    /** 未捕获异常写入 App 私有目录 crash.txt（文件管理器可看），便于无 adb 排障 */
    private fun installCrashGuard() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = getExternalFilesDir(null) ?: java.io.File(filesDir, "ext")
                dir.mkdirs()
                java.io.File(dir, "crash.txt").writeText(android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** 启动失败也不闪退：错误全文显示在屏幕上（可截图反馈） */
    private fun showFatal(t: Throwable) {
        try {
            val tv = android.widget.TextView(this)
            tv.text = "启动失败（请截图反馈）：\n\n${android.util.Log.getStackTraceString(t)}"
            tv.textSize = 12f
            tv.setTextColor(Color.rgb(150, 30, 30))
            tv.setPadding(48, 96, 48, 48)
            tv.setOnLongClickListener {
                android.widget.Toast.makeText(this, "崩溃日志在 Android/data/${packageName}/files/crash.txt", android.widget.Toast.LENGTH_LONG).show()
                true
            }
            setContentView(tv)
        } catch (_: Throwable) { }
    }

    /* ---------- WebView ---------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.setBackgroundColor(Color.rgb(238, 241, 244))
        web.addJavascriptInterface(Bridge(), "AppShell")

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            /* 每次页面加载完成注入 --safe-top（状态栏高度，CSS px）：
             * 页面据此让出安全区；普通浏览器没有这个变量，回退 env(safe-area-inset-top) */
            override fun onPageFinished(view: WebView?, url: String?) {
                injectSafeTop()
            }

            /* 主框架加载失败：内网入口不通 → 配了外网就自动切过去；外网也败才出错误页。
             * 只认 failingUrl == currentBase（主框架），子资源失败不打扰页面。 */
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                val base = currentBase ?: return
                if (failingUrl == null || failingUrl != base) return
                val remote = Prefs.remote(this@MainActivity)
                if (base != Prefs.local(this@MainActivity) || remote.isEmpty()) {
                    showErrorPage("无法连接 $base（${description ?: "网络错误"}）")
                } else {
                    currentBase = remote
                    web.loadUrl(remote)   // 自动外网回退（不再探测，直接试加载）
                }
            }

            /* 内网常用自签 HTTPS 证书：私有壳 App 直接放行（只影响本 WebView） */
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme ?: return false
                /* 电话/邮件等交给系统，其余一律留在壳内 */
                return if (scheme == "tel" || scheme == "mailto" || scheme == "sms") {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                    true
                } else false
            }
        }
    }

    /** 错误页：点「重试」调回原生，重新探测并加载；「服务器设置」进设置页改地址 */
    private fun showErrorPage(reason: String) {
        currentBase = null
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <style>body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;background:#eef1f4;color:#1f3f68;}
            p{margin:8px 0;color:#6b7c93}button{margin-top:18px;padding:12px 36px;border:0;border-radius:999px;font-size:16px}
            .retry{background:#2f6fed;color:#fff}.cfg{background:#fff;color:#2f6fed;border:1px solid #c9d7ea}</style></head>
            <body><div style="text-align:center"><div style="font-size:56px">🔌</div>
            <h2>暂时连不上服务</h2><p>$reason</p></div>
            <button class="retry" onclick="AppShell.retry()">重试</button>
            <button class="cfg" onclick="AppShell.openSettings()">服务器设置</button></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /* ---------- 入口装载（无探测） ---------- */

    /** 直接加载设置页存的内网地址（没存过用 Config.LOCAL_URL）。
     *  与当前入口一致时不重载（避免打断正在看的页面）；错误页态（currentBase==null）会重新装载。 */
    private fun loadConfigured() {
        recheckPending = false
        val target = Prefs.local(this)
        if (target != currentBase) {
            currentBase = target
            web.loadUrl(target)
        }
    }

    /** 防抖重试：仅错误页态下网络恢复时自动重载（正常显示中网络抖动不打扰） */
    private fun scheduleRecheck() {
        if (recheckPending || currentBase != null) return
        recheckPending = true
        window.decorView.postDelayed({
            if (recheckPending && currentBase == null) loadConfigured()
        }, Config.RECHECK_DEBOUNCE_MS)
    }

    companion object {
        /** HTTP 可达性探测（1.5s 超时，2xx~4xx 均视为活着）——设置页「测试连通」用 */
        fun reachable(url: String): Boolean = try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.instanceFollowRedirects = true
            conn.responseCode in 200..499
        } catch (_: Exception) { false }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scheduleRecheck() }
                override fun onLost(network: Network) { /* 显示中交给 onReceivedError 兜底 */ }
            })
        } catch (_: Exception) { /* 极旧系统没有 registerDefaultNetworkCallback，忽略 */ }
    }

    /* ---------- 原生桥（错误页 / 页面内设置入口用） ---------- */

    inner class Bridge {
        @JavascriptInterface
        fun retry() { runOnUiThread { loadConfigured() } }

        @JavascriptInterface
        fun openSettings() { runOnUiThread { startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java)) } }
    }

    /* ---------- 全屏沉浸 + 返回键 ---------- */

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        try {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            /* 统一走 androidx compat：内容延伸到系统栏底下（不留黑边），
             * SHOW_STATUS_BAR=true 只藏导航栏、状态栏保留；false 全藏（完全沉浸） */
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView) ?: return
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (Config.SHOW_STATUS_BAR) {
                c.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                c.isAppearanceLightStatusBars = true    // 浅色底配深色状态栏图标
            } else {
                c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Throwable) { /* 全屏失败不值得闪退 */ }
    }

    /** 状态栏高度（用于保留状态栏时把内容顶下来） */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /** 给页面注入 --safe-top（状态栏高度换算成 CSS px；完全沉浸模式注入 0 让页面铺满） */
    private fun injectSafeTop() {
        if (!this::web.isInitialized) return
        val cssPx = if (Config.SHOW_STATUS_BAR) statusBarHeight() / resources.displayMetrics.density else 0f
        val v = if (cssPx == cssPx.toLong().toFloat()) cssPx.toLong().toString() else cssPx.toString()
        web.evaluateJavascript(
            "document.documentElement.style.setProperty('--safe-top','${v}px');", null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        /* 从通知/对话框回来时系统栏可能复现，重新压下去 */
        if (hasFocus) applyImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() { if (this::web.isInitialized) web.onPause(); super.onPause() }

    /* 从设置页返回：地址可能改了，重新装载（与当前一致则不会重载，不打断页面） */
    override fun onRestart() { super.onRestart(); if (this::web.isInitialized) loadConfigured() }

    override fun onResume() {
        super.onResume()
        if (this::web.isInitialized) web.onResume()
        applyImmersive()
    }
    override fun onDestroy() { if (this::web.isInitialized) web.destroy(); super.onDestroy() }
}
