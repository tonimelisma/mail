# Melisma Mail - Architectural Design & Developer Guide

**Version:** 2.1 (Future State)
**Date:** May 24, 2025

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
* **Architecture:** MVVM with a Domain Layer (Use Cases)
* **Asynchronous Programming:** Kotlin Coroutines & Flow
* **Dependency Injection:** Hilt
* **Networking:** Ktor (with OkHttp engine)
* **Authentication:**
    * Google: AppAuth for Android (orchestrated by `GoogleAuthManager`) for OAuth 2.0 authorization
      code flow and token management.
    * Microsoft: MSAL (Microsoft Authentication Library) for Android.
* **Security:** Android Keystore via a custom `SecureEncryptionService`.

## 2. Core Architectural Principles

### 2.1. Overall Architecture

Melisma Mail employs a layered architecture:

* **UI Layer (`:app` module):** Responsible for presenting data to the user and handling user
  interactions.
* **Domain Layer (`:domain` module):** Contains discrete business logic in the form of Use Cases.
  This layer orchestrates data flow from the Data Layer to the UI Layer.
* **Data Layer (`:data` and `:core-data` modules):** Contains business logic, repository
  implementations, and data source definitions.
* **Backend/Provider Layer (`:backend-google`, `:backend-microsoft` modules):** Handles all
  provider-specific communication, including authentication and API interactions.

### 2.2. MVVM (Model-View-ViewModel)

The UI layer utilizes the MVVM pattern.

* **Views (Composables):** Observe state changes from ViewModels and render the UI. They delegate
  user actions to ViewModels.
* **ViewModels:** Prepare and manage UI-related data (state) for the Views. They delegate all
  business logic and data fetching operations to Use Cases. They expose state using
  `kotlinx.coroutines.flow.StateFlow`.
* **Models:** Represent the data structures (e.g., `Account`, `Message`, `MailFolder`).

### 2.2.1. Domain Layer & Use Cases

To ensure ViewModels remain lightweight and focused on UI logic, all significant business logic is
encapsulated within single-purpose `UseCase` classes in a dedicated `:domain` module. This addresses
the architectural finding F-ARCH-02.

* **Account & Auth Use Cases:** `ObserveAuthStateUseCase`, `GetAccountsUseCase`, `SignInUseCase`,
  `SignOutUseCase`.
* **Data Fetching Use Cases:** `GetFoldersForAccountUseCase`, `ObserveMessagesForFolderUseCase`,
  `ObserveThreadsForFolderUseCase`, `GetMessageDetailsUseCase`.
* **Mail Action Use Cases:** `MarkAsReadUseCase`, `DeleteMessageUseCase`, `MoveMessageUseCase`, etc.

### 2.3. Repository Pattern

Repositories abstract data sources. Use Cases interact with repository interfaces defined in
`:core-data`. Implementations in the `:data` module coordinate data from different sources.

### 2.4. Modularity

The application is divided into functionally distinct modules to promote separation of concerns,
reusability, and faster build times.

### 2.5. Dependency Injection (Hilt)

Hilt is used for managing dependencies throughout the application, simplifying DI and improving
testability.

### 2.6. Asynchronous Operations

Kotlin Coroutines and Flow are used extensively for managing background tasks, network requests, and
reactive data streams, ensuring a non-blocking UI.

### 2.7. Security

* **Token Storage:** OAuth tokens and other sensitive data are encrypted using
  `SecureEncryptionService` (which internally uses Android Keystore) before being stored.
* **Communication:** HTTPS is used for all network communication.
* **Provider Libraries:** MSAL and AppAuth are industry-standard libraries for handling OAuth 2.0
  flows securely.

## 3. Module Architecture Deep Dive

This section details the purpose, key components, and interactions within each module.

### 3.1. `:app` Module

* **Purpose & Scope:** The main application module, responsible for the user interface and user
  experience. It orchestrates UI navigation and presents data obtained from the data layer.
