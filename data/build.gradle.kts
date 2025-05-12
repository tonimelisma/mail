// File: data/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.hilt.gradle)
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
    implementation(project(":core-data")) // Includes ErrorMapperService now
    implementation(project(":backend-microsoft")) // For MS-specific components
    implementation(project(":backend-google")) // For Google-specific components

    // --- Google Identity Library ---
    implementation(libs.google.id)

    // --- AppAuth for OAuth 2.0 ---
    implementation(libs.appauth) // Needed for DefaultAccountRepository's AppAuth integration
    implementation(libs.androidx.browser) // For CustomTabs support in AppAuth

    // --- Hilt ---
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core)

    // --- Activity Result API ---
    implementation(libs.androidx.activity.ktx) // Needed for handling Google sign-in result

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