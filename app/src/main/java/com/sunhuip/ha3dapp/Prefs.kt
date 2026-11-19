/*
 * Prefs.kt — 连接地址的应用内设置（SharedPreferences 持久化）。
 * Config.kt 里的值只作为默认值：用户在设置页改过后，以设置页为准。
 * 地址原样存取（只去首尾空格），不做任何参数改写。
 *
 * 2026-11-19 起恢复外网地址（只由用户在设置页填写，代码里不写死）：
 * 复用老版本写过的 remote_url 键，老用户无需重填。
 */
package com.sunhuip.ha3dapp

import android.content.Context

object Prefs {
    private const val NAME = "shell"
    private const val K_LOCAL = "local_url"
    private const val K_LOCAL_SELECTED = "local_selected"
    private const val K_APP_VER = "app_version"
    private const val K_KEEP_AWAKE = "keep_awake"
    private const val K_AUTH_TOKEN = "auth_token"
    private const val K_REMOTE_URL = "remote_url"
    private const val K_HOME_SSIDS = "home_ssids"
    private const val K_NETWORK_MODE = "network_mode"

    /**
     * 登录 token（服务端 session id）。存在原生层、不依赖 WebView cookie，
     * 因此内外网地址切换——origin 变了、cookie 失效——仍能用它重新登录。
     * 服务端把它当 bearer credential，泄露等价于会话被盗，故只存应用私有目录。
     */
    fun authToken(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_AUTH_TOKEN, null)
            ?.takeIf { it.isNotBlank() }

    fun setAuthToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_AUTH_TOKEN, token.trim()).apply()
    }

    fun clearAuthToken(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .remove(K_AUTH_TOKEN).apply()
    }

    /**
     * 内网地址列表（一行一个，按优先级从上到下）。用户没存过时用 Config 出厂默认。
     * 老版本的单地址字符串天然就是「只有一个元素的列表」，升级无需迁移。
     */
    fun localList(ctx: Context): List<String> {
        val raw = localRaw(ctx)
        if (raw.isNullOrBlank()) return Config.DEFAULT_LOCAL_URLS
        return raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { Config.DEFAULT_LOCAL_URLS }
    }

    /**
     * 首个内网地址（兼容旧调用点）＝ 设置页下拉选定的地址；
     * 选中的地址不在列表里（被删/改过）时回落列表第一个，列表空回落出厂默认。
     */
    fun local(ctx: Context): String {
        val list = localList(ctx)
        val sel = localSelected(ctx)
        return if (sel != null && list.contains(sel)) sel else list.first()
    }

    /** 设置页下拉选中的内网地址（存 URL 原文；未选过/已失效 = null） */
    fun localSelected(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_LOCAL_SELECTED, null)
            ?.takeIf { it.isNotBlank() }

    fun setLocalSelected(ctx: Context, url: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_LOCAL_SELECTED, url.trim()).apply()
    }

    /** 用户在设置页存的原始内网地址串（未设置 = null） */
    fun localRaw(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_LOCAL, null)

    /** 单地址写入（兼容旧调用点） */
    fun set(ctx: Context, local: String) = setLocalList(ctx, listOf(local))

    fun setLocalList(ctx: Context, urls: List<String>) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_LOCAL, urls.joinToString("\n") { it.trim() }.trim())
            .apply()
    }

    /* ---------------- 网络环境判定（见 NetEnv.kt / 2026-11-19 混合策略） ---------------- */

    /**
     * 外网入口（JMGLink 等）。**只由用户在设置页填写，代码里不写死任何真实地址**。
     * 复用老版本的 remote_url 键，老用户升级后无需重填。未设置 = 空串。
     */
    fun remote(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_REMOTE_URL, "") ?: ""

    fun setRemote(ctx: Context, remote: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_REMOTE_URL, remote.trim())
            .apply()
    }

    /**
     * 家里 WiFi 名称，多个用英文逗号分隔（兼容 2.4G/5G 双频）。
     * 空 = 没配家里 WiFi：自动模式下读到 SSID 也无法比对，按「读不到」处理（默认在家）。
     */
    fun homeSsids(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_HOME_SSIDS, "") ?: ""

    fun setHomeSsids(ctx: Context, ssids: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_HOME_SSIDS, ssids.trim())
            .apply()
    }

    /**
     * 网络模式：auto（自动读 SSID）/ home（强制在家）/ away（强制外出）。
     * 未设置 = auto。取值非法时回落 auto，防止旧数据/手改 SharedPreferences 把壳卡死。
     */
    fun networkMode(ctx: Context): String {
        val v = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_NETWORK_MODE, null)
        return when (v) {
            Config.NETWORK_MODE_HOME, Config.NETWORK_MODE_AWAY -> v
            else -> Config.NETWORK_MODE_AUTO
        }
    }

    fun setNetworkMode(ctx: Context, mode: String) {
        val v = when (mode) {
            Config.NETWORK_MODE_HOME, Config.NETWORK_MODE_AWAY -> mode
            else -> Config.NETWORK_MODE_AUTO
        }
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_NETWORK_MODE, v)
            .apply()
    }

    /**
     * 上次连到的服务端版本号（根 package.json 的 version）。
     * 用作离线缓存的「代」：发版就换目录，旧缓存整体作废。
     * 同步可读，不需要等网络，冷启动时命中判断不会因为取版本而卡住。
     */
    fun appVersion(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_APP_VER, null)

    fun setAppVersion(ctx: Context, ver: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_APP_VER, ver.trim())
            .apply()
    }

    /**
     * 屏幕常亮开关。未设置过 = 用 Config 的默认值（默认关）；
     * 用户在设置页拨过开关后就以这里为准（存 false 也不会再回落到默认值）。
     */
    fun keepAwake(ctx: Context): Boolean = try {
        val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (sp.contains(K_KEEP_AWAKE)) sp.getBoolean(K_KEEP_AWAKE, false)
        else Config.KEEP_SCREEN_ON
    } catch (_: Throwable) {
        false
    }

    fun setKeepAwake(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(K_KEEP_AWAKE, on)
            .apply()
    }
}