* **Key Components & Classes:**
    * `MainActivity.kt`: The primary entry point of the application. Hosts the Jetpack Navigation
      `NavHost` and handles `ActivityResultLauncher` callbacks for authentication flows.
    * **ViewModels** (e.g., `HomeViewModel`, `MessageDetailViewModel`): Scoped to navigation
      destinations, these ViewModels delegate tasks to Use Cases and manage UI state.
    * `MailApplication.kt`: The `Application` class, typically used for Hilt setup and other
      application-level initializations.
    * **UI Composables (various files/packages):**
        * `MainApp.kt`: Sets up the main `NavHost` and screen structure.
        * **Screens:**
            * `HomeScreen.kt`: The main screen featuring a `TopAppBar`, a `ModalNavigationDrawer`
              containing the folder list for the active account, the message/thread list, and a
              `FloatingActionButton` for composing new mail.
            * `MessageDetailScreen.kt`: Displays the content of a single message.
            * `ComposeScreen.kt`: UI for composing, replying to, or forwarding messages.
            * `SettingsScreen.kt`: A single screen for managing accounts and other app settings.
    * `di/AppProvidesModule.kt`: Provides application-level dependencies.
* **Data Flow:**
    * ViewModels call Use Cases.
    * Use Cases interact with Repositories.
    * Repositories return data via `Flow`s, which are collected by Use Cases and then exposed as
      `StateFlow` from the ViewModel to the UI.
* **Specific Patterns/Strategies:**
    * Unidirectional Data Flow (UDF) with `StateFlow` for managing UI state.
    * Jetpack Navigation for Compose to handle screen transitions.

### 3.2. `:core-data` Module

* **Purpose & Scope:** Defines the contracts (interfaces) and core data models for the entire
  application. It acts as the "single source of truth" for data structures and repository
  interfaces, ensuring decoupling between the UI layer and specific data implementations.
* **Key Components & Classes:**
    * **Repository Interfaces:** `AccountRepository.kt`, `FolderRepository.kt`,
      `MessageRepository.kt`, `ThreadRepository.kt`.
    * **Domain Models (e.g., in `model/` package):** `Account.kt`, `MailFolder.kt`, `Message.kt`,
      `MailThread.kt`, `AuthResultModels.kt`.
    * **Service Interfaces & Utilities:** `MailApiService.kt`, `ErrorMapperService.kt`,
      `SecureEncryptionService.kt`, `AuthConfigProvider.kt`.
    * **DI Helpers:** Hilt Qualifiers, Custom Hilt Scopes, `MailDispatchers.kt`.
* **Data Flow:** This module primarily defines interfaces and models, so it doesn't have active data
  flow itself but dictates how data should be structured and accessed.
* **Specific Patterns/Strategies:**
    * Interface Segregation: Clearly defined interfaces for repositories and services.
  * Dependency Inversion: `:app` and `:data` depend on abstractions in `:core-data`.

### 3.3. `:data` Module

* **Purpose & Scope:** Implements the repository interfaces defined in `:core-data`. It orchestrates
  data operations, acting as a bridge between the domain layer and the backend-specific services.
* **Key Components & Classes:**
    * `DefaultAccountRepository.kt`: The primary implementation of `AccountRepository`. Manages and
      combines account flows from both Google and Microsoft providers.
    * `DefaultFolderRepository.kt`: Implements `FolderRepository`.
    * `DefaultMessageRepository.kt`: Implements `MessageRepository`.
    * `DefaultThreadRepository.kt`: Implements `ThreadRepository`.
    * **DI Modules (e.g., in `di/DataModule.kt`):** Binds repository interfaces to their default
      implementations.
* **Data Flow:**
    * Use Cases call methods on repository interfaces.
    * `Default` repositories use injected `MailApiService` implementations (from `:backend-*`
      modules) to fetch data.
    * Data is transformed into the domain models defined in `:core-data` and exposed via `Flow`s.
* **Specific Patterns/Strategies:**
    * Repository Pattern.
  * Facade Pattern (`DefaultAccountRepository` acts as a facade for different auth providers).
    * Strategy Pattern (using a map of `MailApiService` implementations).

### 3.4. `:backend-microsoft` Module

* **Purpose & Scope:** Handles all Microsoft/Outlook specific logic, including MSAL-based
  authentication and Microsoft Graph API interactions.
