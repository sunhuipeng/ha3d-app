/*
 * Config.kt — 壳 App 的默认值（用户可在应用内「服务器设置」里覆盖，改动存 SharedPreferences，
 * 不用重新打包）。改默认值/行为开关才需要动这个文件并重出包。
 */
package com.sunhuip.ha3dapp

object Config {
    /**
     * 默认入口（不做地址探测）：启动 / 重试 / 从设置页返回时直接加载设置页存的地址，
     * 没存过就用这里。改这里需要重新打包。
     */
    const val LOCAL_URL = "http://192.168.31.229:5190/index.html?type=mobile"

    /** 外网入口默认值（空 = 不启用外网回退；内网主框架加载失败时自动切过去试一次） */
    const val REMOTE_URL = ""

    /** 设置页下拉预设（手动输入可覆盖为任意固定地址） */
    val PRESETS = listOf(
        "http://192.168.31.229:5190/index.html?type=mobile",  // 本地调试（Vite dev）
        "http://192.168.31.16:8099/index.html?type=mobile",   // 正式（Docker）
    )

    /** 屏幕常亮（展示板/控制面板常驻设备建议开） */
    const val KEEP_SCREEN_ON = true

    /**
     * 顶部状态栏模式：
     *  true  = 保留状态栏（能看到时间/信号/通知），透明底、深色图标，内容自动下移不被遮挡（默认）
     *  false = 完全沉浸（状态栏也隐藏，页面铺满整屏，下拉临时呼出）
     */
    const val SHOW_STATUS_BAR = true

    /** 错误页状态下网络恢复后自动重试的防抖毫秒数 */
    const val RECHECK_DEBOUNCE_MS = 800L
}
