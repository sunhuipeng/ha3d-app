/*
 * WebCache.kt — 壳层离线缓存（叠在服务端三级缓存之上的一层「App 私有目录副本」）。
 *
 * 为什么需要：服务端的静态策略（?v= immutable / ETag 304 / HTML no-store）依赖 WebView
 * 自己的 HTTP 缓存，而那份缓存在低端机上会被系统按配额清掉；更要紧的是 HTML 是
 * no-store，断网 / 服务器不可达时连页面都进不去，只能甩原生错误页。
 *
 * ── 缓存依据（三层，都不看文件时间）
 *   1. 应用版本号 = 服务端根 package.json 的 version（GET /api/version 实时读）
 *      → 决定缓存「代」：目录是 webcache/<version>/，发版即换目录、旧代整体作废。
 *   2. 服务器 = origin（scheme://host:port）
 *      → 每一代下面再按 origin 分目录。壳里可以随时切内网正式 / 测试 / 本机 dev，
 *        不同服务器的同名路径内容并不通用（/index.html、/js/app.js?v=1 都可能重名），
 *        混在一起会出现「切到 B 服务器却加载了 A 的文件」这种最难查的白屏。
 *   3. 资源自身版本 = 前端 URL 里已经带的版本
 *      · Vite 产物：文件名自带内容哈希（/static/index-a1b2c3d4.js）
 *      · 插件：plugin.json 的 version 拼进 ?v=（engine/plugins.js 的加载逻辑）
 *      · 旧站 js：?v=N 手升版本号
 *      → 所以按「完整 URL（含 query）」做文件名即可：版本一变 URL 就变，天然不命中。
 *
 * ── 缓存边界（这条踩过坑，改前务必看完）
 *   只缓存「URL 自带版本」的资源：文件名带内容哈希，或 query 里有 v=。
 *   下面这些必须放过，一律返回 null 交给 WebView：
 *     · Vite dev 的裸模块：/src/、/@vite/、/@fs/、/node_modules/ 下的所有文件
 *       dev 下一次加载有几百个模块，逐个再额外抓一遍落盘会把请求量翻倍并排队，
 *       表现就是「手机浏览器等一会能出来、App 里骨架一直不动」。
 *       而且 dev 模块随时变，缓存下来还会加载到旧模块。
 *     · 不带版本号的 GLB / catalog.json：改了看不出来，交给 WebView 自己的
 *       ETag 协商（服务端已实现），安全且不用我们操心。
 *   GLB（2026-11-19 起纳入）：home 服务端已全面版本化 —— 插件 GLB 拼
 *     plugin.json version、家具包 GLB 拼 item.json version、内置 GLB 拼
 *     package.json version（?v= → immutable）。所以 query 带 v= 的 GLB 也按
 *     「自带版本」缓存：断网也能加载出 3D 模型，版本一变 URL 就变不会命中旧文件。
 *     代价是落盘体积（每个模型几 MB），由版本代切换时整体清理来兜底。
 *
 * ── 填充时机
 *   两条路都会写盘，命中只要求「这个 URL 曾经成功加载过」：
 *   · 正常加载：拦截返回 null 交给 WebView 抓，同时排一次后台落盘（首次加载不多等）
 *   · 启动预取：Application 起来就先抓入口 HTML（只写盘、不参与命中优先路径）
 */
package com.sunhuip.ha3dapp

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

object WebCache {

    private const val TAG = "HA3DWebCache"

    /** 版本未知时（首次安装）用的临时代 */
    private const val BOOT = "boot"

    /** 只缓存这些扩展名的资源（URL 自带版本的代码类 + 带版本的模型） */
    private val CODE_EXT = setOf("js", "css", "woff2", "woff", "glb")

    /** dev 服务器（Vite）的裸模块路径前缀：这些绝不能按文件缓存，见文件头说明 */
    private val DEV_PREFIX = listOf("/src/", "/@", "/node_modules/", "/__vite", "/favicon")

    /** Vite 产物：name-<hash8+>.ext（hash 由 base64url 字符集组成） */
    private val HASHED = Regex("-[A-Za-z0-9_-]{8,}\\.[a-z0-9]+$", RegexOption.IGNORE_CASE)