* **Key Components & Classes:**
    * `MicrosoftAuthManager.kt`: The core MSAL wrapper.
  * `MicrosoftKtorTokenProvider.kt`: Implements Ktor's `BearerTokenProvider` to get access tokens
    silently.
    * `GraphApiHelper.kt`: Implements `MailApiService` for Microsoft Graph.
  * `MicrosoftErrorMapper.kt`: Implements `ErrorMapperService` for Microsoft-specific errors.
  * `di/BackendMicrosoftModule.kt`: Provides Ktor client and other dependencies for this module.
  * `MicrosoftAuthenticatorService.kt`, `MicrosoftStubAuthenticator.kt`: Components for
    `AccountManager` integration.
* **Data Flow:**
    * Repositories call `MicrosoftAuthManager` for auth and `GraphApiHelper` for data.
    * Data from Graph API is mapped to `:core-data` models.
* **Specific Patterns/Strategies:**
    * MSAL for all Microsoft authentication.
  * Ktor `Auth` plugin for automatic token injection.

### 3.5. `:backend-google` Module

* **Purpose & Scope:** Handles all Google/Gmail specific logic, including AppAuth-based API
  authorization and Gmail API interactions.
* **Key Components & Classes:**
    * `GoogleAuthManager.kt`: Centralizes Google-specific authentication logic, orchestrating
      AppAuth.
    * `AppAuthHelperService.kt`: Encapsulates all interactions with the AppAuth library.
    * `GoogleTokenPersistenceService.kt`: Manages secure storage of Google's `AuthState`.
    * `GoogleKtorTokenProvider.kt`: Implements Ktor's `BearerTokenProvider` for Google.
    * `GmailApiHelper.kt`: Implements `MailApiService` for the Gmail API.
    * `GoogleErrorMapper.kt`: Implements `ErrorMapperService` for Google-specific errors.
    * `di/BackendGoogleModule.kt`: Provides Ktor client and other dependencies for this module.
    * `GoogleAuthenticatorService.kt`, `GoogleStubAuthenticator.kt`: Components for `AccountManager`
      integration.
* **Data Flow:**
    * Repositories call `GoogleAuthManager` for auth flows and `GmailApiHelper` for data.
    * Data from Gmail API is mapped to `:core-data` models.
* **Specific Patterns/Strategies:**
    * AppAuth for Android for Google OAuth 2.0 API authorization.
    * Ktor `Auth` plugin for token injection.

## 4. Detailed Authentication & Authorization Flows

This section provides a step-by-step walkthrough of the authentication and authorization processes
for each provider.

### 4.1. Google OAuth Flow (AppAuth Orchestrated by GoogleAuthManager)

1. **Sign-In Initiation (UI -> ViewModel -> UseCase -> Repository -> `GoogleAuthManager`)**: User
   initiates sign-in, which calls down the stack to `GoogleAuthManager`, which uses
   `AppAuthHelperService` to create an `Intent`. This `Intent` is passed back up and emitted as
   `GenericAuthResult.UiActionRequired`.
2. **Authorization Grant (AppAuth UI Flow)**: `MainActivity` launches the `Intent`, showing the
   Google consent screen in a Custom Tab.
3. **Token Exchange & Persistence**: The redirect is handled, and `GoogleAuthManager` exchanges the
   authorization code for tokens, then persists the encrypted `AuthState` using
   `GoogleTokenPersistenceService`.
4. **Authenticated API Calls**: Ktor triggers `GoogleKtorTokenProvider`, which gets a fresh token
   via `GoogleAuthManager`, which in turn handles silent refresh if needed.
5. **Sign-Out**: The sign-out call revokes the token and clears the persisted `AuthState` from
   `AccountManager`.

### 4.2. Microsoft OAuth Flow (MSAL)

1. **MSAL Initialization**: `MicrosoftAuthManager` initializes the MSAL public client application on
   startup.
2. **Sign-In Initiation**: The sign-in call delegates to `MicrosoftAuthManager`, which calls MSAL's
   `acquireToken()` method. MSAL handles the UI flow, potentially using the broker app. The result
   is delivered via callback, and on success, account identifiers are encrypted and saved to
   `AccountManager`.
3. **Authenticated API Calls**: Ktor triggers `MicrosoftKtorTokenProvider`, which uses
   `MicrosoftAuthManager` to call MSAL's `acquireTokenSilentAsync()`. If silent refresh fails with
   `MsalUiRequiredException`, the account is marked as needing re-authentication.
