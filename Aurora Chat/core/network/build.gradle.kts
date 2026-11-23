plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aurora.chat.core.network"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.okhttp3)
    implementation(libs.kotlinx.coroutines.android)
}
