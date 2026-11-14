pluginManagement {
    /* 国内构建：加 -Pcn 走阿里云镜像（gradle -Pcn assembleDebug），默认走官方源（CI 用） */
    val cn = providers.gradleProperty("cn").isPresent
    repositories {
        if (cn) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    val cn = providers.gradleProperty("cn").isPresent
    repositories {
        if (cn) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ha3d-app"
include(":app")
