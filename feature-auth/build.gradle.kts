// File: feature-auth/build.gradle.kts (No Hilt changes needed here)

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.melisma.feature_auth"
    compileSdk = 35

    defaultConfig {
        minSdk = 26 // Updated minSdk

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
}

dependencies {

    // --- MSAL Dependency ---
    // Use 'api' if other modules consuming feature-auth need MSAL types directly.
    // Use 'implementation' if MSAL types are fully hidden by this module's API.
    // 'api' is often safer for auth libraries shared across modules.
    api(libs.microsoft.msal)

    // Core Kotlin extensions
    implementation(libs.androidx.core.ktx)

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}