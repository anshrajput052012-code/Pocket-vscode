plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vscode.desktop.standalone"
    compileSdk = 34

    defaultConfig {
        // LOCKED FOREVER: changing this makes Android treat the APK as a different app (no in-place update).
        applicationId = "com.pocket.vscode"
        minSdk = 26
        targetSdk = 34
        // CI overwrites versionCode with the GitHub run number (monotonic) and versionName with 1.0.<run number>.
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    // Every build is signed with the SAME permanent key (app/keystore.jks, restored/saved by the workflow),
    // so a new APK always installs as an update over the old one.
    signingConfigs {
        getByName("debug") {
            val permanentKeystore = file("keystore.jks")
            if (permanentKeystore.exists()) {
                storeFile = permanentKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // The bundled Debian rootfs / VS Code archives are already compressed: store them as-is. This keeps the
    // APK build fast and lets the app read the exact asset size for the real progress percentage.
    androidResources {
        noCompress.addAll(listOf("xz", "gz", "tar"))
    }

    packaging {
        jniLibs {
            // CRITICAL for Android 10+ (API 29+):
            // Extracts libproot.so, libproot-loader.so, and libtalloc.so into
            // nativeLibraryDir (/data/app/.../lib/arm64/) which has SELinux exec permissions.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
