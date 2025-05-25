// File: settings.gradle.kts (Project Root)

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Configure where Gradle looks for dependency artifacts
dependencyResolutionManagement {
    // Recommended mode: Fail if repositories are declared elsewhere (e.g., module build files)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Standard repositories
        google()
        mavenCentral()

        // *** Repository for MSAL and its dependencies ***
        maven {
            url =
                uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            name = "MicrosoftDeviceSDK" // Optional: Just adds a name for clarity
        }
        // ********************************************************
    }
}

rootProject.name = "Mail"
// Include all modules in the project build
include(":app")
include(":core-data")
include(":backend-microsoft")
include(":backend-google")  // New Google backend module
include(":data")
include(":domain") // Add the new domain module
