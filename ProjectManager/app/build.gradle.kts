plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pm.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pm.manager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        val pmApiBaseUrl = (project.findProperty("PM_API_BASE_URL") as? String) ?: "http://10.0.2.2:3001/"
        val pmWsHost = (project.findProperty("PM_WS_HOST") as? String) ?: "10.0.2.2"
        val pmWsPort = (project.findProperty("PM_WS_PORT") as? String) ?: "3003"
        buildConfigField("String", "PM_API_BASE_URL", "\"$pmApiBaseUrl\"")
        buildConfigField("String", "PM_WS_HOST", "\"$pmWsHost\"")
        buildConfigField("int", "PM_WS_PORT", pmWsPort)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
}