plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jpsums.zenix"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jpsums.zenix"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.1"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed key from the repo so every new build installs over the old one
            storeFile = file("zenix-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
