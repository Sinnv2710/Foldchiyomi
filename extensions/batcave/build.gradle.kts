plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

android {
    namespace = "eu.kanade.tachiyomi.extension.en.batcave"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.en.batcave"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.4.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Provided by the host app at runtime, so compileOnly.
    compileOnly("com.github.keiyoushi:extensions-lib:v1.4.2.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("org.jsoup:jsoup:1.18.1")
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("com.github.null2264.injekt:injekt-core:4135455a2a")
}