4. **Sign-Out**: The sign-out call triggers MSAL's `removeAccount()` method, which clears all cached
   tokens for that account. Persisted identifiers are also removed from `AccountManager`.

## 5. UI Architecture (MVVM & Jetpack Compose)

* **Pattern:** MVVM with a domain layer is strictly followed.
* **ViewModels (`androidx.lifecycle.ViewModel`):**
    * Scoped to a specific navigation destination or graph.
    * Responsible for holding and managing UI-related state (`StateFlow`).
    * Delegate all business logic and data operations to Use Cases.
* **Views (Jetpack Compose Composables):**
    * Observe `StateFlow` from ViewModels using `collectAsStateWithLifecycle()`.
  * Stateless or stateful, with state often hoisted to the ViewModel.
* **State Management:**
    * Immutable data classes (e.g., `HomeScreenState`) represent the complete state for a screen.
    * ViewModels update state using `_uiState.update { currentState.copy(...) }`.
* **Navigation:**
    * Jetpack Navigation for Compose is the single source of truth for navigation.
    * The primary navigation graph includes the following routes:
        * `home`: The main screen for viewing messages.
        * `message_detail/{messageId}`: The screen for viewing a single message's content.
        * `compose`: Screen for writing a new email. Can be expanded with optional arguments for
          reply/forward.
        * `settings`: A single screen for app settings and account management.
* **Theming:**
    * Material 3 theming is applied using `Theme.kt`, `Color.kt`, `Type.kt`. Dynamic Color is
      supported.

## 6. Test Strategy

### 6.1. Goals

* Verify correctness of individual components (unit tests).
* Ensure proper interaction between components (integration tests).
* Prevent regressions during development and refactoring.
* Improve overall code design by promoting testability.

### 6.2. Scope

* **Unit Tests:** Focus on ViewModels, Repositories, Use Cases, Mappers, API Helpers, and
  Authentication Components.
* **Integration Tests (JVM-based):** Verify interactions between collaborating components within a
  module or across closely related modules.
* **UI/E2E Tests (Future):** End-to-end tests using Jetpack Compose testing APIs.

### 6.3. Test Levels & Techniques

* **Unit Tests:**
    * **Tools:** JUnit 4/5, MockK (for mocking), `kotlinx-coroutines-test`, Turbine (for testing
      `Flow`).
    * **Focus:** Isolate the class under test, mock its dependencies, and verify its logic.
* **Integration Tests (JVM):**
    * **Tools:** JUnit 4/5, Hilt.
    * **Focus:** Verify the contract between components.

### 6.4. Key Areas for Testing

* Authentication Flows.
* Data Repositories.
* ViewModels.
* Error Mappers.
* API Helpers.

### 6.5. Best Practices

* Tests should be readable, maintainable, and fast.
* Aim for good test coverage of business logic and critical paths.
* Tests should be independent.

## 7. Setup, Build, and Development Environment

### 7.1. Prerequisites

* Latest stable version of Android Studio.
* Android SDK.
* JDK.

### 7.2. Project Setup

1. **Clone the repository**.
2. **Open in Android Studio**.
3. **Sync Gradle**.
4. **Configuration (Client IDs, etc.):**
    * **Microsoft (MSAL):** Configure `auth_config_msal.json` with the correct `client_id` and
      `redirect_uri`.
    * **Google (AppAuth):** Configure the Android Client ID (often via `BuildConfig`) and the
      redirect URI in `AndroidManifest.xml`.

### 7.3. Build Commands

* **Build Project:** `./gradlew build`
* **Clean Project:** `./gradlew clean`
* **Install Debug APK:** `./gradlew installDebug`
* **Run Unit Tests:** `./gradlew testDebugUnitTest`
* **Run Android Instrumented Tests:** `./gradlew connectedDebugAndroidTest`

### 7.4. Development Guidance

* **Branching Strategy:** Follow a standard Git flow.
* **Code Style:** Adhere to Kotlin coding conventions.
* **Logging:** Use Timber for logging.
* **Addressing `TODO`s:** Regularly review and address `TODO` comments.

This document provides a foundational understanding of the Melisma Mail application's architecture
and development practices. It should be treated as a living document and updated as the project
evolves.
