/*
 * MainActivity.kt — 壳容器（很薄的一层）。
 *
 * WebView 的创建、加载、失败兜底、缓存全部搬到 Shell / WebCache，这里只负责：
 *   · 窗口装饰（沉浸式全屏；屏幕常亮按设置页开关，默认关）
 *   · 把单例 WebView attach 上来 / 离开时 detach（不销毁，下次进入零加载）
 *   · 返回键（页内可回退先回退，退到底才退出 App）
 *
 * 入口与开关都在 Config.kt；异常兜底沿用 crash.txt（外部私有目录，无 adb 也能排障）。
 */
package com.sunhuip.ha3dapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView

class MainActivity : Activity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()
        try {
            applyImmersive()
            applyKeepAwake()

            web = Shell.attach(this)     // 复用进程内单例（含预热、诊断、缓存策略）
            Shell.loadConfigured()       // 与当前入口一致时不会重载，避免打断正在看的页面
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
            } catch (_: Exception) { }
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
                android.widget.Toast.makeText(
                    this, "崩溃日志在 Android/data/${packageName}/files/crash.txt",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                true
            }
            setContentView(tv)
        } catch (_: Throwable) { }
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
        } catch (_: Throwable) {
            /* 全屏失败不值得闪退 */
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        /* 从通知/对话框回来时系统栏可能复现，重新压下去 */
        if (hasFocus) applyImmersive()
    }

    /**
     * TV/盒子遥控器：方向键与确认键转发给页面焦点导航（engine/tvFocus.js），
     * 设置实体键（部分遥控映射为 MENU）打开服务地址设置页。
     * BACK 不拦截（走 onBackPressed：页内回退 → 退出）；HOME 键系统不投递给应用，
     * 按下即回桌面，天然就是「默认实现」。
     * 键盘长按会连续触发 onKeyDown（系统自带 repeat），页面侧寻焦是轻量查询，无需节流。
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> { Shell.dispatchTvKey("up"); return true }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { Shell.dispatchTvKey("down"); return true }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { Shell.dispatchTvKey("left"); return true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { Shell.dispatchTvKey("right"); return true }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            -> { Shell.dispatchTvKey("ok"); return true }
            android.view.KeyEvent.KEYCODE_SETTINGS,
            android.view.KeyEvent.KEYCODE_MENU,
            -> { Shell.openSettings(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    /* ---------- 生命周期：只 detach，不 destroy ---------- */

    override fun onRestart() {
        super.onRestart()
        /* 从设置页返回：地址可能改了。与当前一致时 Shell 内部会跳过重载 */
        Shell.loadConfigured()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        applyImmersive()
        applyKeepAwake()    // 设置页拨了常亮开关，回来立即生效（onRestart→onResume 必经）
    }

    /** 屏幕常亮按设置页的开关来（未拨过用 Config 默认值）：add/clear 成对，切换不会残留 */
    private fun applyKeepAwake() {
        try {
            if (Prefs.keepAwake(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) { }
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (Config.REUSE_WEBVIEW) {
            Shell.detach()          // 只脱离窗口：页面、HA 长连、3D 场景全部留着
        } else {
            Shell.release()
        }
        super.onDestroy()
    }

    /**
     * 内存极紧（后台进程即将被回收）时主动释放 WebView：与其被系统直接杀进程、
     * 下次冷启动全量重来，不如自己先放掉这块内存，保留进程壳，回来还能快速重建。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_COMPLETE && Config.RELEASE_WEBVIEW_ON_PRESSURE) {
            Shell.release()
        }
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
}
