# Melisma Mail - Architectural Design & Developer Guide (v2.1)

**Version:** 2.1 (Reflects Post-Refactoring State)
**Date:** Current Date

## 1. Introduction & Project Vision

### 1.1. Overview

Melisma Mail is an Android email client designed to offer a clean, intuitive, and native user
experience, adhering to Material 3 design principles. It aims to be a compelling alternative to
default email applications, initially supporting Microsoft Outlook and Google (Gmail) accounts, with
a focus on modern Android development practices.

### 1.2. Core Goals

* **User Experience:** Provide a seamless, performant, and visually appealing interface.
* **Modern Android Practices:** Leverage Kotlin, Jetpack Compose, Coroutines, Flow, Hilt, and other
  modern Android libraries and patterns.
* **Security:** Ensure secure handling of user credentials and data, particularly OAuth tokens.
* **Modularity:** Maintain a well-defined, modular codebase for better maintainability, scalability,
  and testability.

### 1.3. Key Technologies

* **UI:** Jetpack Compose
* **Architecture:** MVVM (Model-View-ViewModel)
* **Asynchronous Programming:** Kotlin Coroutines & Flow
* **Dependency Injection:** Hilt
* **Networking:** Ktor (with OkHttp engine)
* **Authentication:**
    * Google: AppAuth for Android (orchestrated by `GoogleAuthManager`) for OAuth 2.0 authorization
      code flow and token management.
    * Microsoft: MSAL (Microsoft Authentication Library) for Android (orchestrated by
      `MicrosoftAuthManager` and `MicrosoftTokenPersistenceService`).
* **Security:** Android Keystore via a custom `SecureEncryptionService`.

## 2. Core Architectural Principles

### 2.1. Overall Architecture

Melisma Mail employs a layered architecture:

* **UI Layer (`:app` module):** Responsible for presenting data to the user and handling user
  interactions.
* **Domain/Data Layer (`:data` and `:core-data` modules):** Contains business logic, repository
  implementations, and data source definitions.
* **Backend/Provider Layer (`:backend-google`, `:backend-microsoft` modules):** Handles all
  provider-specific communication, including authentication and API interactions.

### 2.2. MVVM (Model-View-ViewModel)

The UI layer utilizes the MVVM pattern. ViewModels interact with repositories, prepare UI state (
`StateFlow`), and Composables observe this state.

### 2.3. Repository Pattern

Repositories abstract data sources. ViewModels interact with repository interfaces defined in
`:core-data`. Implementations in the `:data` module coordinate data.

### 2.4. Modularity

The application is divided into functionally distinct modules.

### 2.5. Dependency Injection (Hilt)

Hilt is used for managing dependencies.

### 2.6. Asynchronous Operations

Kotlin Coroutines and Flow are used extensively.

### 2.7. Security

* **Token Storage:** OAuth tokens and sensitive data are encrypted using `SecureEncryptionService` (
  Android Keystore) before being stored via `AccountManager` (Google's `AuthState` by
  `GoogleTokenPersistenceService`, Microsoft account details by `MicrosoftTokenPersistenceService`).
* **Communication:** HTTPS for all network communication.
* **Provider Libraries:** MSAL and AppAuth are industry-standard.

## 3. Module Architecture Deep Dive

### 3.1. `:app` Module

* **Purpose:** Main application module for UI and user experience.
* **Key Components:** `MainActivity.kt`, `MainViewModel.kt`, UI Composables.
* **Data Flow:** ViewModels observe repository `StateFlow`s; Composables observe ViewModel
  `StateFlow`s.

### 3.2. `:core-data` Module

* **Purpose:** Defines contracts (interfaces) and core data models.
* **Key Components:**
    * Repository Interfaces (`AccountRepository`, `FolderRepository`, etc.).
    * Domain Models (`Account`, `Message`, `GenericAuthResult`, etc.).
    * Service Interfaces (`MailApiService`, `ErrorMapperService`, `SecureEncryptionService`).
    * `PersistenceResult.kt` & provider-specific error enums (e.g., `GooglePersistenceErrorType` in
      `:backend-google`, `MicrosoftPersistenceErrorType` in `:backend-microsoft`) for structured
      error handling from persistence layers.

### 3.3. `:data` Module

* **Purpose:** Implements repository interfaces from `:core-data`.
* **Key Components:**
    * `DefaultAccountRepository.kt`: Implements `AccountRepository`.
        * Orchestrates Google authentication by delegating to `GoogleAuthManager`.
        * Delegates Microsoft authentication to an injected `MicrosoftAccountRepository` (from
          `:backend-microsoft`).
        * Manages `OverallApplicationAuthState`.
    * Other `Default*Repository` classes (e.g., `DefaultFolderRepository`) use injected
      `MailApiService` implementations from backend modules.

