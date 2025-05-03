// File: build.gradle.kts (Project Level)

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    // --- Add Hilt Gradle Plugin ---
    alias(libs.plugins.hilt.gradle) apply false
}