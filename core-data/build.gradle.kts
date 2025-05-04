// File: core-data/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // No Kapt or Hilt plugin needed here if it only contains interfaces, models, and non-Hilt injected classes
}

android {
    namespace = "net.melisma.core_data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true // You might want this false for easier debugging initially
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
    buildFeatures {
        // Keeping these features off seems reasonable for this module
        compose = false
        viewBinding = false
        // androidResources = false // Keep if you truly have no resources
        buildConfig = false
        aidl = false
        renderScript = false
        resValues = false
        shaders = false
    }
    // Recommended for MockK testing Android classes in unit tests
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Kotlin Stdlib (Good practice to include explicitly)
    implementation(libs.kotlin.stdlib) // Already present in your TOML

    // Coroutines Core (Exported via api)
    api(libs.kotlinx.coroutines.core)

    // Javax Inject (Used for @Inject annotation, needed by Hilt/Dagger users)
    implementation(libs.javax.inject)

    // --- Unit Testing (test source set) --- // ADDED/UPDATED BLOCK
    testImplementation(libs.junit)                     // Core JUnit
    testImplementation(libs.mockk.core)                // MockK core library
    testImplementation(libs.mockk.android)             // MockK extensions for Android classes (even in unit tests)
    testImplementation(libs.mockk.agent)               // MockK agent for final classes/methods if needed
    testImplementation(libs.kotlinx.coroutines.test)   // Coroutine testing utilities (runTest, TestDispatchers)
    testImplementation(libs.turbine)                   // Flow testing helper

    // --- Instrumented Testing (androidTest source set) --- // NO CHANGES HERE
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}