plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // You might also need the compose compiler plugin if using @Composable in this module,
    // but for just state, runtime might be enough. Add if needed:
    // alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.melisma.feature_auth"
    compileSdk = 35 // Or your target SDK

    defaultConfig {
        minSdk = 24

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // If using @Composable annotations within this library module, enable compose here:
    // buildFeatures {
    //    compose = true
    // }
    // composeOptions {
    //    kotlinCompilerExtensionVersion = "..." // Use the version compatible with your Kotlin version
    // }
}

dependencies {

    // --- MSAL Dependency ---
    api(libs.microsoft.msal)
    implementation(libs.microsoft.display.mask)

    // --- Compose Runtime Dependency --- Needed for mutableStateOf, etc.
    // Use platform() to ensure version consistency with the BOM used in :app
    implementation(platform(libs.androidx.compose.bom))
    // Add the specific runtime artifact
    implementation(libs.androidx.compose.runtime)
    // OR without alias: implementation("androidx.compose.runtime:runtime")
    // ---------------------------------

    // Core Kotlin extensions
    implementation(libs.androidx.core.ktx)

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
