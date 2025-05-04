// File: backend-microsoft/build.gradle.kts
// Build script for the backend-microsoft library module.
// Contains implementations for repositories and data sources using Microsoft technologies (MSAL, Graph API).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt") // Apply kapt for Hilt annotation processing
    alias(libs.plugins.hilt.gradle)  // Apply Hilt plugin
}

android {
    namespace = "net.melisma.backend_microsoft"
    compileSdk = 35 // Align with app

    defaultConfig {
        minSdk = 26 // Align with app

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Disable unnecessary build features
    buildFeatures {
        compose = false
        viewBinding = false
        androidResources = false
        buildConfig = false
        aidl = false
        renderScript = false
        resValues = false
        shaders = false
    }
}

dependencies {
    // --- Project Modules ---
    implementation(project(":core-data"))
    implementation(project(":feature-auth")) // Needs MicrosoftAuthManager etc.

    // --- Hilt ---
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // --- Coroutines ---
    // implementation(libs.kotlinx.coroutines.core) // Pulled transitively via :core-data

    // --- JSON Parsing (for GraphApiHelper) ---
    implementation(libs.org.json) // Or your chosen library

    // --- AndroidX Core (Optional, for annotations like @Inject) ---
    implementation(libs.androidx.core.ktx)

    // --- MSAL (For exception types if needed, though maybe covered by :feature-auth 'api') ---
    // api(libs.microsoft.msal) // Already api dependency in :feature-auth

    // --- Testing Dependencies ---
    testImplementation(libs.junit)
    // Add Hilt testing dependencies if needed later
    // testImplementation(libs.hilt.android.testing)
    // kaptTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Configure Kapt
kapt {
    correctErrorTypes = true
}
