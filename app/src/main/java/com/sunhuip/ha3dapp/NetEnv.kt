/*
 * NetEnv.kt — 网络环境判定（2026-11-19 简化后的单一真相）。
 *
 * 地址策略（用户定稿，无任何网络探测）：
 *   在家 → 使用设置页下拉选定的内网地址，失败就走离线副本/错误页
 *   外出 → 使用外网地址（设置页填，代码里不写死）
 *
 * 环境判定优先级（inHome）：
 *   1. 手动模式 home / away → 直接定，不看 WiFi
 *   2. 自动模式 → 读当前 WiFi SSID，与「家里 WiFi 名单」比对：
 *        命中 → 在家；读到但不在名单 → 外出
 *   3. 读不到 SSID（无权限 / 定位关 / 非 WiFi）或没配家里名单 → 默认「在家」（用户 11-19 确认）
 *
 * 注意：SSID 在 Android 10+ 需要定位权限 + 定位开关，读不到是常态而非异常，
 * 因此一律按「默认在家」兜底，绝不因此误切到外网地址导致白屏。
 */
package com.sunhuip.ha3dapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

object NetEnv {

    /** 当前是否按「在家」处理（整个 App 判定入口，Shell 与设置页都走这里） */
    fun inHome(ctx: Context): Boolean = when (Prefs.networkMode(ctx)) {
        Config.NETWORK_MODE_HOME -> true
        Config.NETWORK_MODE_AWAY -> false
        else -> inHomeBySsid(ctx)   // auto
    }

    private fun inHomeBySsid(ctx: Context): Boolean {
        val ssid = currentSsid(ctx)
        val homeList = Prefs.homeSsids(ctx).split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .toSet()
        /* 读不到 SSID、或压根没配家里名单 → 无法比对 → 默认在家（用户 11-19 确认） */
        if (ssid == null || homeList.isEmpty()) return true
        /* 忽略大小写：名单是用户手输的，「Home」和手机上报的「home」应视为同一个 */
        return homeList.any { it.equals(ssid, ignoreCase = true) }
    }

    /**
     * 读当前 WiFi SSID；读不到返回 null（不是异常，是常态）。
     * 去掉 WifiManager 给 SSID 套的引号，并过滤各种「未知」哨兵值。
     */
    fun currentSsid(ctx: Context): String? {
        if (!canReadSsid(ctx)) return null
        return try {
            @Suppress("MissingPermission", "DEPRECATION")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            @Suppress("MissingPermission", "DEPRECATION")
            val raw = wm.connectionInfo?.ssid ?: return null
            val s = raw.trim().trim('"')
            when (s) {
                "", "<unknown>", "00:00:00:00:00:00", "0x" -> null
                else -> s
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Android 10(API 29)+ 读 SSID 要定位权限 + 定位开关；8/9 只需 WIFI_STATE（安装即授予） */
    private fun canReadSsid(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!fine && !coarse) return false
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !lm.isLocationEnabled) return false
        }
        return true
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** 自动模式下是否需要向用户申请定位权限（用于读 SSID） */
    fun needLocationPermission(ctx: Context): Boolean =
        Prefs.networkMode(ctx) == Config.NETWORK_MODE_AUTO &&
            !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** 诊断用：一句话说明当前环境判定，设置页「运行诊断」展示 */
    fun describe(ctx: Context): String {
        val mode = when (Prefs.networkMode(ctx)) {
            Config.NETWORK_MODE_HOME -> "手动·在家"
            Config.NETWORK_MODE_AWAY -> "手动·外出"
            else -> "自动"
        }
        val ssid = currentSsid(ctx) ?: "读不到"
        val home = Prefs.homeSsids(ctx).ifBlank { "未配置" }
        return "网络环境：$mode｜WiFi：$ssid｜家里名单：$home｜判定：${if (inHome(ctx)) "在家" else "外出"}"
    }
}
