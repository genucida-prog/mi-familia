plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mifamilia.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mifamilia.app"
        minSdk = 24
        targetSdk = 34
    versionCode = 3
    versionName = "1.2"
    }

    buildTypes {
        debug {
            // Debuggable, sideloadable build used while iterating.
            isMinifyEnabled = false
        }
        release {
            // The WebView payload is already plain HTML/CSS/JS, so there is
            // nothing to shrink and nothing to keep alive via ProGuard.
            // Enable `isMinifyEnabled = true` only if you add a keystore.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The www payload lives in assets/wwww and is loaded through
    // WebViewAssetLoader, so nothing here needs to be exposed or compressed
    // in a special way. Keep the raw files byte-identical.
    androidResources {
        noCompress += listOf("png")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    // Background MQTT sync (SyncService): plain TCP against the public brokers.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}