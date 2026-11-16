/*
 * Shell.kt — WebView 单例容器（进程内唯一实例，Activity 只做 attach/detach）。
 *
 * 为什么不再由 MainActivity 持有：Activity 一销毁就 destroy() 会把 three.js 场景、
 * 已建立的 HA WebSocket、楼层缓存全部作废——按返回退出再进、系统低内存回收 Activity，
 * 都要重走一遍完整加载。改成 Application 级单例后，第二次进入只是把同一个 WebView
 * 重新 attach 到窗口，零加载。
 *
 * 注意事项（改这块前先读）：
 *   · attach 前必须判 parent 并 removeView，否则 setContentView 会 IllegalStateException
 *   · 不要在这里 destroy()；真要释放走 release()（内存极紧时由 MainActivity 兜底调用）
 *   · WebView 必须用 applicationContext 构造，用 Activity 会泄漏整个窗口
 */
package com.sunhuip.ha3dapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference

object Shell {

    private const val TAG = "HA3DShell"

    /** Application context（init 时赋值） */
    lateinit var app: Context
        private set

    /** 进程内唯一 WebView（null = 还没创建 / 已被 release） */
    var web: WebView? = null
        private set

    /** 当前已加载的入口 URL；null = 错误页态 */
    var currentBase: String? = null
        private set

    /** 是否正显示「离线回退副本」：网络恢复后自动重载一次 */
    private var offlineShown = false

    private var hostRef: WeakReference<Activity>? = null
    private var recheckPending = false
    private var warmup: WebView? = null
    private var policyApplied = false
    private val main = Handler(Looper.getMainLooper())

    /* ---------------- 初始化（Application.onCreate 调用） ---------------- */

    fun init(ctx: Context) {
        app = ctx.applicationContext
        warmUp()
        registerNetworkCallback()
    }

    /**
     * renderer 优先级（进程级，每个进程只生效一次，所以要在第一个 WebView 建好后立刻设）。
     *
     * 默认策略是 RENDERER_PRIORITY_WAIVED：WebView 一不可见就降到最低 oom_adj，
     * 系统稍有内存压力就优先杀掉 Chromium renderer —— 表现为「切出去看个消息再回来，
     * 白屏重载一遍、3D 从头构建」。设成 BOUND + waivedWhenNotVisible=false 让它与 App
     * 同优先级，正好对应「常亮展示板 + 偶尔切出去」的用法。
     */
    private fun applyRendererPolicy(v: WebView) {
        if (policyApplied) return
        policyApplied = true
        try {
            v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        } catch (_: Throwable) {
            /* 已被设过 / 老容器不支持：退回系统默认，不影响页面使用 */
        }
    }

    /**
     * 预热：先造一个离屏 WebView 把 Chromium 动态库加载掉（模板 1x1 空白页）。
     * 真 WebView 创建时不必再等库加载，首屏能提前 30~80ms。
     * 预热实例在真 WebView 建好后立刻销毁——renderer 进程是共享的，库不会因此卸载。
     */
    private fun warmUp() {
        if (warmup != null) return
        try {
            val v = WebView(app)
            applyRendererPolicy(v)          // 进程里第一个 WebView，正好用来设策略
            v.settings.javaScriptEnabled = true
            v.setBackgroundColor(Color.rgb(238, 241, 244))
            v.loadDataWithBaseURL(
                null,
                "<html><body style=\"margin:0;background:#eef1f4\"></body></html>",
                "text/html", "utf-8", null,
            )
            warmup = v
        } catch (_: Throwable) {
            /* 预热失败无所谓，只是没省下那几十毫秒 */
        }
    }

    private fun releaseWarmup() {
        try { warmup?.destroy() } catch (_: Throwable) { }
        warmup = null
    }

    /* ---------------- WebView 创建 / 挂载 ---------------- */

