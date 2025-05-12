// File: backend-google/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt") // For Hilt
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.kotlin.serialization) // For kotlinx.serialization
}

android {
    namespace = "net.melisma.backend_google"
    compileSdk = 35 // Or your project's compileSdk

    defaultConfig {
        minSdk = 26 // Or your project's minSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Google Android OAuth Client ID for AppAuth
        buildConfigField(
            "String",
            "GOOGLE_ANDROID_CLIENT_ID",
            "\"326576675855-6vc6rrjhijjfch6j6106sd5ui2htbh61.apps.googleusercontent.com\""
        )

        // Add manifest placeholder for AppAuth redirect scheme
        // This will be available to all variants, including androidTest
        manifestPlaceholders["appAuthRedirectScheme"] = "net.melisma.mail"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Or true, with appropriate Proguard rules
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
        buildConfig = true
    }
}

dependencies {
    // --- Project Modules ---
    implementation(project(":core-data"))

    // --- Google Identity, Auth, and Credential Manager ---
    implementation(libs.google.play.services.auth) // For com.google.android.gms.auth.api.identity.*
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id) // For com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
    implementation(libs.appauth) // For OpenID AppAuth OAuth 2.0 implementation
    implementation(libs.androidx.browser) // For CustomTabs to improve OAuth UI experience

    implementation(libs.androidx.activity.ktx) // Useful for ActivityResultLauncher

    // --- Ktor Client ---
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)

    // --- Hilt ---
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services) // For Task.await()

    // --- Unit Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android) // if using Android specific parts in unit tests
    testImplementation(libs.mockk.agent)   // if needing final class/method mocking
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)

    // --- Instrumented Testing ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

kapt {
    correctErrorTypes = true
}