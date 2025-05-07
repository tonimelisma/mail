// File: backend-google/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.kotlin.serialization) // For kotlinx.serialization
}

android {
    namespace = "net.melisma.backend_google"
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
    implementation(project(":core-common")) // For ErrorMapper

    // --- Google Sign-In ---
    implementation(libs.google.play.services.auth)
    implementation(libs.androidx.activity.ktx) // Needed for ActivityResultLauncher

    // --- Ktor Client ---
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json) // Core serialization runtime

    // --- Hilt ---
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core)

    // --- Unit Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // --- Instrumented Testing ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

kapt {
    correctErrorTypes = true
}