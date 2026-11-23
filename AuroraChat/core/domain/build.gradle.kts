plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aurora.chat.core.domain"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}

dependencies {
    api(project(":core:common"))
}
