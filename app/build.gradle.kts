plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vc = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1

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
        versionCode = 100 + vc
        versionName = "2.$vc"
    }
    buildFeatures {
        buildConfig = true
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
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
}