### 3.4. `:backend-microsoft` Module

* **Purpose:** Handles Microsoft/Outlook specific logic.
* **Key Components:**
    * `MicrosoftAuthManager.kt`: Core MSAL wrapper. Manages
      `IMultipleAccountPublicClientApplication`, sign-in/out, silent token acquisition. Delegates
      persistence to `MicrosoftTokenPersistenceService`. Updates `ActiveMicrosoftAccountHolder`.
    * `MicrosoftTokenPersistenceService.kt`: Manages CRUD operations for Microsoft account data (
      identifiers, non-critical tokens) using `AccountManager` and `SecureEncryptionService`.
      Returns `PersistenceResult`.
    * `MicrosoftKtorTokenProvider.kt`: Ktor `BearerTokenProvider`. Uses `MicrosoftAuthManager` for
      tokens.
    * `GraphApiHelper.kt`: Implements `MailApiService` using Ktor client with
      `MicrosoftKtorTokenProvider`.
    * `MicrosoftErrorMapper.kt`: Maps MSAL/Graph errors.
    * `ActiveMicrosoftAccountHolder.kt`: Holds active MS account ID.
    * `ManagedMicrosoftAccount.kt`: Data class representing an MSAL account enriched with persisted
      details.
    * `MicrosoftAccountRepository.kt` (in `repository` sub-package): Implements `AccountRepository`
      for Microsoft, using `MicrosoftAuthManager`.
    * DI modules for providing Ktor client, services, etc.

### 3.5. `:backend-google` Module

* **Purpose:** Handles Google/Gmail specific logic.
* **Key Components:**
    * `GoogleAuthManager.kt`: Centralizes Google authentication.
        * Orchestrates `AppAuthHelperService` for AppAuth flow.
        * Manages `GoogleTokenPersistenceService` for `AuthState` persistence.
        * Provides methods like `signInInteractive`, `handleAuthorizationResponse`,
          `getFreshAccessToken`, `signOut`, etc.
        * Updates `ActiveGoogleAccountHolder`.
    * `AppAuthHelperService.kt`: Encapsulates AppAuth library interactions (request creation, token
      exchange/refresh, revocation).
    * `GoogleTokenPersistenceService.kt`: Manages secure storage of Google's `AuthState` (JSON)
      using `AccountManager` and `SecureEncryptionService`. Returns `PersistenceResult`.
    * `GoogleKtorTokenProvider.kt`: Ktor `BearerTokenProvider`. Uses `GoogleAuthManager` (
      specifically `getFreshAccessToken`) for tokens. Handles
      `GoogleNeedsReauthenticationException`.
    * `GmailApiHelper.kt`: Implements `MailApiService` using Ktor client with
      `GoogleKtorTokenProvider`.
    * `GoogleErrorMapper.kt`: Maps AppAuth/Gmail API errors.
    * `ActiveGoogleAccountHolder.kt`: Holds active Google account ID.
    * `ManagedGoogleAccount.kt`: Data class representing a Google account with its `AuthState` and
      user info.
    * DI modules for Ktor client, services, etc.

## 4. Detailed Authentication & Authorization Flows

### 4.1. Google OAuth Flow (AppAuth Orchestrated by `GoogleAuthManager`)

1. **Sign-In Initiation (UI -> ViewModel -> `DefaultAccountRepository` -> `GoogleAuthManager`)**:
    * ViewModel calls `AccountRepository.signIn("GOOGLE", ...)`.
    * `DefaultAccountRepository` calls `googleAuthManager.signInInteractive(...)`.
    * `GoogleAuthManager` uses `appAuthHelperService` to create an auth `Intent`.
    * `DefaultAccountRepository` emits `GenericAuthResult.UiActionRequired(intent)`.
2. **Authorization Grant (AppAuth UI Flow)**:
    * `MainActivity` launches the `Intent`. AppAuth handles user auth & consent via Custom Tab.
3. **Token Exchange & Persistence (Redirect -> `DefaultAccountRepository` -> `GoogleAuthManager`)**:
    * `MainActivity` passes result to `AccountRepository.handleAuthenticationResult("GOOGLE", ...)`.
    * `DefaultAccountRepository` calls `googleAuthManager.handleAuthorizationResponse(...)`.
    * `GoogleAuthManager` uses `appAuthHelperService` to exchange code for tokens, then
      `googleTokenPersistenceService` to save `AuthState` and user info. Updates
      `ActiveGoogleAccountHolder`. Emits `GoogleSignInResult`.
    * `DefaultAccountRepository` maps to `GenericAuthResult`.
