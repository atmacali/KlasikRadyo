plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val githubRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val appVersionCode = githubRunNumber?.plus(2) ?: 3

android {
    namespace = "com.retroradyo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retroradyo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "1.1"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file("retroradyo-debug.p12")
            storePassword = "android"
            keyAlias = "retroradyo-debug"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
