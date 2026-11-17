plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* release 签名：凭据放 local.properties（已被 .gitignore 忽略，绝不入库）。
 * 没配的机器（Windows 工具链 / 新 clone）跳过签名配置，release 仍产出 unsigned APK，
 * 不会让 assembleRelease 直接失败。要出可安装的正式包，按 README「release 签名」配 4 行。 */
import java.util.Properties

val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val releaseStore = signingProps.getProperty("release.storeFile")?.let { rootProject.file(it) }
val releaseConfigured = releaseStore != null && releaseStore.isFile
    && !signingProps.getProperty("release.storePassword").isNullOrBlank()

android {
    namespace = "com.sunhuip.ha3dapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sunhuip.ha3dapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseConfigured) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingProps.getProperty("release.storePassword")
                keyAlias = signingProps.getProperty("release.keyAlias", "ha3d")
                keyPassword = signingProps.getProperty("release.keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
