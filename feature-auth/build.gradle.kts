// File: feature-auth/build.gradle.kts

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.melisma.feature_auth"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    // Disable build features not needed in this module.
    buildFeatures {
        buildConfig = false
        androidResources = false // Assuming auth_config stays in :app for now
    }
}

dependencies {
    // --- Project Modules ---
    // Use 'api' so modules using feature-auth can see core_data types exposed by it.
    api(project(":core-data")) // *** ADDED DEPENDENCY ***

    // --- MSAL Dependency ---
    // Use 'api' if other modules consuming feature-auth need MSAL types directly.
    // Use 'implementation' if MSAL types are fully hidden by this module's API.
    // 'api' is often safer for auth libraries shared across modules.
    api(libs.microsoft.msal)

    // --- Coroutines (Might be needed for any internal async work) ---
    // api(libs.kotlinx.coroutines.core) // Included transitively via :core-data 'api' dep

    // --- Core Kotlin extensions (Useful for basic language features) ---
    implementation(libs.androidx.core.ktx) // Keep implementation scope if not exposed

    // --- Testing Dependencies ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
