/*
 * Prefs.kt — 连接地址的应用内设置（SharedPreferences 持久化）。
 * Config.kt 里的值只作为默认值：用户在设置页改过后，以设置页为准。
 * 地址原样存取（只去首尾空格），不做任何参数改写。
 *
 * 2026-11-15 起移除外网入口：只存内网地址。老版本写过的 remote_url 键不再读取，
 * 留着不影响运行（下次 set 也不会再写）。
 */
package com.sunhuip.ha3dapp

import android.content.Context

object Prefs {
    private const val NAME = "shell"
    private const val K_LOCAL = "local_url"
    private const val K_APP_VER = "app_version"

    /** 内网入口（用户清空/未设置时回退 Config 默认值） */
    fun local(ctx: Context): String =
        localRaw(ctx)?.takeIf { it.isNotBlank() } ?: Config.LOCAL_URL

    /** 用户在设置页存的原始内网地址（未设置 = null，用于区分「默认候选列表」和「手动指定」） */
    fun localRaw(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_LOCAL, null)

    fun set(ctx: Context, local: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_LOCAL, local.trim())
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
}
