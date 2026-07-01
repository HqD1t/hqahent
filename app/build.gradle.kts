plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voicebot.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicebot.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed signing key committed to the repo so every CI build has the SAME
    // signature -> the APK installs as an UPDATE over the old one, keeping all
    // permissions and the accessibility service enabled (no reinstall needed).
    signingConfigs {
        create("shared") {
            storeFile = file("voicebot.jks")
            storePassword = "voicebot"
            keyAlias = "voicebot"
            keyPassword = "voicebot"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
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
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Offline Russian speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Networking for Claude API + model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.json:json:20240303")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