    /** 单线程顺序落盘：并发抓同一批资源容易互相覆盖，也不差这点并发度 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ha3d-webcache").apply { isDaemon = true }
    }

    /** 预取节流（onPageFinished 会反复触发，别每次都抓一遍）。
     *  按 origin 记：切内网/外网时 originKey 变了要立刻给新服务器建副本，不能被旧站的 5 分钟挡掉。 */
    private var lastPrefetchAt = 0L
    private var lastPrefetchKey = ""
    private const val PREFETCH_MIN_GAP_MS = 5 * 60 * 1000L

    /** 当前缓存代（服务端版本号）；Prefs 里有旧值就先用，冷启动零等待 */
    private var version: String? = null

    /* ---------------- 目录（版本代 → origin） ---------------- */

    private fun verOf(ctx: Context): String {
        version?.let { return it }
        val v = Prefs.appVersion(ctx) ?: BOOT
        version = v
        return v
    }

    private fun root(ctx: Context): File = File(ctx.filesDir, "webcache").apply { mkdirs() }

    private fun versionDir(ctx: Context): File = File(root(ctx), verOf(ctx)).apply { mkdirs() }

    /** 某一代下、某台服务器的目录（originKey 见 originKeyOf） */
    private fun dirOf(ctx: Context, originKey: String): File =
        File(versionDir(ctx), originKey).apply { mkdirs() }

    /**
     * 把 origin 变成安全的目录名：192.168.31.16:8099 → http_192.168.31.16_8099
     * 不同端口的同一台机器也分开（8099 正式 / 5190 dev 内容完全不同）。
     */
    private fun originKeyOf(raw: String?): String? {
        val origin = originOf(raw ?: return null) ?: return null
        return origin.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    }

    /** 入口 HTML 副本（每代、每服务器一份） */
    private fun htmlFile(ctx: Context, originKey: String): File =
        File(dirOf(ctx, originKey), "__index.html")

