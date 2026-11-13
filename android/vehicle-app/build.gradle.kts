/** A Gradle property (-Pname=value or gradle.properties); null when not set. */
fun prop(name: String): String? = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

/**
 * A BuildConfig string that is not a compile-time constant: Kotlin would otherwise copy the old value into
 * the classes that use it, and an incremental build would not pick up a new value.
 */
fun configString(value: String): String = "String.valueOf(\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\")"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bobcar.vehicle"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.bobcar.vehicle"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // This vehicle's app-made ID on the server (no VIN; the emulator has none). -Pbobcar.vehicleId=...
        buildConfigField("String", "VEHICLE_ID", configString(prop("bobcar.vehicleId") ?: "veh-001"))

        // Pleos Vehicle SDK version (libs.versions.toml). The gateway corrects SDK bugs of a specific version with it.
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicleSdk.get()}\"")
    }

    buildTypes {
        // Server settings (CLAUDE.md rule 6-1). Override with -Pbobcar.serverUrl / authUrl / firebaseApiKey
        // (e.g. in ~/.gradle/gradle.properties; never committed).
        // SERVER_URL: base URL ending at the Functions region; the app adds /api/v1/...
        // AUTH_URL: Firebase Auth emulator; empty = Google's real Auth endpoints (HTTPS).
        // FIREBASE_API_KEY: the project's Web API key (sent in a header, never in a URL); the emulator takes any value.
        debug {
            // The PC's Firebase emulators as seen from the Android emulator (server/: npm run emulators)
            buildConfigField("String", "SERVER_URL", configString(prop("bobcar.serverUrl") ?: "http://10.0.2.2:5001/demo-bobcar/asia-northeast3"))
            buildConfigField("String", "AUTH_URL", configString(prop("bobcar.authUrl") ?: "http://10.0.2.2:9099"))
            buildConfigField("String", "FIREBASE_API_KEY", configString(prop("bobcar.firebaseApiKey") ?: "emulator"))
        }
        release {
            // No server until a real HTTPS server exists: records stay in the upload queue
            buildConfigField("String", "SERVER_URL", configString(prop("bobcar.serverUrl") ?: ""))
            buildConfigField("String", "AUTH_URL", configString(prop("bobcar.authUrl") ?: ""))
            buildConfigField("String", "FIREBASE_API_KEY", configString(prop("bobcar.firebaseApiKey") ?: ""))
            isDebuggable = false
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
        compose = true
        buildConfig = true
    }
    testOptions {
        // Robolectric (Room DAO tests on the JVM)
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.pleos.vehicle.sdk)
    implementation(libs.pleos.navi.helper)
    // Voice guidance, on-device mode only (no server credentials in the app)
    implementation(libs.pleos.text.to.speech)
    // Vehicle SDK pulls kotlin-reflect 1.9.25; pin it to the project's Kotlin version (same as the official sample)
    implementation(libs.kotlin.reflect)
    // StateFlow for the signal snapshot (same version the SDK and lifecycle already resolve to)
    implementation(libs.kotlinx.coroutines.android)
    // Local DB (records first, upload queue later)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Upload queue: sends when the network is up, retries with backoff
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
