plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.melisma.mail"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.melisma.mail"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    // composeOptions {
    //     kotlinCompilerExtensionVersion = "..." // Specify if needed
    // }
}

dependencies {
    implementation(project(":feature-auth"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // --- ViewModel and Lifecycle Compose Dependencies ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7") // Use appropriate version
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")  // Use appropriate version
    // --- End ViewModel Dependencies ---

    // --- Material Icons Dependencies ---
    implementation("androidx.compose.material:material-icons-core:1.7.8") // Use latest stable version
    implementation("androidx.compose.material:material-icons-extended:1.7.8") // Use latest stable version
    // --- End Material Icons Dependencies ---

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}