// Server connection shared by the vehicle app and the phone app: HTTP, Firebase anonymous sign-in, server settings.
// No Pleos SDK here (vehicle-app only).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bobcar.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // Robolectric (org.json and Android classes on the JVM)
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
