// File: app/build.gradle.kts

import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.gradle)
}

android {
    namespace = "net.melisma.mail"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.melisma.mail"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Add AppAuth redirect scheme for Google OAuth
        manifestPlaceholders["appAuthRedirectScheme"] = "net.melisma.mail"

        testInstrumentationRunner = "net.melisma.mail.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Expose a field for each property. For fields that might not exist,
        // provide a default "null" value.
        buildConfigField(
            "String",
            "TEST_GMAIL_REFRESH_TOKEN",
            "\"${localProperties.getProperty("TEST_GMAIL_REFRESH_TOKEN", "null")}\""
        )
        buildConfigField(
            "String",
            "TEST_GMAIL_EMAIL",
            "\"${localProperties.getProperty("TEST_GMAIL_EMAIL", "null")}\""
        )
        buildConfigField(
            "String",
            "TEST_MS_ACCOUNT_ID",
            "\"${localProperties.getProperty("TEST_MS_ACCOUNT_ID", "null")}\""
        )
        buildConfigField(
            "String",
            "TEST_MS_EMAIL",
            "\"${localProperties.getProperty("TEST_MS_EMAIL", "null")}\""
        )
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // no-op
        }
        dex {
            excludes += "io/ktor/util/pipeline/use streaming syntax.class"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true // Good for MockK/Robolectric if used
            isIncludeAndroidResources = true // Enable access to Android resources in tests
            all {
                // Configure the test to continue even when there are failures
                // it.ignoreFailures = true // Removed as per R-CRITICAL-01
            }
        }
    }
    lint {
        // abortOnError = false // Allow builds to pass temporarily with lint errors
        // it.ignoreFailures = true // Removed as per R-CRITICAL-01
    }
}

dependencies {
    // --- Project Modules ---
    implementation(project(":core-data"))
    implementation(project(":core-db"))
    implementation(project(":data"))
    implementation(project(":domain"))
    implementation(project(":backend-microsoft"))
    implementation(project(":backend-google"))

    // MSAL dependency for BrowserTabActivity
    implementation(libs.microsoft.msal)

    // --- AppAuth ---
    implementation(libs.appauth)
    implementation(libs.androidx.browser)

    // --- AndroidX Core & Lifecycle ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material)
    implementation(libs.androidx.material.icons.extended)

    // --- Paging 3 ---
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // --- ViewModel & Lifecycle Compose Dependencies ---
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // --- Hilt ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core)

    // --- Unit Testing (test source set) ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk.core)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)

    testImplementation(project(":core-data"))
    testImplementation(project(":data"))
    testImplementation(project(":backend-microsoft"))
    testImplementation(project(":backend-google"))

    testImplementation(libs.microsoft.msal)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // --- Instrumented Testing (androidTest source set) ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.timber)

    // Hilt Navigation Compose
    implementation(libs.androidx.hilt.navigation.compose)

    // Hilt testing
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.51.1")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.51.1")

    // For assertions
    androidTestImplementation("com.google.truth:truth:1.4.2")

    // For coroutines testing
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

    implementation(libs.androidx.lifecycle.process)
}