4. **Authenticated API Calls (`GmailApiHelper` -> `GoogleKtorTokenProvider` -> `GoogleAuthManager`)
   **:
    * Ktor invokes `GoogleKtorTokenProvider.getBearerTokens()`.
    * Provider calls `googleAuthManager.getFreshAccessToken(...)`.
    * `GoogleAuthManager` retrieves `AuthState` (via `googleTokenPersistenceService`), refreshes
      token if needed (via `appAuthHelperService`), updates persisted `AuthState`. Handles
      `invalid_grant` by clearing tokens and returning `NeedsReauthentication`.
5. **Sign-Out (`DefaultAccountRepository` -> `GoogleAuthManager`)**:
    * `googleAuthManager.signOut(...)` is called.
    * `GoogleAuthManager` uses `appAuthHelperService` to revoke token (if possible),
      `googleTokenPersistenceService` to clear `AuthState`, and updates `ActiveGoogleAccountHolder`.

### 4.2. Microsoft OAuth Flow (MSAL Orchestrated by `MicrosoftAuthManager`)

1. **MSAL Initialization (`MicrosoftAuthManager`)**: Initializes
   `IMultipleAccountPublicClientApplication`.
2. **Sign-In
   Initiation (`DefaultAccountRepository` -> `MicrosoftAccountRepository` ->`MicrosoftAuthManager`)
   **:
    * `microsoftAuthManager.signInInteractive(...)` called. MSAL handles UI.
    * On success, `MicrosoftAuthManager` uses `microsoftTokenPersistenceService` to save account
      info. Updates `ActiveMicrosoftAccountHolder`.
3. **Authenticated API
   Calls (`GraphApiHelper` -> `MicrosoftKtorTokenProvider` -> `MicrosoftAuthManager`)**:
    * `MicrosoftKtorTokenProvider` calls `microsoftAuthManager.acquireTokenSilent(...)`.
    * MSAL attempts silent refresh. Handles `MsalUiRequiredException`.
4. **Sign-Out (`DefaultAccountRepository` -> `MicrosoftAccountRepository` -> `MicrosoftAuthManager`)
   **:
    * `microsoftAuthManager.signOut(...)` called.
    * MSAL removes account. `MicrosoftAuthManager` then uses `microsoftTokenPersistenceService` to
      clear persisted data. Updates `ActiveMicrosoftAccountHolder`.

## 5. UI Architecture (MVVM & Jetpack Compose)

* Follows standard MVVM with Jetpack Compose.
* ViewModels expose `StateFlow` for UI state.
* Composables observe state using `collectAsStateWithLifecycle()`.
* Jetpack Navigation for Compose handles screen transitions.
* Material 3 Theming.

## 6. Test Strategy

### 6.1. Goals

* Verify correctness, ensure proper interactions, prevent regressions.
* Promote testability.

### 6.2. Scope

* **Unit Tests:** ViewModels, Repositories, Mappers, Auth Managers, Persistence Services, API
  Helpers.
* **Integration Tests (JVM-based):** Interactions between components (e.g., ViewModel-Repository,
  Repository-AuthManager-PersistenceService).
* **UI/E2E Tests (Future):** Full user flows.

### 6.3. Tools

* JUnit 4/5, MockK, `kotlinx-coroutines-test`, Turbine.

### 6.4. Key Areas

* Authentication flows (success, error, cancellation, refresh).
* Data Repositories (fetching, mapping, error handling).
* ViewModels (state updates, delegation).

## 7. Setup, Build, and Development Environment

### 7.1. Prerequisites

* Latest stable Android Studio.
* Android SDK.
* JDK.

### 7.2. Project Setup

1. Clone repository.
2. Open in Android Studio & Sync Gradle.
3. **Configuration:**
    * **Microsoft (MSAL):** `app/src/main/res/raw/auth_config_msal.json` (client ID, redirect URI).
    * **Google (AppAuth):**
        * **Android Client ID (for AppAuth):** Used by `AppAuthHelperService`. Often provided via
          `BuildConfig` from `backend-google/build.gradle.kts`. Linked to package name & SHA-1 in
          Google Cloud Console.
        * **Redirect URI (for AppAuth):** Custom scheme (e.g., `net.melisma.mail:/oauth2redirect`)
          in `AndroidManifest.xml` (app module or backend-google for placeholder) and Google Cloud
          Console.
    * Ensure any `buildConfigField` values for client IDs or redirect URIs in module
      `build.gradle.kts` files are correctly set.

### 7.3. Build Commands

* Standard Gradle commands: `./gradlew build`, `./gradlew clean`, `./gradlew testDebugUnitTest`,
  etc.

### 7.4. Development Guidance

* Follow Kotlin coding conventions. Use Android Studio formatter.
* Use Timber for logging.
* Address `TODO`s; refer to `TODO.md` for larger items.

This document serves as the current architectural reference. 