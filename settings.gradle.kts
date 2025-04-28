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

        // *** Add this repository for MSAL and its dependencies ***
        maven {
            url =
                uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            name = "MicrosoftDeviceSDK" // Optional: Just adds a name for clarity
        }
        // ********************************************************
    }
}

rootProject.name = "Mail"
include(":app")
include(":feature-auth")

