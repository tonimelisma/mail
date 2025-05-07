// File: core-common/build.gradle.kts
plugins {
    alias(libs.plugins.android.library) // Apply library plugin
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.melisma.core_common" // Make sure this matches your package name
    compileSdk = 35 // Match your project's compileSdk

    defaultConfig {
        minSdk = 26 // Match your project's minSdk
        // No test runner needed for a simple common module initially
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Disable features not needed for this simple utility module
    buildFeatures {
        buildConfig = false
        resValues = false
        aidl = false
        renderScript = false
        shaders = false
        viewBinding = false // If not using View Binding
        androidResources = false // If no Android resources are needed
    }
}

dependencies {
    // Essential Kotlin standard library
    implementation(libs.kotlin.stdlib)

    // Add other common dependencies if needed later (e.g., javax.inject if using qualifiers here)

    // Add testing dependencies if you plan unit tests within core-common
    // testImplementation(libs.junit)
    // testImplementation(libs.mockk.core)
    // testImplementation(libs.kotlinx.coroutines.test)
}
