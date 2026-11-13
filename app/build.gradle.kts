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
        versionCode = 1
        versionName = "0.1.0"
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
}

dependencies {
    // Only Android framework APIs and the Kotlin standard library ship in the app. JUnit is used for tests only.
    testImplementation("junit:junit:4.13.2")
}
