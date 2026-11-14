plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Google Maps API key from local.properties (gitignored): MAPS_API_KEY=...
// Without it the app simply uses OpenStreetMap/CARTO.
val mapsApiKey: String = rootProject.file("local.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("MAPS_API_KEY=") }
    ?.substringAfter("=")?.trim().orEmpty()

android {
    namespace = "uk.maynotfunction.ping"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        // The Google Maps API key is restricted to this ID and the signing key's SHA-1.
        applicationId = "uk.maynotfunction.ping"
        minSdk = 31
        targetSdk = 37
        versionCode = 12
        versionName = "2.5.2"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("boolean", "HAS_MAPS_KEY", mapsApiKey.isNotEmpty().toString())
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // `-Pping.appIdSuffix=.test` installs a debug build next to the real app, e.g. to try setup.
            (project.findProperty("ping.appIdSuffix") as String?)?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maps.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
