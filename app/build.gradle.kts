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
        versionCode = 4
        versionName = "1.3.0"
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
            /*
             * 这里**故意不开** R8/资源压缩。
             *
             * 引入 Material 3 之后 debug 包从 1.1 MB 涨到 6.0 MB（不混淆、资源不去重是主因），
             * 开 minify 能压下去不少 —— 但 Material 的控件是靠**反射**按类名从 XML/主题里
             * inflate 的，R8 一旦裁到某个类，只有真机点到那一屏才崩，而这类崩溃在
             * 单测里完全看不出来。当前没有可用的真机回归条件，所以宁可胖一点，
             * 也不要发一个"装得上但某屏必崩"的包。
             *
             * 想瘦身的话：先在有真机的环境跑一遍完整手工回归（设置页全部控件、结果页、
             * 悬浮条、图标），再把这两行打开。
             */
            isMinifyEnabled = false
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
    // Material Components（MD3）：界面统一用它，顺带拿到 Material You 壁纸取色。
    // 这是本项目**唯一**的运行期第三方依赖 —— 换来的是整套 MD3 控件与动态取色，
    // 代价是 APK 从 ~1.1MB 涨到 ~2.5MB（会带进 androidx 的 appcompat/core/fragment 等）。
    // 网络与 JSON 仍然是自带的（HttpURLConnection + 手写 Json.kt），没有引入 OkHttp/Gson。
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")

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
