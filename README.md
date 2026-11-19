# ha3d-app — HA 3D Home 空壳 App

> 🔗 项目介绍主页（功能演示、截图）：<https://sunhuipeng.github.io/ha3d-site/>

Android WebView 空壳：嵌套 ha-3d-home 页面，沉浸式全屏（比浏览器 PWA 可靠）。
**地址全部由用户在应用内设置页填写，代码里没有写死的预设列表**。

## 功能特性

- **内外网自动切换**：按「在家 / 外出」自动选地址——在家用内网地址列表（连不上依次探测后续地址），外出用外网地址；监听 WiFi 变化，切换环境主动重载（详见下方「网络环境切换」）
- **扫码授权登录**：设置页「扫码授权登录其他设备」——App 已登录时，扫 PC/电视登录页出示的一次性二维码批准对方免密登录；两端一内网一外网、打同一个服务即可（详见下方「登录（外网穿透时用）」）
- **内外网切换免重登**：原生 token（`Prefs.authToken`）在每次切换地址后按新 origin 预种登录 cookie，服务端重启也已落盘恢复（详见下方「登录」）
- **TV 遥控模式**：宽屏 + App 壳自动启用四区域方向导航，遥控器可直接操作页面（详见下方「TV / 遥控支持」）
- **离线兜底**：WebCache 按 origin 落盘 HTML + 关键静态资源，断网时页面照常打开并提示重连

## 网络环境切换（2026-11-19）

按「在家 / 外出」自动选地址，且切 WiFi 会主动重新判定、切换：

| 环境 | 用哪个地址 | 连不上怎么办 |
|---|---|---|
| 在家 | 「内网地址列表」第一个（启动直接加载，零延迟） | 依次探测列表里的后续地址，用第一个连得上的临时加载（不写回配置） |
| 外出 | 「外网地址」（单个，手动输入） | 直接显示错误页 / 离线副本，不探测 |

环境判定（`NetEnv.inHome`）：
1. 设置页选了「强制在家 / 强制外出」→ 按手动，不看 WiFi；
2. 自动模式 → 读当前 WiFi 名称，命中「家里 WiFi 名单」= 在家，读到但不在名单 = 外出；
3. **读不到 SSID（无定位权限 / 定位开关关 / 非 WiFi）或没配家里名单 → 默认在家**。

- 读 SSID 在 Android 10+ 要定位权限 + 定位开关；App 会自动申请权限，设置页「读取当前 WiFi」可一键填入名单。
- `ConnectivityManager.NetworkCallback` 监听网络变化：WiFi 切换导致环境变化时 `Shell.loadConfigured(force=true)` 主动重载。
- 家里配置的地址列表存在 `Prefs.localList()`（换行分隔，老的单地址数据自动兼容为一项）；外网地址 `Prefs.remote()`（复用老 `remote_url` 键）。
- 内外网切换后 cookie 因 origin 变化而失效，靠原生 token（`Prefs.authToken`）在每个新 origin 预种 cookie，免重新登录。

设置页（长按桌面图标「服务器设置」/ 错误页按钮 / 遥控设置键）可维护：内网地址（多行）、外网地址、家里 WiFi 名单、网络模式。清空内网地址保存 = 恢复出厂默认（`Config.DEFAULT_LOCAL_URLS`）。

## 默认值与行为开关（Config.kt）

**改出厂默认值 / 行为开关只需要动 `app/src/main/java/com/sunhuip/ha3dapp/Config.kt` 并重出包**；
用户在应用内设置页保存的地址、WiFi 名单、网络模式等存 SharedPreferences（`Prefs.kt`），优先级更高，不需要改代码。

| 常量 | 说明 |
|---|---|
| `OFFICIAL_LOCAL_URL` | 出厂默认内网地址（仅首次安装、从没设置过时用） |
| `DEFAULT_LOCAL_URLS` | 出厂默认内网地址列表（用户设置页保存后以设置页为准） |
| `NETWORK_MODE_AUTO` / `HOME` / `AWAY` | 网络模式：自动读 WiFi / 强制在家 / 强制外出 |
| `PROBE_TIMEOUT_MS` | 后续候选地址的可达性探测超时 |
| `KEEP_SCREEN_ON` / `SHOW_STATUS_BAR` | 屏幕常亮 / 状态栏模式的默认值 |
| `RECHECK_DEBOUNCE_MS` | 网络变化 / 错误页态重试的防抖毫秒 |
| `REUSE_WEBVIEW` | WebView 进程内单例（推荐 true，返回/设置页往返不重载） |
| `RELEASE_WEBVIEW_ON_PRESSURE` | 内存极紧时是否放掉 WebView（展示设备建议 false） |
| `OFFLINE_FALLBACK` | 主框架加载失败时回退离线副本 |

**改行为逻辑去哪个文件**（2026-11-19 起不再是单文件工程）：

