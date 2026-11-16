/*
 * App.kt — 进程级初始化（Application）。
 *
 * 这里做的三件事有个共同前提：都是「一次性、且必须早于第一个 WebView 创建」——
 *   1. renderer 优先级策略（进程级 static，WebView 建好之后再设不生效）
 *   2. WebView 预热（先把 Chromium 库加载掉，把 30~80ms 从首屏挪到启动早期）
 *   3. 入口 HTML 预取落盘（断网 / 服务器不可达时页面能起来，而不是甩原生错误页）
 */
package com.sunhuip.ha3dapp

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        /* 注意：renderer 优先级不是启动时能直接设的——WebView.setRendererPriorityPolicy
           是实例方法、且每个进程只生效一次，必须在第一个 WebView 实例创建之后调用。
           具体位置在 Shell.warmUp()/ensure() 里（见 Shell.applyRendererPolicy）。 */
        Shell.init(this)

        /* 预取只写磁盘、不占内存优先路径：正常加载永远走网络（保证前端改动即时生效），
           副本只用于主框架加载失败时的回退。 */
        WebCache.prefetchAsync(this)
    }
}
