plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "net.melisma.domain"
    compileSdk = 35 // Aligned with :app module

    defaultConfig {
        minSdk = 26 // Aligned with :app module
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22") // Ensure version matches project
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3") // Ensure version matches project

    // Hilt for Domain layer - changed to hilt-android and KSP
    implementation("com.google.dagger:hilt-android:2.56.2") // Align with project version & :core-data
    ksp("com.google.dagger:hilt-compiler:2.56.2") // Align with project version & :core-data

    // Project Modules
    api(project(":core-data")) // 'api' so downstream modules (:app) can see :core-data types if use cases expose them directly

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing (Optional for now, but good to set up)
    // testImplementation("junit:junit:4.13.2")
    // testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // testImplementation("io.mockk:mockk:1.13.5")
} 