| 要改什么 | 文件 |
|---|---|
| 在家/外出判定、SSID 读取、地址可达性探测 | `NetEnv.kt` |
| 地址解析与级联探测、WiFi 切换重载、扫码授权请求、离线回退、错误页 | `Shell.kt` |
| 内网地址列表 / 外网地址 / WiFi 名单 / 网络模式 / token 的持久化 | `Prefs.kt` |
| 设置页 UI（含扫码授权入口、当前连接显示） | `SettingsActivity.kt` |
| 离线缓存落盘与命中 | `WebCache.kt` |
| TV 遥控焦点 | `web/src/engine/tvFocus.js`（home 项目） |

## 构建方式
### 本地 CLI 构建（已在本机装好，2026-11-14）

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
        ├── AndroidManifest.xml      # INTERNET/定位权限、明文 HTTP 放行、singleTask、android:name=.App
        ├── java/com/sunhuip/ha3dapp/
        │   ├── App.kt               # Application：初始化壳层 + 启动预取
        │   ├── Config.kt            # ★ 默认值与开关
        │   ├── NetEnv.kt            # ★ 在家/外出判定（SSID）+ 地址可达性探测
        │   ├── Prefs.kt             # 地址列表 + token + 缓存代（服务端版本号）持久化
        │   ├── Shell.kt             # ★ WebView 单例 + client + 环境切换/逐地址探测 + 错误页/离线回退 + 运行诊断
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
| URL 自带的版本（Vite 文件哈希 / `?v=<plugin.json/item.json/package.json version>` / 旧站 `?v=N`） | 决定单个文件是否命中：版本一变 URL 就变，天然不命中 |

只缓存 js / css / woff2、**带 `?v=` 版本号的 GLB 模型**（2026-11-19 起纳入：插件/家具包/内置模型的 URL 都已版本化，
断网也能加载出 3D 模型；每个模型几 MB，由换代清理兜底）和入口 HTML；不带版本的 GLB、`catalog.json` 不碰，
那类文件仍交给 WebView 的 ETag 协商。填充不依赖预取——**任何成功加载过的 URL 都会落盘**。

**断网兜底**：主框架加载失败时，用「上次成功的 HTML + 本地 js/css 副本」重建页面，顶部浮一条
「离线模式…重新连接」提示；没有完整副本才出原生错误页。网络恢复会自动重载。

**运行诊断**：设置页底部显示硬件加速真实值、WebView 包名+版本、文本缩放、renderer 优先级、缓存代与占用，
并带一个「清除离线缓存」按钮 —— 排障截一张图就够，不用接 adb。

## TV / 遥控支持

App 内**宽屏时**网页会自动开启 TV 焦点模式（`web/src/engine/tvFocus.js`，浏览器里则需 URL 带 `tv=1`）；窄屏不启用，走普通触摸。开启后蓝色描边表示当前焦点，方向键在页面四个区域（左上信息 / 右上工具 / 右侧面板 / 左下楼层）内导航，到边界按位置切区，确认键点击。

**遥控按键映射**（`MainActivity.onKeyDown`）

| 遥控键 | 行为 |
|---|---|
| 方向键 ↑ ↓ ← → | `Shell.dispatchTvKey()` → `evaluateJavascript` 调页面 `__tvFocus.key(name)` 寻焦 |
| OK / 确认 / 中心键 | 同上，发 `ok` 对焦点元素执行点击 |
| 设置键 / 菜单键 | `Shell.openSettings()` 直接开应用内服务器设置页（不经网页） |
| 返回键 BACK | 走系统默认（WebView 可回退则回退，否则退出） |
| HOME 键 | 系统默认，壳不拦截 |

键盘长按的系统 repeat 会连续触发，页面侧寻焦是轻量查询、无需节流。网页侧的焦点导航与调试参数（`?tv=1&debug=1`）详见 `ha-3d-home` 的 README。

## 登录（外网穿透时用）

服务端可配置单账号登录（见 `ha-3d-home` README「登录与外网访问」）。启用后 App 打开会被重定向到登录页，**App 用账号密码登录**（遥控器/触摸都可用）。

**扫码授权登录（2026-11-19 定稿授权语义）**：App 已登录 = 授权方。在**设置页**点「扫码授权登录其他设备」，扫另一台设备登录页展示的一次性二维码，App 用自己的原生 token 调 `/api/auth/qr-approve` 批准（不写 cookie、不改自身登录态），对方登录页轮询到 `approved` 后自动兑换 session 登录。跨网络（对方外网、本机内网，或反之）同样成立：**App 只从二维码里提取 code，不信任也不打开其中内嵌的地址**（那是待登录方的 origin，App 未必连得上）；授权请求打 App 自己已登录的服务地址（`currentBase` 的 origin），两端打到同一后端进程即可在服务端内存命中同一条 code。前提：两端连的是同一个后端进程（多实例内存不共享）。

要点：