    /**
     * URL → 扁平文件名，query 一起编码：
     *   /static/index-a1b2.js                 → static_index-a1b2.js
     *   /assets/plugins/light/plugin.js?v=1.2 → assets_plugins_light_plugin.js__v_1.2
     */
    private fun nameOf(url: String): String {
        val u = try { URI(url) } catch (_: Exception) { null }
        val path = u?.path ?: url.substringBefore('?')
        val query = u?.query ?: url.substringAfter('?', "")
        val base = path.trimStart('/')
        val raw = if (query.isEmpty()) base else "${base}__$query"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".woff") -> "font/woff"
        name.endsWith(".glb") -> "model/gltf-binary"
        else -> "application/octet-stream"
    }

    private fun extOf(path: String): String {
        val i = path.lastIndexOf('.')
        return if (i >= 0) path.substring(i + 1).lowercase() else ""
    }

    /**
     * 这个 URL 是否「自带版本」——只有自带版本的才能按文件缓存。
     * dev 模块路径一律排除（见文件头「缓存边界」）。
     */
    private fun cacheable(path: String, query: String?): Boolean {
        if (extOf(path) !in CODE_EXT) return false
        if (DEV_PREFIX.any { path.startsWith(it) }) return false
        val q = query ?: ""
        val hasVersion = q.split('&').any { it == "v" || it.startsWith("v=") }
        return hasVersion || HASHED.containsMatchIn(path)
    }

    /* ---------------- 拦截：自带版本的代码资源走本地副本 ---------------- */

    /**
     * 在 WebView 的后台线程调用。命中磁盘直接回本地文件（秒开、断网可用）；
     * 未命中返回 null 交给 WebView 自己抓，同时排一次后台落盘——
     * 首次加载不会因为我们要多抓一遍而变慢（前提是只对极少量生产产物这么做）。
     */
    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        if (req.isForMainFrame) return null        // 主文档不拦：保证前端改动即时生效
        if (req.method != "GET") return null
        val url = req.url?.toString() ?: return null
        if (!url.startsWith("http")) return null

        val path = try { req.url.path ?: return null } catch (_: Throwable) { return null }
        val query = try { req.url.query } catch (_: Throwable) { null }
        if (!cacheable(path, query)) return null

        val ctx = Shell.app
        val originKey = originKeyOf(url) ?: return null
        val name = nameOf(url)
        val f = File(dirOf(ctx, originKey), name)
        if (f.isFile && f.length() > 0) {
            return try {
                // 二进制字体不能声明字符集，传 null 让 WebView 按 MIME 处理
                WebResourceResponse(mimeOf(name), null, FileInputStream(f))
            } catch (_: Throwable) {
                null
            }
        }
        scheduleStore(url, f)
        return null
    }

    private fun scheduleStore(url: String, f: File) {
        io.execute {
            try {
                if (f.isFile && f.length() > 0) return@execute
                fetchToFile(url, f)
            } catch (_: Throwable) { }
        }
    }

    /* ---------------- 抓取落盘 ---------------- */

    /**
     * 抓 url 写到 f；先写 .tmp 再改名，避免读到半截文件。
     *
     * @param withAuth 是否带原生 token。入口 HTML 必须带：服务端启用登录门禁后，
     *   裸请求 /index.html 会被 302 到 /login.html，存下来的「离线副本」就变成登录页了。
     * @param requireOk 是否把 302 后的最终 URL 当失败（登录页不能当页面副本存）。
     */
    private fun fetchToFile(url: String, f: File, withAuth: Boolean = false): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")   // 落盘存原文，别存 gzip 体
                if (withAuth) Prefs.authToken(Shell.app)?.let { setRequestProperty("X-HA3D-Token", it) }
            }
            if (conn.responseCode != 200) return false
            /* 被重定向到登录页 = 没拿到真正的页面副本，宁可不存（让诊断继续显示「无」） */
            if (withAuth && conn.url?.path?.endsWith("/login.html") == true) return false
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            conn.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            if (tmp.length() > 0) {
                f.delete()
                return tmp.renameTo(f)
            }
            tmp.delete()
            return false
        } catch (_: Throwable) {
            return false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    /* ---------------- 版本核对（缓存代的开关） ---------------- */

    /**
     * 问服务端要版本号（GET /api/version，实时读 package.json）。
     * 版本变了就切到新代并删掉旧代目录 —— 发版后旧缓存整体作废，
     * 不会出现「新前端配旧 js」这种最难查的问题。
     *
     * 调用时机：Application 启动 + 每次页面加载完成（服务端可能热升级）。
     *
     * @param base 当前实际连接的地址（Shell.currentBase）。**不能写死 Prefs.local()**：
     *   外网时当前服务是 jmglink，而 Prefs.local() 是内网地址——外网不可达，
     *   版本请求必然失败，缓存代就永远停在 boot（这是「缓存代 boot」的根因之一）。
     */
    fun refreshVersionAsync(ctx: Context, base: String? = null) {
        val origin = originOf(base ?: try { Prefs.local(ctx) } catch (_: Throwable) { return }) ?: return
        io.execute { checkVersion(ctx, origin) }
    }

    /** 在 io 线程核对版本：HTTP 请求 + 换代都在同一条线程，避免与落盘抢目录 */
    private fun checkVersion(ctx: Context, origin: String) {
        try {
            val url = URL("$origin/api/version")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) { conn.disconnect(); return }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            // 极简解析：只取 version 字段，不引 JSON 库
            val v = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            if (v.isNullOrBlank()) return
            onVersionSeen(ctx, v)
        } catch (_: Throwable) { }
    }

    private fun onVersionSeen(ctx: Context, v: String) {
        /* Prefs 里没存过 = 首次安装还停在 boot 临时代；按 boot 处理才能把
           刚抓到的新文件改名接过来（否则下面 cleanup 会把 boot 代整个删掉白下一轮） */
        val old = Prefs.appVersion(ctx) ?: BOOT
        if (old == v) return
        Prefs.setAppVersion(ctx, v)
        version = v
        try {
            val base = root(ctx)
            val newDir = File(base, v)
            val oldDir = File(base, old)
            /* 第一次确定版本（之前是临时的 boot 代）时把内容直接接过来 ——
               那批文件本来就是刚从这个服务端抓的，改名比清空重下省一整轮加载。
               真发版时旧目录里是旧文件：Vite 产物名带哈希，URL 变了自然不命中，
               所以迁移过去也不会出现「新前端配旧 js」。 */
            if (oldDir.isDirectory && !newDir.exists()) {
                if (!oldDir.renameTo(newDir)) Log.w(TAG, "rename ${oldDir.name} -> $v failed")
            }
            /* 其余更早的代直接清掉，控制体积 */
            base.listFiles()?.forEach { d ->
                if (d.isDirectory && d.name != v) d.deleteRecursively()
            }
        } catch (_: Throwable) { }
        Log.i(TAG, "cache generation -> $v (was $old)")
    }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        if (u.scheme == null || u.host == null) null
        else buildString {
            append(u.scheme).append("://").append(u.host)
            if (u.port > 0) append(':').append(u.port)
        }
    } catch (_: Throwable) { null }

    /* ---------------- 入口 HTML 副本（按服务器分开存） ---------------- */

    /**
     * 预取「当前实际连接的那台服务器」的入口 HTML（按 origin 独立节流）。
     * 只写盘，不参与命中优先路径 —— 正常加载永远走网络（保证改动即时生效），
     * 副本只在主框架加载失败时用来重建页面。
     *
     * @param base 当前 Shell.currentBase（在家=内网、外出=外网）。
     *   **不能写死 Prefs.local()**：那只是内网地址，外网时给它建副本毫无意义，
     *   外网 origin 就永远没有 HTML 副本 → 断网无法离线回退（「外网无副本」根因）。
     */
    fun prefetchAsync(ctx: Context, base: String? = null) {
        val url = try {
            (base ?: Prefs.local(ctx)).takeIf { it.startsWith("http") } ?: return
        } catch (_: Throwable) { return }
        val originKey = originKeyOf(url) ?: return

        /* 版本核对不节流（每次页面加载完成都问一次，服务端实时读文件、开销极小）；
           HTML 落盘才按 origin 节流：切服务器时 key 变了，立刻给新站建副本。 */
        refreshVersionAsync(ctx, url)
        val now = System.currentTimeMillis()
        if (originKey == lastPrefetchKey && now - lastPrefetchAt < PREFETCH_MIN_GAP_MS) return
        lastPrefetchAt = now
        lastPrefetchKey = originKey
        io.execute {
            try { fetchToFile(url, htmlFile(ctx, originKey), withAuth = true) } catch (_: Throwable) { }
        }
    }

    /** base 这台服务器有没有可用的 HTML 副本（跨服务器一律不认，见文件头第 2 条） */
    fun hasHtml(ctx: Context, base: String): Boolean {
        return try {
            val key = originKeyOf(base) ?: return false
            val f = htmlFile(ctx, key)
            f.isFile && f.length() > 512
        } catch (_: Throwable) {
            false
        }
    }

    fun readHtml(ctx: Context, base: String): String? {
        return try {
            val key = originKeyOf(base) ?: return null
            val f = htmlFile(ctx, key)
            if (f.isFile && f.length() > 512) f.readText(Charsets.UTF_8) else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * base 这台服务器有没有一份「能真跑起来」的资源副本：至少一个 js。
     * 只有 HTML 没有 js 时回退是白屏，还不如直接出原生错误页（更明确）。
     */
    fun hasAssets(ctx: Context, base: String): Boolean {
        return try {
            val key = originKeyOf(base) ?: return false
            dirOf(ctx, key).listFiles()?.any { it.name.endsWith(".js") && it.length() > 0 } == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * base 这台服务器的资源副本统计：（文件个数, 总字节）。
     * 只数资源（js/css/woff），不含入口 HTML 副本 —— 两者在诊断页分开显示才有意义：
     * 「HTML 有、资源 0」说明页面存下来了但 js 没存上（离线回退会故意拒绝这种状态）。
     */
    fun assetStats(ctx: Context, base: String): Pair<Int, Long> {
        return try {
            val key = originKeyOf(base) ?: return Pair(0, 0L)
            val files = dirOf(ctx, key).listFiles()
                ?.filter { it.isFile && it.name != "__index.html" } ?: emptyList()
            Pair(files.size, files.sumOf { it.length() })
        } catch (_: Throwable) {
            Pair(0, 0L)
        }
    }

    /** 清空所有代的缓存（设置页「清除离线缓存」用；正常路径不需要调） */
    fun clear(ctx: Context) {
        try { root(ctx).listFiles()?.forEach { it.deleteRecursively() } } catch (_: Throwable) { }
        lastPrefetchAt = 0L
        lastPrefetchKey = ""
        Log.i(TAG, "cache cleared")
    }
}
