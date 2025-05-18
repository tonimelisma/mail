// File: backend-google/build.gradle.kts
// Adding a newline to try and force a Gradle sync
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp) // Changed from kapt
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

        buildConfigField(
            "String",
            "GOOGLE_ANDROID_CLIENT_ID",
            "\"326576675855-r404vqtrr8ohbpl7g6tianaekkt70igd.apps.googleusercontent.com\"" // <<< YOUR ANDROID CLIENT ID
        )

        // This manifest placeholder is used by AppAuth for the redirect URI.
        // The scheme 'net.melisma.mail' should be unique to your app.
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
        buildConfig = true // Ensure buildConfig is enabled to access GOOGLE_ANDROID_CLIENT_ID
    }
}

dependencies {
    // --- Project Modules ---
    implementation(project(":core-data"))

    // --- Google Identity, Auth, and Credential Manager ---
    implementation(libs.appauth) // For OpenID AppAuth OAuth 2.0 implementation
    implementation(libs.androidx.browser) // For CustomTabs to improve OAuth UI experience
    // implementation(libs.playservices.auth) // Removed as per user request and mapper simplification

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
    ksp(libs.hilt.compiler)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services) // For Task.await() if still needed elsewhere, review

    // --- Unit Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)

    // --- Instrumented Testing ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // For JWT decoding
    implementation("com.auth0.android:jwtdecode:2.0.2") // Or latest stable version

    // AndroidX KTX Libraries
    implementation(libs.androidx.core.ktx) // General utilities
    implementation(libs.androidx.activity.ktx) // For ActivityResultLauncher related utilities if needed by helpers

    // Logging
    implementation(libs.timber) // Added Timber for logging
}
