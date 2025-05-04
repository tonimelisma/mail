// File: backend-microsoft/build.gradle.kts

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt") // Apply kapt if using Hilt/DI annotations within this module
    alias(libs.plugins.hilt.gradle) // Apply Hilt plugin if needed here
}

android {
    namespace = "net.melisma.backend_microsoft" // Ensure correct namespace
    compileSdk = 35 // Match app's SDK

    defaultConfig {
        minSdk = 26 // Match app's minSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    // buildFeatures { // Add features if needed, e.g., compose = true if Compose used here
    //     compose = true
    // }
    // composeOptions { // Add if compose enabled
    //    kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    // }
}

dependencies {
    // --- Project Modules ---
    implementation(project(":core-data")) // Essential dependency
    // Ensure NO dependency on :feature-auth here

    // --- AndroidX & Kotlin ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core) // If using coroutines directly

    // --- Hilt --- (If using @Inject, @Provides, @Module etc. *within* this module)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // --- MSAL --- (Should already be here)
    implementation(libs.microsoft.msal) // Assuming you have this alias

    // --- JSON Parsing --- (e.g., if GraphApiHelper uses org.json)
    implementation(libs.org.json) // Assuming alias exists

    // --- Unit Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine) // If testing flows here

    // --- Instrumented Testing ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Add other necessary test dependencies
}

kapt { // Add if Hilt compiler is used
    correctErrorTypes = true
}