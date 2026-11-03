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
        versionCode = 13
        versionName = "2.2"
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
    // v2.0 has none. The Leanback GuidedStep screen was the source of the
    // launch crash (its stylists inflate under a theme it cannot live with)
    // and of the settings/dialog focus problems; the screen is now plain
    // Android views with D-pad focus, which needs no library at all.
}
