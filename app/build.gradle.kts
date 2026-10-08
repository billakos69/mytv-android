plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mytv.app"
    compileSdk = 34
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    defaultConfig {
        applicationId = "com.mytv.app"
        minSdk = 24
        targetSdk = 29
        versionCode = 4
        versionName = "1.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}