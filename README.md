# ha3d-app — HA 3D Home 空壳 App

Android WebView 空壳：嵌套 ha-3d-home 页面，沉浸式全屏（比浏览器 PWA 可靠），屏幕常亮。
**固定直连配置的内网地址，不做启动探测、不做外网回退**：主框架加载失败直接显示错误页，
可点「重试」或进服务器设置改地址。

> 2026-11-15：外网（JMGLink）在手机端即使地址正确也访问不通，**外网入口整体移除**，只保留内网直连。

## 地址清单（2026-11-15）

| 用途 | 地址 |
|---|---|
| 内网正式（出厂默认） | `http://192.168.31.16:8099/index.html` |
| 内网测试 A | `http://192.168.31.78:8099/index.html` |
| 内网测试 B / Vite dev | `http://192.168.31.229:5190/index.html` |

设置页（长按桌面图标「服务器设置」）可下拉切换这三个内网预设，也可手填任意地址；
清空输入框后保存 = 恢复默认的内网正式地址。

## 需要改的地方（只有一个文件）

`app/src/main/java/com/sunhuip/ha3dapp/Config.kt`

| 常量 | 说明 |
|---|---|
| `OFFICIAL_LOCAL_URL` | 内网正式地址 |
| `TEST_LOCAL_URL_A` / `TEST_LOCAL_URL_B` | 两个内网测试地址 |
| `LOCAL_URL` | 出厂默认入口，指向 `OFFICIAL_LOCAL_URL` |
| `PRESETS` | 设置页下拉预设，带「正式 / 测试 / 调试」标签 |
| `KEEP_SCREEN_ON` / `SHOW_STATUS_BAR` | 屏幕常亮 / 状态栏模式 |
| `RECHECK_DEBOUNCE_MS` | 错误页态下网络恢复自动重试的防抖毫秒 |

> 主框架加载失败的处理写在 `MainActivity.onMainFrameFailed()`：直接显示错误页，不回退外网。
> 老版本存过的 `remote_url` 键已不再读取，留着不影响运行。

## 构建方式（二选一）

### 方式 A：GitHub Actions 云端构建（本地零环境，推荐）

1. 推到 GitHub 仓库（main 分支）；
2. Actions 自动构建（或手动 Run workflow）；
3. 到该次运行的 Artifacts 下载 `ha3d-app-debug.apk`，手机安装（需允许「未知来源应用」）。

工程**自带 gradlew**（发行地址指向腾讯镜像），但 CI 里不依赖 wrapper jar：
由 `gradle/actions/setup-gradle` 装 Gradle 8.7 后直接 `gradle assembleDebug`，本地零环境。

### 方式 B：本地 CLI 构建（已在本机装好，2026-11-14）

本机已就绪：JDK 17（`/Library/Java/JavaVirtualMachines/microsoft-17.jdk`）、
Android SDK（`~/Library/Android/sdk`，含 platforms;android-34 / build-tools;34.0.0 / cmdline-tools / platform-tools，全部来自腾讯镜像）、
Gradle 8.10.2（`~/tools/gradle-8.10.2`）。**工程自带 gradlew**（发行地址已指向腾讯镜像），直接：

```bash
cd ha3d-app
JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-17.jdk/Contents/Home ./gradlew -Pcn assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

注意：
- 别用 `brew` 装的 `gradle`（9.x，与 AGP 8.5 不兼容），一律走 `./gradlew`（8.10.2）；
- `-Pcn` 走阿里云 Maven 镜像；`local.properties` 里是 sdk.dir（已 gitignore）；
- 升级 SDK 组件可用 `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/Library/Android/sdk "platforms;android-35"` 等。

### 首次环境搭建记录（换机器参考）

```bash
brew install --cask temurin@17                                  # JDK 17
# SDK 组件（腾讯镜像，sdkmanager 的 --proxy 参数对本镜像无效，直接下 zip 手工摆放）：
#   http://mirrors.cloud.tencent.com/AndroidSDK/ 下：
#   commandlinetools-mac-9862592_latest.zip → 解压到 $ANDROID_HOME/cmdline-tools/latest
#   platform-34-ext7_r03.zip                → $ANDROID_HOME/platforms/android-34
#   build-tools_r34-macosx.zip              → 解压后根目录是 android-14，改名 $ANDROID_HOME/build-tools/34.0.0
#   platform-tools_r37.0.3-darwin.zip       → $ANDROID_HOME/platform-tools
# 许可文件（ licenses/android-sdk-license ）：24333f8a63b6825ea9c5514f83c2829b004d1fee
#                                               8933bad161af4178b1185d1a37fbf41ea5269c55
#                                               d56f5187479451eabf01fb78af6dfcb131a6481e
curl -O https://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip && unzip -d ~/tools  # Gradle
cd ha3d-app && ~/tools/gradle-8.10.2/bin/gradle -Pcn wrapper \
  --gradle-distribution-url "https://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip"  # 生成 wrapper
