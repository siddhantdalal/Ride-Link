import java.util.Properties

plugins {
    id("com.android.application")
}

// The Google Maps Platform key lives in secrets.properties at the project root, which git ignores. Without that file
// the app still builds, but navigation can't load.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.siddhantdalal.bikelink"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.siddhantdalal.bikelink"
        // Android 8.0. Older phones can't run current Google Maps, so they couldn't use the app anyway.
        // The Navigation SDK needs at least Android 7.
        minSdk = 26
        // The Navigation SDK 7+ needs at least API 36.
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["MAPS_API_KEY"] = secrets.getProperty("MAPS_API_KEY", "MISSING_MAPS_API_KEY")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // For the version shown in About & licences.
        buildConfig = true
    }

    lint {
        // AppCompat and the KTX extensions only arrive as part of the Navigation SDK. Bike Link's own screens use
        // framework views and themes, whose tint and drawable APIs work on every Android version the app supports (8+),
        // and the app doesn't depend on KTX directly. So these suggestions don't apply.
        disable += setOf(
            "UseAppTint",
            "UseCompatTextViewDrawableXml",
            "UseCompatTextViewDrawableApis",
            "UseCompatLoadingForDrawables",
            "UseKtx",
        )
    }

    compileOptions {
        // The Navigation SDK uses newer Java APIs that have to be desugared for older Android versions.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configurations.named("implementation") {
    // The Navigation SDK ships its own map, and the separate Maps SDK can't be in the same app.
    exclude(group = "com.google.android.gms", module = "play-services-maps")
}

dependencies {
    // Google's turn-by-turn navigation, which provides the map, routing (two-wheeler mode) and voice guidance. It's
    // the app's one large dependency, and the reason for it.
    implementation("com.google.android.libraries.navigation:navigation:8.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
