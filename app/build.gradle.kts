plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (set by GitHub Actions secrets).
// Without them the release APK is signed with the debug key so it still installs.
val ksFile: String? = System.getenv("KEYSTORE_FILE")
val hasReleaseKey = ksFile != null && file(ksFile).exists()

android {
    namespace = "com.globalvpn.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.globalvpn.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Official WireGuard userspace tunnel (GoBackend) — no root required.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