```

## 安装到手机

1. APK 拷到手机（微信文件传输 / 数据线 / 局域网 HTTP 都行）；
2. 设置 → 安全 → 允许安装未知来源应用（华为：设置 → 系统和更新 → 开发人员选项外的一般路径「更多安全设置」）；
3. 点 APK 安装，桌面出现「HA 3D Home」图标。

## 目录结构

```
ha3d-app/
├── .github/workflows/build.yml      # CI 构建
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts             # minSdk 26 / target 34 / AGP 8.5.2 / Kotlin 1.9.24
    └── src/main/
        ├── AndroidManifest.xml      # INTERNET 权限、明文 HTTP 放行、singleTask、android:name=.App
        ├── java/com/sunhuip/ha3dapp/
        │   ├── App.kt               # Application：初始化壳层 + 启动预取
        │   ├── Config.kt            # ★ 入口与开关
        │   ├── Prefs.kt             # 地址 + 缓存代（服务端版本号）持久化
        │   ├── Shell.kt             # ★ WebView 单例 + client + 错误页/离线回退 + 运行诊断
        │   ├── WebCache.kt          # ★ 离线缓存（按服务端版本分代 + 按版本化 URL 命中）
        │   ├── SettingsActivity.kt  # 应用内服务器设置页（含运行诊断 / 清缓存）
        │   └── MainActivity.kt      # 窗口装饰 + attach/detach（不销毁 WebView）
        └── res/
            ├── drawable/splash_bg.xml / ic_splash_logo.xml   # 冷启动启动图
            ├── values/themes.xml                             # windowBackground = 启动图
            ├── values-v31/themes.xml                         # Android 12+ SplashScreen
            └── mipmap-*/                                     # 自适应图标（矢量房子）
```

## 壳层行为（2026-11-16）

**启动链路**

1. 点图标 → 主题的 `windowBackground` 立刻画出启动图（底色 #EEF1F4 + 图标），不闪白；
2. `App.onCreate` 建一个离屏 WebView **预热** Chromium 库（省 30~80ms），同时预取入口 HTML；
3. `MainActivity` 把**进程内单例 WebView**挂上窗口（不是新建）；
4. 页面 `index.html` 里的静态骨架（`#boot-static`）先顶着 → Vue 挂载后交给 `BootOverlay` 的版面骨架 →
   **等 3D 渲染循环首帧上屏**才淡出。中途不会出现「骨架没了、3D 还没出来」的二次白屏。

**WebView 生命周期**

- `Shell` 持有唯一实例（`applicationContext` 构造），`MainActivity` 只做 attach/detach；
- 按返回退出、被系统回收 Activity，再进来都是**把同一个页面重新挂上窗口**，不重新加载；
- `onDestroy` 不 `destroy()`（`Config.REUSE_WEBVIEW = false` 可退回旧行为）；
- `setRendererPriorityPolicy(BOUND, waivedWhenNotVisible = false)`：WebView 不可见时 renderer 不降级，
  切出去看个消息再回来不会白屏重载。

**缓存（两层，都不看文件时间）**

| 依据 | 怎么用 |
|---|---|
| 服务端根 `package.json` 的 `version`（`GET /api/version`） | 决定缓存「代」：目录 `files/webcache/<version>/`，发版即换目录，旧代迁移后清理 |
| URL 自带的版本（Vite 文件哈希 / 插件 `?v=<plugin.json version>` / 旧站 `?v=N`） | 决定单个文件是否命中：版本一变 URL 就变，天然不命中 |

只缓存 js / css / woff2 和入口 HTML；GLB、`catalog.json`、插件模型不碰（文件名不带版本，改了看不出来），
那类文件仍交给 WebView 的 ETag 协商。填充不依赖预取——**任何成功加载过的 URL 都会落盘**。

**断网兜底**：主框架加载失败时，用「上次成功的 HTML + 本地 js/css 副本」重建页面，顶部浮一条
「离线模式…重新连接」提示；没有完整副本才出原生错误页。网络恢复会自动重载。

**运行诊断**：设置页底部显示硬件加速真实值、WebView 包名+版本、文本缩放、renderer 优先级、缓存代与占用，
并带一个「清除离线缓存」按钮 —— 排障截一张图就够，不用接 adb。
