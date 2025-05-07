// File: app/build.gradle.kts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt") // Apply kapt using its standard ID
    alias(libs.plugins.hilt.gradle)
    // id("com.google.gms.google-services") // Ensure this is uncommented if you've set up google-services.json
}

android {
    namespace = "net.melisma.mail"
    compileSdk = 35 // Ensure this matches your project's compileSdk

    defaultConfig {
        applicationId = "net.melisma.mail"
        minSdk = 26 // Ensure this matches your project's minSdk
        targetSdk = 35 // Ensure this matches your project's targetSdk
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Set to true for release builds eventually
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true // Good for MockK/Robolectric if used
    }
}

dependencies {
    // --- Project Modules ---
    implementation(project(":core-data"))
    implementation(project(":data")) // Depends on :backend-microsoft (and eventually :backend-google)
    implementation(project(":core-common")) // Added as it's a common utility module

    // --- AndroidX Core & Lifecycle ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // --- ViewModel & Lifecycle Compose Dependencies ---
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // --- Hilt ---
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    // implementation(libs.androidx.hilt.navigation.compose) // Optional Hilt Compose Navigation

    // --- Coroutines (for main source set if directly used, often transitive) ---
    implementation(libs.kotlinx.coroutines.core) // Explicitly add for clarity if needed
    //implementation(libs.kotlinx.coroutines.*) // For Dispatchers.Main

    // --- Unit Testing (test source set) ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android) // For mocking Android framework classes in unit tests
    testImplementation(libs.mockk.agent)   // For mocking final classes/methods

    // ADDED explicit dependency for kotlinx-coroutines-core for tests
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test) // For runTest, TestDispatchers etc.
    testImplementation(libs.turbine) // For testing Flows

    // --- Instrumented Testing (androidTest source set) ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Robolectric (If you decide to re-add it for specific tests needing Android runtime)
    // testImplementation(libs.robolectric)
    // testImplementation(libs.androidx.test.core) // For Robolectric
}

kapt {
    correctErrorTypes = true
}
