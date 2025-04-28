plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
}

dependencies {

    // --- MSAL Dependency using Version Catalog ---
    // Use 'api' so that modules depending on :feature-auth (like :app)
    // can access MSAL types (IAccount, MsalException, etc.)
    api(libs.microsoft.msal) // Use the alias defined in libs.versions.toml

    // Keep display-mask as implementation, using Version Catalog
    implementation(libs.microsoft.display.mask) // Use the alias defined in libs.versions.toml

    // --- REMOVED explicit OpenTelemetry BOM ---
    // ------------------------------------------

    // Core Kotlin extensions are generally useful
    implementation(libs.androidx.core.ktx)

    // Test dependencies remain the same
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
