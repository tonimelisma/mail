// File: build.gradle.kts (Project Level)

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt.gradle) apply false
}

subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(libs.versions.kotlin.get())
                because("Enforce consistent Kotlin version ${libs.versions.kotlin.get()} across all modules and configurations")
            }
        }
    }
}