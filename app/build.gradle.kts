plugins {
    id("com.android.application")
}

android {
    namespace = "com.siddhantdalal.bikelink"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.siddhantdalal.bikelink"
        // Android 8.0. Older phones can't run current Google Maps, so they couldn't use the app anyway.
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            // The app has no reflection or serialization, so R8's default rules are all it needs.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        // For the version shown in About & licences.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Only the Android framework at runtime: Google Maps does the navigating, so the app needs no libraries.
    testImplementation("junit:junit:4.13.2")
}
