// File: data/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.melisma.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Add manifest placeholder for AppAuth redirect scheme
        // This will be available to all variants, including androidTest
        manifestPlaceholders["appAuthRedirectScheme"] = "net.melisma.mail"
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "REDIRECT_URI_APP_AUTH",
                "\"net.melisma.mail.debug:/oauth2redirect\""
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField(
                "String",
                "REDIRECT_URI_APP_AUTH",
                "\"net.melisma.mail:/oauth2redirect\""
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
    implementation(project(":core-data")) // Includes ErrorMapperService now
    implementation(project(":core-db")) // Added new local DB module
    // Use 'api' for :backend-microsoft so its Hilt bindings and types (like AuthStateListener)
    // are visible to modules that depend on :data (e.g., :backend-google for Kapt processing)
    implementation(project(":backend-microsoft")) // For MS-specific components
    implementation(project(":backend-google")) // For Google-specific components

    // --- Room --- 
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // --- Paging 3 ---
    implementation(libs.androidx.paging.runtime) // Use -ktx version for coroutine support
    // implementation(libs.androidx.paging.common.ktx) // common is usually transitive

    // --- Google Identity Library ---
    implementation(libs.google.id)

    // --- AppAuth for OAuth 2.0 ---
    implementation(libs.appauth) // Needed for DefaultAccountRepository's AppAuth integration
    implementation(libs.androidx.browser) // For CustomTabs support in AppAuth

    // --- Hilt ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work) // Corrected alias
    ksp(libs.androidx.hilt.compiler) // KSP processor for @HiltWorker

    // --- WorkManager ---
    implementation(libs.androidx.work.runtime.ktx) // Corrected alias

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core)

    // --- Kotlinx Serialization ---
    implementation(libs.kotlinx.serialization.json)

    // --- Activity Result API ---
    implementation(libs.androidx.activity.ktx) // Needed for handling Google sign-in result

    // --- Unit Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    implementation(libs.timber)

    // --- Instrumented Testing ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
