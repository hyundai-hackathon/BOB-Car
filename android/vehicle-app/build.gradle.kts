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

        // Upload server base URL. Empty until the server exists: records stay in the upload queue.
        // Set it only for testing, e.g. ./gradlew installDebug -Pbobcar.serverUrl=http://10.0.2.2:8787
        // String.valueOf keeps it from being a compile-time constant: Kotlin would otherwise copy the old
        // value into the classes that use it, and an incremental build would not pick up a new URL.
        val serverUrl = providers.gradleProperty("bobcar.serverUrl").getOrElse("")
        buildConfigField("String", "SERVER_URL", "String.valueOf(\"$serverUrl\")")
    }

    buildTypes {
        release {
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