- 入口在设置页（`SettingsActivity` 的「扫码授权登录其他设备」按钮）；未登录（无原生 token）时按钮拒绝并提示先登录。
- 授权请求经 WebView `evaluateJavascript` 发出，手动带 `X-HA3D-Token`（`apiAuth.js` 的守卫跳过 `/api/auth/*`，不会自动注入）；结果经 `AppShell.scanAuthResult()` 回调原生 Toast。需要 `CAMERA` 权限（Manifest 已声明，扫码库内部申请运行时权限），`CaptureActivity` 覆写为竖屏。
- QR 一次性码 60 秒有效、单次消费（状态机 pending → approved → consumed）。
- cookie 是登录 session 的载体之一：`Shell.init()` 显式开 `CookieManager`，`setAcceptThirdPartyCookies` 放行穿透域名，`onPause` 时 `flush()` 落盘。

**内外网切换免登录（原生 token）**：cookie 按 origin 隔离，单靠它换地址必丢登录态。App 另有一层——登录成功后服务端在响应体返回 `token`（账号密码登录由 `login.js` 调 `AppShell.saveToken()`），存进 `Prefs.authToken`（应用私有目录）。之后：

- `loadConfigured()` 在每次 `loadUrl()` 前调 `seedAuthCookie()`，把原生 token 预置成目标 origin 的 `ha3d_session` cookie——首屏 document 导航带不了自定义 header，靠这颗 cookie 过服务端门禁；
- 页面内 `/api/*` 请求由前端 `services/apiAuth.js` 注入 `X-HA3D-Token` 头（经 `AppShell.getToken()` 取），服务端 `guard()` 两种来源都认。

因此**设置页切换内网/外网地址无需重新登录**，服务端 session 表是同一张。**session 服务端落盘（2026-11-19）**：`data/auth-sessions.json` 整表持久化、重启自动恢复，服务重启后 App 免重新登录（token 在原生层保留且服务端 session 不再丢失）；服务端删除该文件 = 强制全部设备重登。退出登录走 `AppShell.logout()` → `Shell.clearAuth()` 清原生 token 与各 origin cookie，服务端 logout 同步清 token 并落盘。

其他要点：扫码授权请求必须由 WebView 发出（`Shell.processScanPayload()` 用 `evaluateJavascript` 完成，绝对地址指向 `currentBase` 的 origin）。

## 一键打包（Windows / mac 通用）

根目录 `package.json` 是给 VS Code「npm 脚本」面板用的入口（不是 Node 工程，勿 `npm install`）：

| 脚本 | 作用 |
|---|---|
| `npm run apk` | 打 debug APK（`assembleDebug`，依赖走国内镜像） |
| `npm run apk:release` | 打**带签名**的 release（没配签名则退回 unsigned） |
| `npm run clean` | 清理构建 |
| `npm run apk:install` | `adb install -r` 装 debug 包 |
| `npm run logcat` | 看壳子日志（HA3DShell / HA3DWebCache） |

`scripts/gradle.cjs` 做平台分发：Windows 跑 `gradlew.bat`（JDK 默认取仓库旁 `_tools/jdk17`，可用 `HA3D_JAVA_HOME` 覆盖）；mac 跑 `./gradlew`，会自动补 `gradlew` 可执行位、用 `/usr/libexec/java_home` 找 JDK 17、兜底 `~/Library/Android/sdk`。adb 脚本（`apk:install` / `logcat`）需把 `$ANDROID_HOME/platform-tools` 加进 PATH。产物：`app/build/outputs/apk/debug/app-debug.apk` 与 `app/build/outputs/apk/release/app-release.apk`。

> 脚本名曾用 `install`，那是 npm 生命周期钩子名——在本目录习惯性敲 `npm install` 会误触发 `adb install`，已改名 `apk:install`。

### release 签名（本机私有，绝不入库）

`app/build.gradle.kts` 从**已被 gitignore** 的 `local.properties` 读签名凭据，没配的机器打 release 只会得到 unsigned 包（编译不失败）。要在某台机器出可安装的正式包，两步：

1. 生成 keystore（只需一次，放在 `app/` 下——`*.jks` 已被 gitignore）：

   ```bash
   keytool -genkeypair -v -keystore app/release.jks -storetype JKS \
     -keyalg RSA -keysize 2048 -validity 10000 -alias ha3d \
     -dname "CN=HA 3D Home, OU=HA3D, O=Sunhuip, C=CN"
   ```

2. 在该机 `local.properties` 追加 4 行（密码换成你自己的）：

   ```properties
   release.storeFile=app/release.jks
   release.storePassword=你的store密码
   release.keyAlias=ha3d
   release.keyPassword=你的key密码
   ```

`npm run apk:release` 成功后产物名是 `app-release.apk`（带签名）；若看到 `app-release-unsigned.apk` 说明这台机器没配或配错了，装不上。

⚠️ **keystore 和密码绝不入库**，换机器 / 重装系统前务必自行备份 `app/release.jks` + `local.properties` 里的 4 行——丢了就无法给同一个应用发更新（只能换包名或让用户卸载重装）。`build.gradle.kts` 里没有任何明文密码。
