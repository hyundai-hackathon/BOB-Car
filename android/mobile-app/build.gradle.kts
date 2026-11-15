/** A Gradle property (-Pname=value or gradle.properties); null when not set. */
fun prop(name: String): String? = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

/** A BuildConfig string that is not a compile-time constant (an incremental build picks up a new value). */
fun configString(value: String): String = "String.valueOf(\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\")"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bobcar.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bobcar.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: the same team key as the vehicle app, from ~/.gradle/gradle.properties (bobcar.release.storeFile /
    // storePassword / keyAlias / keyPassword). Never in the repository (CLAUDE.md rule 7). Without them: unsigned.
    val releaseStoreFile = prop("bobcar.release.storeFile")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = prop("bobcar.release.storePassword")
                keyAlias = prop("bobcar.release.keyAlias")
                keyPassword = prop("bobcar.release.keyPassword")
            }
        }
    }

    buildTypes {
        // Same server as the vehicle app (CLAUDE.md rule 6-1). Debug: -Pbobcar.serverUrl / authUrl / firebaseApiKey.
        // Release: -Pbobcar.release.serverUrl / authUrl / firebaseApiKey (never changes debug builds).
        debug {
            // The PC's Firebase emulators as seen from the Android emulator (server/: npm run emulators)
            buildConfigField("String", "SERVER_URL", configString(prop("bobcar.serverUrl") ?: "http://10.0.2.2:5001/demo-bobcar/asia-northeast3"))
            buildConfigField("String", "AUTH_URL", configString(prop("bobcar.authUrl") ?: "http://10.0.2.2:9099"))
            buildConfigField("String", "FIREBASE_API_KEY", configString(prop("bobcar.firebaseApiKey") ?: "emulator"))
        }
        release {
            // The real HTTPS server when bobcar.release.* is set; without it the app shows it cannot connect
            buildConfigField("String", "SERVER_URL", configString(prop("bobcar.release.serverUrl") ?: ""))
            buildConfigField("String", "AUTH_URL", configString(prop("bobcar.release.authUrl") ?: ""))
            buildConfigField("String", "FIREBASE_API_KEY", configString(prop("bobcar.release.firebaseApiKey") ?: ""))
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
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
        // Robolectric (org.json on the JVM)
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Server connection (HTTP, anonymous sign-in, server settings) and the consent notice, shared with the vehicle app
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
