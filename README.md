# ha3d-app — HA 3D Home 空壳 App

Android WebView 空壳：嵌套 ha-3d-home 页面，沉浸式全屏（比浏览器 PWA 可靠），
**内网 / 外网入口自动切换**（启动与网络变化时探测，内网通走内网，不通走外网），屏幕常亮。

## 需要改的地方（只有一个文件）

`app/src/main/java/com/sunhuip/ha3dapp/Config.kt`

| 常量 | 说明 |
|---|---|
| `LOCAL_URL` | 内网入口，当前 `http://192.168.31.16:8077/` |
| `REMOTE_URL` | 外网入口，**当前留空**，拿到外网地址（frp / DDNS / 反代）后填入即可启用回退 |
| `LOCAL_PROBE_URL` | 内网可达性探测端点，默认探测入口本身 |
| `KEEP_SCREEN_ON` | 屏幕常亮开关 |

## 构建方式（二选一）

### 方式 A：GitHub Actions 云端构建（本地零环境，推荐）

1. 推到 GitHub 仓库（main 分支）；
2. Actions 自动构建（或手动 Run workflow）；
3. 到该次运行的 Artifacts 下载 `ha3d-app-debug.apk`，手机安装（需允许「未知来源应用」）。

工程**不带 gradle wrapper**（wrapper jar 是二进制无法手写），CI 里由 `gradle/actions/setup-gradle` 装 Gradle 8.7，无需本地任何东西。

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
        ├── AndroidManifest.xml      # INTERNET 权限、明文 HTTP 放行、singleTask
        ├── java/com/sunhuip/ha3dapp/
        │   ├── Config.kt            # ★ 入口与开关
        │   └── MainActivity.kt      # WebView + 探测切换 + 沉浸全屏
        └── res/                     # 名称、主题、自适应图标（矢量房子）
```
