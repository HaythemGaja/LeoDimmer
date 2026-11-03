plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.universal.tvdimmer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.universal.tvdimmer"
        // NOTE: minSdk 28, not 30, on purpose. The target Xiaomi box in use is a
        // MIBOX4 running Android 9 (API 28): a minSdk 30 build would refuse to
        // install on it. Everything in this app works on API 28, and the
        // TYPE_APPLICATION_OVERLAY / foreground-service code paths used here
        // are the Android 11+ shapes (they degrade cleanly on API 28).
        // Raise this to 30 in one line if you ever want a strict 11+ build.
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    buildTypes {
        release {
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
    // Leanback settings screen (GuidedStepSupportFragment). This is the only
    // external dependency, on purpose: the S905X in the Mi Box does not need
    // a heavy dependency graph for a dimmer.
    implementation("androidx.leanback:leanback:1.0.0")
}
