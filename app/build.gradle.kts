plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "xyz.hvdw.mediaoverlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "xyz.hvdw.mediaoverlay"
        minSdk = 23     // Android 6
        targetSdk = 33  // Android 13
        versionCode = 2
        versionName = "1.1"

        // Only include the ABIs you want in the final APK
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Remove unwanted native libs if any dependency tries to include them
    packaging {
        jniLibs {
            excludes += listOf(
                "**/armeabi/**",
                "**/armeabi-v7a/**",
                "**/x86/**"
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
