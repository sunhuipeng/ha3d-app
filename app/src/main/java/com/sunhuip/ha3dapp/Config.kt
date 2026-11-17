/*
 * Config.kt — 壳 App 的默认值（用户可在应用内「服务器设置」里覆盖，改动存 SharedPreferences，
 * 不用重新打包）。改默认值/行为开关才需要动这个文件并重出包。
 */
package com.sunhuip.ha3dapp

/** 设置页下拉的一项：label 给人看（区分正式 / 测试），url 是真实入口 */
data class Preset(val label: String, val url: String)

object Config {
    /** 内网正式地址（Docker 8099）：出厂默认入口 */
    const val OFFICIAL_LOCAL_URL = "http://192.168.31.16:8099/index.html"

    /** 内网测试地址 A（Vite dev 5190） */
    const val TEST_LOCAL_URL_A = "http://192.168.31.78:5190/index.html"

    /** 内网测试地址 B（Vite dev 5190） */
    const val TEST_LOCAL_URL_B = "http://192.168.31.229:5190/index.html"

    /**
     * 默认入口（不做地址探测、不做外网回退）：启动 / 重试 / 从设置页返回时直接加载
     * 设置页存的地址，没存过就用这里。出厂默认走内网正式地址，改这里需要重新打包。
     *
     * 外网（JMGLink）在手机端即使地址正确也访问不通，2026-11-15 起整体移除，
     * 只保留内网直连；主框架加载失败直接显示错误页，由用户重试或进设置页改地址。
     */
    const val LOCAL_URL = OFFICIAL_LOCAL_URL

    /** 设置页下拉预设（手动输入可覆盖为任意固定地址） */
    val PRESETS = listOf(
        Preset("正式 · 192.168.31.16:8099", OFFICIAL_LOCAL_URL),
        Preset("测试 · 192.168.31.78:5190", TEST_LOCAL_URL_A),
        Preset("调试 · 192.168.31.229:5190", TEST_LOCAL_URL_B),
    )

    /** 屏幕常亮的**默认值**（设置页可覆盖，用户拨过开关后以设置页为准）。
     *  2026-11-17 改为 false：普通手机上常亮费电；常驻展示板在设置页打开即可。 */
    const val KEEP_SCREEN_ON = false

    /**
     * 顶部状态栏模式：
     *  true  = 保留状态栏（能看到时间/信号/通知），透明底、深色图标，内容自动下移不被遮挡（默认）
     *  false = 完全沉浸（状态栏也隐藏，页面铺满整屏，下拉临时呼出）
     */
    const val SHOW_STATUS_BAR = true

    /** 错误页状态下网络恢复后自动重试的防抖毫秒数 */
    const val RECHECK_DEBOUNCE_MS = 800L

    /* ---------------- WebView 生命周期 / 缓存（见 Shell.kt、WebCache.kt） ---------------- */

    /**
     * WebView 实例复用：Activity 销毁时只 detach 不 destroy。
     *  true = 进程内单例，按返回退出再进 / 从设置页返回都不重载（推荐）
     *  false = 退回旧行为，每次重建 WebView（最省内存，但每次都要重跑一遍加载链路）
     */
    const val REUSE_WEBVIEW = true

    /**
     * 内存极紧时主动放掉 WebView（onTrimMemory >= COMPLETE）。
     * 展示设备建议 false：宁可占着内存也别让屏幕上的界面被拆掉重建。
     */
    const val RELEASE_WEBVIEW_ON_PRESSURE = false

    /**
     * 离线回退：主框架加载失败时，用上次成功抓到的 HTML + 本地 js/css 副本重建页面。
     * 页面能起来，由它自己显示「连不上 HA / 正在重连」，比甩原生错误页有用。
     * 没有完整副本时（从没成功加载过）仍走原生错误页。
     */
    const val OFFLINE_FALLBACK = true
}
