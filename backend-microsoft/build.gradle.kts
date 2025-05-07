// File: backend-microsoft/build.gradle.kts

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.kotlin.serialization) // <<< APPLY SERIALIZATION PLUGIN
}

android {
    namespace = "net.melisma.backend_microsoft"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
}

dependencies {
    // --- Project Modules ---
    implementation(project(":core-data"))
    implementation(project(":core-common"))

    // --- MSAL ---
    implementation(libs.microsoft.msal)

    // --- Hilt ---
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // --- AndroidX & Kotlin ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // --- Ktor Client Dependencies --- ADDED BLOCK ---
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json) // Core serialization runtime
    implementation(libs.ktor.client.logging) // Optional Ktor logging
    // --- END Ktor Block ---

    // --- JSON Parsing (Old - REMOVE if GraphApiHelper no longer uses org.json) ---
    // implementation(libs.org.json)

    // --- Unit Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine) // Uncommented for testing Flow-based code

    // --- Instrumented Testing ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

kapt {
    correctErrorTypes = true
}
