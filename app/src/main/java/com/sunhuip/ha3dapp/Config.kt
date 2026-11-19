/*
 * Config.kt — 壳 App 的默认值（用户可在应用内「服务器设置」里覆盖，改动存 SharedPreferences，
 * 不用重新打包）。改默认值/行为开关才需要动这个文件并重出包。
 */
package com.sunhuip.ha3dapp

object Config {
    /**
     * 出厂默认内网地址（Docker 8099）。仅为「首次安装、设置页从没存过」时的兜底，
     * 用户在设置页可维护成任意多个地址——这里不再有写死的预设列表。
     */
    const val OFFICIAL_LOCAL_URL = "http://192.168.31.16:8099/index.html"

    /** 出厂默认内网地址列表（一行一个；用户设置页保存后以设置页为准） */
    val DEFAULT_LOCAL_URLS = listOf(OFFICIAL_LOCAL_URL)

    /* ---------------- 网络环境判定（见 NetEnv.kt） ---------------- */

    /** 自动：读 WiFi SSID 与「家里 WiFi」名单比对；读不到按在家处理 */
    const val NETWORK_MODE_AUTO = "auto"
    /** 手动：强制按在家处理（用内网地址） */
    const val NETWORK_MODE_HOME = "home"
    /** 手动：强制按外出处理（用外网地址） */
    const val NETWORK_MODE_AWAY = "away"

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
