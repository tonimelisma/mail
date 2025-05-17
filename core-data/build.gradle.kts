// File: core-data/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.melisma.core_data"
    compileSdk = 35 // Ensure this matches your project's compileSdk

    defaultConfig {
        minSdk = 26 // Ensure this matches your project's minSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Consider false for easier debugging initially
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
        compose = false
        viewBinding = false
        buildConfig = false
        aidl = false
        renderScript = false
        resValues = false
        shaders = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Kotlin Stdlib
    implementation(libs.kotlin.stdlib)

    // Coroutines Core (Exported via api for modules that implement core-data interfaces)
    api(libs.kotlinx.coroutines.core)

    // Javax Inject (Used for @Inject annotation, potentially by Hilt/Dagger users of this module's interfaces)
    implementation(libs.javax.inject)

    // ADDED: AndroidX Core KTX to provide androidx.annotation.RawRes and other utilities
    implementation(libs.androidx.core.ktx)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences) // Assuming alias is androidx-datastore-preferences
    implementation(libs.androidx.lifecycle.runtime.ktx) // Assuming alias is androidx-lifecycle-runtime-ktx

    // Logging
    implementation(libs.timber) // Added Timber for logging

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // --- Unit Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android) // For mocking Android classes if needed in core-data tests
    testImplementation(libs.mockk.agent)   // For mocking final classes/methods if needed
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // --- Instrumented Testing ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
