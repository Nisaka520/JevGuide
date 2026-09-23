plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.nisaka520.jevguide"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nisaka520.jevguide"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    /**
     * 固定签名：本地构建与 GitHub Actions 用**同一把** keystore。
     *
     * 为什么必须这样：AGP 默认的 debug keystore 是每台机器（含每个 CI runner）各自生成的，
     * 于是"本地装的"和"CI 出的"签名不一致 —— 新版覆盖安装会直接报「应用未安装」。
     * 这把 keystore 是**公开的调试签名**（口令就是 android），只为让升级能装上，
     * 别拿它去发应用商店；真要上架请自己用 secrets 配一把正式签名。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "jevguide"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false          // 无第三方依赖，不需要混淆；要瘦身可自行开启
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 运行期零第三方依赖：JSON 用手写的 Json.kt，网络用 HttpURLConnection，UI 用原生 View
    testImplementation("junit:junit:4.13.2")
}

// 单测输出中文，Windows 上默认编码会乱，锁成 UTF-8；顺便把 println 显示出来
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
