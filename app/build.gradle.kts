plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from GitHub Actions secrets (never stored in this repository).
// Without them (e.g. a local build) the release build falls back to the debug key.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.aly.trueqibla"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aly.trueqibla"
        minSdk = 26
        targetSdk = 34
        // Every cloud build gets a higher number, so a new APK installs over the old one.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.1"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
// No external libraries: everything (sensors, GPS, World Magnetic Model) is built into Android.