    private fun ensure(): WebView {
        web?.let { return it }
        val v = WebView(app)
        applyRendererPolicy(v)      // 预热失败时的兜底：这是进程里第一个 WebView
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            /* 页面内 3D 舞台自己接管双指手势；WebView 层再放大整页会和取景手势打架，
               表现为「捏一下界面整体放大、还退不回去」 */
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            /* 滚动到屏幕外时先栅格化，减少长页滚动的白块 */
            offscreenPreRaster = true
        }
        v.setBackgroundColor(Color.rgb(238, 241, 244))
        v.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER)
        v.isVerticalScrollBarEnabled = false
        v.addJavascriptInterface(Bridge(), "AppShell")
        v.webChromeClient = WebChromeClient()
        v.webViewClient = Client()
        web = v
        /* 预热实例延后释放：某些老 WebView 在「同进程还有活实例时 destroy 另一个」
           会出错，等新实例稳定下来再收，代价只是多占几十毫秒内存。 */
        main.postDelayed({ releaseWarmup() }, 800)
        return v
    }

    /**
     * 把单例 WebView 挂到 Activity 窗口上。
     * 先判 parent 再 removeView 是必须的：从设置页返回、或系统重建 Activity 时，
     * WebView 可能还挂在已销毁的 content view 上，直接 setContentView 会崩。
     */
    fun attach(activity: Activity): WebView {
        val v = ensure()
        hostRef = WeakReference(activity)
        (v.parent as? ViewGroup)?.removeView(v)
        activity.setContentView(v)
        v.onResume()
        applyPageScaleGuard(v)
        return v
    }

    /** 只脱离窗口，不 destroy（下次 attach 复用同一实例） */
    fun detach() {
        val v = web ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        hostRef = null
    }

    /** 真释放（内存极紧时兜底）：销毁后下次 ensure() 会重建 */
    fun release() {
        detach()
        try { web?.destroy() } catch (_: Throwable) { }
        web = null
    }

    /** 老容器可能忽略 settings 里的缩放开关，这里再压一道（不影响页面内 canvas 手势） */
    private fun applyPageScaleGuard(v: WebView) {
        try {
            v.settings.textZoom = 100
            v.settings.loadWithOverviewMode = false
            v.settings.useWideViewPort = true
        } catch (_: Throwable) { }
    }

    /* ---------------- 入口装载 ---------------- */

    /**
     * 加载设置页存的入口地址。与当前一致时不重载（避免打断正在看的页面）；
     * force = true 时（点「重试」）无条件重载。
     */
    fun loadConfigured(force: Boolean = false) {
        recheckPending = false
        offlineShown = false
        val target = Prefs.local(app)
        val v = ensure()
        if (force || target != currentBase) {
            currentBase = target
            v.loadUrl(target)
        }
    }

    /** 网络恢复后的防抖重试：错误页态 / 离线页态才重载，正常显示中不打扰 */
    private fun scheduleRecheck() {
        if (recheckPending) return
        if (currentBase != null && !offlineShown) return
        recheckPending = true
        main.postDelayed({
            if (recheckPending) {
                val t = currentBase
                if (t != null && offlineShown) {
                    offlineShown = false
                    web?.loadUrl(t)          // 离线副本 → 网络恢复，重拉一次
                } else {
                    loadConfigured(force = true)
                }
            }
            recheckPending = false
        }, Config.RECHECK_DEBOUNCE_MS)
    }

    private fun registerNetworkCallback() {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scheduleRecheck() }
                override fun onLost(network: Network) { /* 显示中交给 onReceivedError 兜底 */ }
            })
        } catch (_: Throwable) {
            /* 极旧系统没有 registerDefaultNetworkCallback，忽略 */
        }
    }

    /* ---------------- 失败处理：离线回退 → 原生错误页 ---------------- */

    /**
     * 主框架加载失败统一入口。
     * 只认 failingUrl == currentBase，子资源失败不打扰页面；同一失败的两次回调
     * （新旧两个重载）后到的会被这层比较挡掉，不会重复弹页。
     */
    private fun onMainFrameFailed(failingUrl: String?, description: String?) {
        val base = currentBase ?: return
        if (failingUrl == null || failingUrl != base) return

        /* 优先回退到「上次成功抓到的 HTML + 本地 js/css 副本」：
           页面能起来，由它自己显示「连不上 HA / 正在重连」，比甩原生错误页有用得多。
           没有完整副本（从没成功加载过）时，老老实实出原生错误页。 */
        if (Config.OFFLINE_FALLBACK && tryRestoreOffline(base)) return
        showErrorPage("无法连接 $base（${description ?: "网络错误"}）")
    }

    /**
     * 用磁盘副本重建页面；返回 false 表示没有可用副本。
     * 副本按「服务器」分开存（WebCache 里就是按 origin 分目录的）：
     * 只有这台服务器自己成功加载过才有副本，绝不拿 A 服务器的页面去顶 B 服务器 ——
     * 那样 HTML 引用的 js 在 B 上根本不存在，表现就是一片白。
     */
    private fun tryRestoreOffline(base: String): Boolean {
        val html = WebCache.readHtml(app, base) ?: return false
        if (!WebCache.hasAssets(app, base)) return false
        val v = web ?: return false
        offlineShown = true
        currentBase = base          // 保留入口，网络恢复时据此重载
        v.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
        return true
    }

    /** 原生错误页：点「重试」调回原生按当前配置重载；「服务器设置」进设置页改地址 */
    private fun showErrorPage(reason: String) {
        currentBase = null
        offlineShown = false
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
        web?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /* ---------------- WebViewClient ---------------- */

    private class Client : WebViewClient() {

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == null) return
            injectSafeTop(view)
            if (Shell.offlineShown) injectOfflineBanner(view)
            /* 页面加载完成后刷一次 HTML 副本（节流在 WebCache 里做），保证回退副本尽量新 */
            WebCache.prefetchAsync(Shell.app)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val req = request ?: return null
            /* 只对「文件名带内容哈希」的 Vite 产物做磁盘命中，其余（GLB / catalog / API /
               主文档）一律返回 null 交回 WebView：那些文件改了名不变，自己缓存会出脏数据 */
            return WebCache.intercept(req)
        }

        /* 旧重载已废弃，但部分老 WebView / 兼容容器只回调它，保留作为兜底 */
        @Deprecated("Deprecated in Java")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
            Shell.onMainFrameFailed(failingUrl, description)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                Shell.onMainFrameFailed(request.url?.toString(), error?.description?.toString())
            }
        }

        /* 内网常用自签 HTTPS 证书：私有壳 App 直接放行（只影响本 WebView） */
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
            handler?.proceed()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url ?: return false
            val scheme = url.scheme ?: return false
            /* 电话/邮件等交给系统，其余一律留在壳内 */
            return if (scheme == "tel" || scheme == "mailto" || scheme == "sms") {
                try {
                    val ctx = Shell.hostRef?.get() ?: Shell.app
                    val it = Intent(Intent.ACTION_VIEW, url)
                    if (ctx !is Activity) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                } catch (_: Exception) { }
                true
            } else false
        }
    }

    /* ---------------- 原生桥（错误页 / 离线提示条 / 页面内设置入口用） ---------------- */

    /* 必须是可被反射访问到的类型：WebView 通过反射调用 @JavascriptInterface 方法，
       嵌套成 private class 在部分实现上会 IllegalAccessException。 */
    class Bridge {
        @JavascriptInterface
        fun retry() {
            main.post { loadConfigured(force = true) }
        }

        @JavascriptInterface
        fun openSettings() {
            main.post {
                try {
                    val intent = Intent(app, SettingsActivity::class.java)
                    val act = hostRef?.get()
                    if (act != null) act.startActivity(intent)
                    else app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
        }
    }

    /* ---------------- 安全区 / 离线提示 ---------------- */

    private fun statusBarHeight(): Int {
        val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) app.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * 注入 --safe-top（状态栏高度换算成 CSS px）：
     * 页面据此让出安全区；普通浏览器没有这个变量，回退 env(safe-area-inset-top)。
     */
    private fun injectSafeTop(view: WebView) {
        val cssPx = if (Config.SHOW_STATUS_BAR) statusBarHeight() / app.resources.displayMetrics.density else 0f
        val v = if (cssPx == cssPx.toLong().toFloat()) cssPx.toLong().toString() else cssPx.toString()
        view.evaluateJavascript(
            "document.documentElement.style.setProperty('--safe-top','${v}px');", null,
        )
    }

    /** 离线副本上的提示条：不改页面结构，加载后浮一层，可点重连 */
    private fun injectOfflineBanner(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
              if (document.getElementById('__shell_offline')) return;
              var d = document.createElement('div');
              d.id = '__shell_offline';
              d.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:2147483000;' +
                'padding:8px 12px;font:13px/1.4 -apple-system,system-ui,sans-serif;text-align:center;' +
                'background:#fff4e5;color:#8a5300;box-shadow:0 1px 4px rgba(0,0,0,.12)';
              d.innerHTML = '离线模式：显示上次缓存的界面' +
                '<a href="javascript:void(0)" onclick="AppShell.retry()" ' +
                'style="color:#2f6fed;font-weight:600;text-decoration:none;margin-left:8px">重新连接</a>';
              document.body.appendChild(d);
            })();
            """.trimIndent(),
            null,
        )
    }

    /* ---------------- 运行诊断（设置页展示） ---------------- */

    /**
     * 体检信息。老版本 WebView 是掉帧和闪退的头号嫌疑，而 manifest 里的
     * hardwareAccelerated 只是「声明」，运行期到底有没有生效只有 isHardwareAccelerated 知道。
     */
    fun diagText(): String {
        val v = web
        val hw = when (v?.isHardwareAccelerated) {
            null -> "未知（WebView 尚未创建）"
            true -> "已开启"
            false -> "未开启（掉帧多半出在这里）"
        }
        val pkg = try {
            WebView.getCurrentWebViewPackage()
        } catch (_: Throwable) { null }
        val ver = if (pkg == null) "未知" else "${pkg.packageName} ${pkg.versionName}"
        val scale = try { v?.settings?.textZoom?.toString() ?: "-" } catch (_: Throwable) { "-" }
        val cacheMb = WebCache.sizeBytes(app) / 1024f / 1024f

        /* renderer 优先级读运行期真实值：设失败（老容器）时这里会退回 WAIVED，
           一眼就能看出「切回来白屏」是不是这个原因 */
        val prio = try {
            if (v == null) "未创建"
            else {
                val waived = v.rendererPriorityWaivedWhenNotVisible
                val name = when (v.rendererRequestedPriority) {
                    WebView.RENDERER_PRIORITY_BOUND -> "BOUND"
                    WebView.RENDERER_PRIORITY_IMPORTANT -> "IMPORTANT"
                    else -> "WAIVED"
                }
                if (waived) "$name（不可见时降级）" else "$name（不可见时不降级）"
            }
        } catch (_: Throwable) { "未知" }

        return buildString {
            appendLine("硬件加速：$hw")
            appendLine("WebView：$ver")
            appendLine("文本缩放：$scale%")
            appendLine("渲染优先级：$prio")
            appendLine("缓存代：${Prefs.appVersion(app) ?: "boot"}")
            val base = currentBase ?: Prefs.local(app)
            appendLine("离线副本：${if (WebCache.hasHtml(app, base)) "有" else "无"}，资源缓存 ${String.format(java.util.Locale.US, "%.1f", cacheMb)} MB")
        }.trim()
    }

    /** 供 MainActivity 判「是否已建好」 */
    fun hasWeb(): Boolean = web != null
}
