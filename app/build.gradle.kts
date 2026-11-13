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
        versionName = "1.6"
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
    testOptions { unitTests.isIncludeAndroidResources = true }
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
// No runtime libraries: sensors, location and the magnetic model use Android APIs.


// Pinned SahlTV packaged fonts are staged at build time; installed apps stay offline.
val thmanyahAssets = layout.buildDirectory.dir("generated/thmanyah/assets")
val prepareThmanyah = tasks.register<Exec>("prepareThmanyah") {
    workingDir(rootProject.projectDir)
    commandLine(if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3",
        "tools/prepare_thmanyah.py", "--output", thmanyahAssets.get().asFile.absolutePath)
    inputs.file(rootProject.file("tools/prepare_thmanyah.py"))
    outputs.files(listOf("r", "m", "b").map { thmanyahAssets.get().file("thmanyah/$it.bin") })
}
android.sourceSets.getByName("main").assets.srcDir(thmanyahAssets)
tasks.matching { it.name == "preBuild" || it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(prepareThmanyah)
}
