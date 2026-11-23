plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aurora.chat"
    compileSdk = 37
    defaultConfig {
        applicationId = "asia.aurorachat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "3.2.0"
    }
    buildTypes {
        release {
            // 只保留真机 ABI：x86/x86_64 只有模拟器用得到。
            // libmmkv.so 四个 ABI 合计 2.7 MB，去掉这两份能省 1.5 MB（压进 APK 后约 0.5 MB）。
            // armeabi-v7a 保留，避免 32 位低端机装不上；debug 不过滤，模拟器照常开发。
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
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
    kotlin { jvmToolchain(21) }
    buildFeatures { compose = true }
    // Compose 编译器报告：实证哪些 composable 可被跳过，作为性能优化的度量基线
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_compiler_reports")
    }
    // 原生库打包策略：压缩并随安装释放到 nativeLibraryDir（等价 extractNativeLibs=true；AGP 8 不允许在清单里显式写该属性）。
    // proot / loader / 依赖库伪装成 lib*.so 放在 jniLibs，靠这里释放成「只读、可执行」的真实文件，
    // 才能在 targetSdk 35（禁止从可写目录 exec）下执行 proot。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    // GIF/动图 WebP 逐帧解码器（GifDecoder API<28 + ImageDecoderDecoder API28+）。
    // coil-base 只带 BitmapFactoryDecoder，没有它所有动图都只显示第一帧。
    implementation(libs.coil.gif)
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
    // 解压 Linux rootfs / Python 包（tar.xz / tar.gz / zip）：Commons Compress + XZ for Java（纯 Java，可在 Android 运行）
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    // 液态玻璃渲染底座（kyant backdrop + shapes）：LiquidBubble / LiquidDialog / GlassListCard /
    // KyantScrollContainer 等整套液态组件都依赖它。缺了这两个坐标，整套液态 UI 全线编译不过。
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")


    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:ai"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:community"))
    implementation(project(":feature:server"))
    implementation(project(":feature:misc"))
    implementation(libs.koin.android)

    debugImplementation(libs.compose.ui.tooling)
    // 交付协议（DeliveryProtocol）等纯逻辑的 JVM 单元测试：不打包进 APK，仅本地/CI 校验
    testImplementation("junit:junit:4.13.2")
    // 真实 JSON 实现：Android 的 org.json 在 JVM 单测里是 stub，必须换成可用实现才能测编码/解析
    testImplementation("org.json:json:20231013")
}