/*
 * Prefs.kt — 连接地址的应用内设置（SharedPreferences 持久化）。
 * Config.kt 里的值只作为默认值：用户在设置页改过后，以设置页为准。
 */
package com.sunhuip.ha3dapp

import android.content.Context

object Prefs {
    private const val NAME = "shell"
    private const val K_LOCAL = "local_url"
    private const val K_REMOTE = "remote_url"

    /** 内网入口（用户清空/未设置时回退 Config 默认值） */
    fun local(ctx: Context): String = localRaw(ctx)?.takeIf { it.isNotBlank() } ?: Config.LOCAL_URL

    /** 用户在设置页存的原始内网地址（未设置 = null，用于区分「默认候选列表」和「手动指定」） */
    fun localRaw(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_LOCAL, null)

    /** 外网入口（空 = 不启用外网回退） */
    fun remote(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(K_REMOTE, null) ?: Config.REMOTE_URL

    fun set(ctx: Context, local: String, remote: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_LOCAL, local.trim())
            .putString(K_REMOTE, remote.trim())
            .apply()
    }
}
