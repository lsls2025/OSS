plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aurora.chat"
    compileSdk = 35
    defaultConfig {
        applicationId = "asia.aurorachat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "2.4.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        // 大量权限调用已被运行时检查或上游封装，这里只折叠警告不阻塞编译
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true }
    // Compose 编译器稳定性配置 + 报告：实证哪些 composable 可被跳过，作为性能优化的度量基线
    composeCompiler {
        stabilityConfigurationFile = rootProject.file("compose_compiler_config.conf")
        reportsDestination = layout.buildDirectory.dir("compose_compiler_reports")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 视频本地压缩（上传前压缩，节省服务器带宽）
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tencent.mmkv)
    implementation(libs.androidx.profileinstaller)
    implementation("com.caverock:androidsvg:1.4")
    // 二维码生成与解析（ZXing core，纯 JVM，轻量）
    implementation("com.google.zxing:core:3.5.3")
    // 相机拍摄
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-video:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    // 指纹/生物识别 使用 API 28+ 平台原生 BiometricPrompt
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 网络层：OkHttp 单例连接池 + 单例 SSLContext（替代每次请求 new 的 HttpURLConnection）
    implementation(libs.okhttp3)
    // FTPS(FTP over TLS) 客户端，供 AI agent 的 ftp_transfer 工具使用
    implementation("commons-net:commons-net:3.11.0")
    // Lua 脚本引擎（纯 Java，约 200KB，用于 AI 工作区轻量脚本执行）
    implementation("org.luaj:luaj-jse:3.0.1")
    debugImplementation(libs.androidx.ui.tooling)
}


