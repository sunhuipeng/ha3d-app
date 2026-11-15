/*
 * Prefs.kt — 连接地址的应用内设置（SharedPreferences 持久化）。
 * Config.kt 里的值只作为默认值：用户在设置页改过后，以设置页为准。
 */
package com.sunhuip.ha3dapp

import android.content.Context
import android.net.Uri

object Prefs {
    private const val NAME = "shell"
    private const val K_LOCAL = "local_url"
    private const val K_REMOTE = "remote_url"

    /**
     * 历史地址里残留的 ?type=pc|mobile 等参数已废止：读取与保存时统一剥掉，
     * 只删 type，其它 query 参数与 fragment 原样保留，老版本设置页存过的值也能平滑过渡。
     */
    private fun stripTypeParam(url: String): String {
        if (!url.contains("type=")) return url
        return try {
            val uri = Uri.parse(url)
            if (uri.getQueryParameter("type") == null) return url
            val rest = uri.queryParameterNames.filter { it != "type" }
            val b = uri.buildUpon().clearQuery()
            rest.forEach { k -> uri.getQueryParameters(k).forEach { v -> b.appendQueryParameter(k, v) } }
            b.build().toString()
        } catch (_: Throwable) { url }
    }

    /** 内网入口（用户清空/未设置时回退 Config 默认值） */
    fun local(ctx: Context): String =
        stripTypeParam(localRaw(ctx)?.takeIf { it.isNotBlank() } ?: Config.LOCAL_URL)

    /** 用户在设置页存的原始内网地址（未设置 = null，用于区分「默认候选列表」和「手动指定」） */
    fun localRaw(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_LOCAL, null)

    /** 外网入口（空 = 不启用外网回退） */
    fun remote(ctx: Context): String =
        stripTypeParam(
            ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(K_REMOTE, null) ?: Config.REMOTE_URL
        )

    fun set(ctx: Context, local: String, remote: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(K_LOCAL, stripTypeParam(local.trim()))
            .putString(K_REMOTE, stripTypeParam(remote.trim()))
            .apply()
    }
}
