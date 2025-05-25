# Melisma Mail - Architectural Design & Developer Guide

**Version:** 2.2 (Offline-First Vision)
**Date:** May 25, 2025

## 1. Introduction & Project Vision

### 1.1. Overview

Melisma Mail is an Android email client designed to offer a clean, intuitive, and native user
experience, adhering to Material 3 design principles. It aims to be a compelling alternative to
default email applications, initially supporting Microsoft Outlook and Google (Gmail) accounts, with
a focus on modern Android development practices.

### 1.2. Core Goals

* **User Experience:** Provide a seamless, performant, and visually appealing interface that works
  smoothly online and offline.
* **Modern Android Practices:** Leverage Kotlin, Jetpack Compose, Coroutines, Flow, Hilt, Room, and
  WorkManager.
* **Security:** Ensure secure handling of user credentials and data, particularly OAuth tokens.
* **Modularity:** Maintain a well-defined, modular codebase for better maintainability, scalability,
  and testability.

### 1.3. Key Technologies

* **UI:** Jetpack Compose
* **Architecture:** Offline-First MVVM with a Domain Layer (Use Cases)
* **Local Storage:** Room
* **Background Processing:** WorkManager
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

Melisma Mail employs a layered architecture designed for an offline-first experience:

* **UI Layer (`:app` module):** Responsible for presenting data to the user. Interacts exclusively
  with the local database via ViewModels and Use Cases.
* **Domain Layer (`:domain` module):** Contains discrete business logic in the form of Use Cases.
* **Data Layer (`:data` module):** Implements repository interfaces. Its primary role is to act as a
  **synchronizer** between network data sources (APIs) and the local database.
* **Database Layer (`:core-db` module):** Defines the Room database schema, entities, and Data
  Access Objects (DAOs).
* **Backend/Provider Layer (`:backend-google`, `:backend-microsoft` modules):** Handles all
  provider-specific API communication.
* **Contracts Layer (`:core-data` module):** Defines interfaces and data models for the application.

### 2.2. Offline-First and Single Source of Truth

The application is architected to be "offline-first". The UI layer is completely decoupled from the
network. It observes `Flow`s directly from the Room database DAOs. All user mutations (deleting,
marking as read) are applied to the local database first, then queued for synchronization with the
server. The local database, defined in `:core-db`, is the **single source of truth** for the entire
application state.

### 2.3. MVVM (Model-View-ViewModel)

The UI layer utilizes the MVVM pattern.

* **Views (Composables):** Observe state changes from ViewModels and render the UI. They delegate
  user actions to ViewModels.
* **ViewModels:** Prepare and manage UI-related data (state) for the Views. They delegate all
  business logic and data operations to Use Cases.
* **Models:** Represent the data structures (e.g., `Account`, `Message`, `MailFolder`).

### 2.4. Domain Layer & Use Cases

All business logic is encapsulated within single-purpose `UseCase` classes in the `:domain` module.
This ensures ViewModels remain lightweight. Key use cases include `SignInUseCase`,
`ObserveMessagesUseCase`, `MarkAsReadUseCase`, and `SyncAccountUseCase`.

### 2.5. Repository Pattern

Repositories in the `:data` module abstract data sources and manage the synchronization logic
between the network and the local database.

### 2.6. Modularity

The application is divided into functionally distinct modules to promote separation of concerns,
reusability, and faster build times.

### 2.7. Dependency Injection (Hilt)

Hilt is used for managing dependencies throughout the application.

### 2.8. Asynchronous Operations & Sync Strategy

The application uses a three-pillar strategy for data synchronization, managed primarily by
`WorkManager`.

1. **Initial Sync:** A one-time `WorkManager` job triggered on new account login to perform a bulk
   download of recent message headers and specified attachments.
2. **Periodic Background Sync:** A recurring `PeriodicWorkRequest` via `WorkManager` to check for
   new mail at user-defined intervals (e.g., every 15 minutes), ensuring battery efficiency.
3. **Real-Time Foreground Sync:**
    * **Phase 1 (Initial Implementation):** A "Lifecycle-Aware Smart Sync" mechanism. This involves
      frequent, short-interval polling for new messages triggered only when the application is in
      the foreground, providing a near-real-time experience.
    * **Phase 2 (Future Vision):** Evolve to a direct-to-client connection model where supported.
      For Gmail, this involves using the Google Cloud Pub/Sub client library. For IMAP providers,
      this uses the `IDLE` command. For providers like Microsoft Graph that do not support a direct
      client-side stream, the smart sync polling mechanism will remain the primary method.

## 3. Module Architecture Deep Dive

This section details the purpose, key components, and interactions within each module.

### 3.1. `:app` Module

* **Purpose & Scope:** The main application module, responsible for the user interface and user
  experience. It orchestrates UI navigation and presents data obtained from the local database.
* **Key Components & Classes:**
    * `MainActivity.kt`: The primary entry point of the application. Hosts Jetpack Navigation
      `NavHost` and handles `ActivityResultLauncher` callbacks for authentication flows.
    * **ViewModels** (e.g., `HomeViewModel`, `MessageDetailViewModel`): Scoped to navigation
      destinations, these ViewModels delegate tasks to Use Cases and manage UI state.
    * `MailApplication.kt`: The `Application` class, typically used for Hilt setup and other
      application-level initializations.
    * **UI Composables (various files/packages):**
        * `MainApp.kt`: Sets up the main navigation and screen structure.
        * Screens (e.g., `HomeScreen.kt`, `SettingsScreen.kt`): Define the layout and behavior of
          different parts of the UI.
        * Component Composables (e.g., `MessageListItem.kt`): Reusable UI elements.
* **Data Flow:** ViewModels call Use Cases -> Use Cases call Repositories -> Repositories
  synchronize the local DB with the network -> UI observes `Flow`s from the DB via the ViewModel.

### 3.2. `:core-data` Module

* **Purpose & Scope:** Defines the contracts (interfaces) and core data models for the entire
  application. It acts as the "single source of truth" for data structures and repository
  interfaces, ensuring decoupling between the UI layer and specific data implementations.
* **Key Components & Classes:**
    * **Repository Interfaces:** `AccountRepository.kt`, `FolderRepository.kt`,
      `MessageRepository.kt`, `ThreadRepository.kt`.
    * **Domain Models:** `Account.kt`, `MailFolder.kt`, `Message.kt`, etc.
    * **Service Interfaces:** `MailApiService.kt`, `ErrorMapperService.kt`, etc.

### 3.3. `:data` Module

* **Purpose & Scope:** Implements the repository interfaces from `:core-data`. It orchestrates data
  synchronization, acting as the bridge between the backend services and the local database.
* **Key Components & Classes:**
    * `DefaultAccountRepository.kt`: Implements `AccountRepository`, synchronizing account info.
    * `DefaultFolderRepository.kt`: Implements `FolderRepository`, synchronizing folder lists.
    * `DefaultMessageRepository.kt`: Implements `MessageRepository`, synchronizing messages.

### 3.4. `:core-db` Module (New)

* **Purpose & Scope:** Defines the application's local database schema using Room. It is the single
  source of truth for all persisted application data.
* **Key Components & Classes:**
    * `AppDatabase.kt`: The main Room database class.
    * **Entities:** `@Entity` classes like `AccountEntity`, `FolderEntity`, `MessageEntity`.
    * **DAOs:** `@Dao` interfaces for database access.

### 3.5. `:backend-microsoft` Module

* **Purpose & Scope:** Handles all Microsoft/Outlook specific logic, including MSAL-based
  authentication and Microsoft Graph API interactions.
* **Key Components & Classes:** `MicrosoftAuthManager.kt`, `GraphApiHelper.kt` (implements
  `MailApiService`).

### 3.6. `:backend-google` Module

* **Purpose & Scope:** Handles all Google/Gmail specific logic, including AppAuth-based API
  authorization and Gmail API interactions.
* **Key Components & Classes:** `GoogleAuthManager.kt`, `GmailApiHelper.kt` (implements
  `MailApiService`).

## 4. Detailed Authentication & Authorization Flows

*(This section's core logic remains the same, but repositories now save results to the local DB upon
success.)*

### 4.1. Google OAuth Flow (AppAuth Orchestrated by GoogleAuthManager)

1. **Sign-In Initiation**: `UiActionRequired` is emitted.
2. **Authorization Grant**: User grants permissions via Custom Tab.
3. **Token Exchange & Persistence**: Code is exchanged for tokens; on success,
   `DefaultAccountRepository` saves the new `AccountEntity` to the database.
4. **Authenticated API Calls**: Ktor client uses token provider to make authenticated calls.
5. **Sign-Out**: Tokens are revoked, and the `AccountEntity` is deleted from the database.

### 4.2. Microsoft OAuth Flow (MSAL)

1. **MSAL Initialization**: MSAL client is initialized.
2. **Sign-In Initiation**: MSAL handles the UI flow; on success, `DefaultAccountRepository` saves
   the new `AccountEntity` to the database.
3. **Authenticated API Calls**: Ktor client uses token provider for silent token acquisition.
4. **Sign-Out**: MSAL removes the account, and `DefaultAccountRepository` deletes the corresponding
   `AccountEntity`.

## 5. UI Architecture (MVVM & Jetpack Compose)

* **Pattern:** Offline-First MVVM with a domain layer is strictly followed.
* **ViewModels (`androidx.lifecycle.ViewModel`):** Scoped to navigation destinations, they hold UI
  state and delegate logic to Use Cases.
* **Views (Jetpack Compose Composables):** Observe `StateFlow` from ViewModels using
  `collectAsStateWithLifecycle()`.
* **State Management:** Immutable data classes represent screen state.
* **Navigation:** Jetpack Navigation for Compose is used with routes like `home`,
  `message_detail/{messageId}`, and `settings`.
* **Theming:** Material 3 theming with Dynamic Color is supported.

## 6. Test Strategy

* **Goals:** Verify correctness, ensure proper interaction, prevent regressions.
* **Scope:**
    * **Unit Tests:** Focus on ViewModels, Repositories (synchronization logic), Use Cases, Mappers,
      and API Helpers.
    * **Integration Tests:** Verify interactions between Repositories and DAOs.
    * **UI/E2E Tests:** Validate full user flows.
* **Tools:** JUnit, MockK, `kotlinx-coroutines-test`, Turbine, Hilt testing APIs.

## 7. Setup, Build, and Development Environment

* **Prerequisites:** Latest stable Android Studio, SDK, JDK.
* **Project Setup:** Clone repo, sync Gradle, configure client IDs in `auth_config_msal.json` and
  `build.gradle.kts`.
* **Build Commands:** `./gradlew build`, `./gradlew testDebugUnitTest`, etc.
* **Development Guidance:** Follow standard Git flow, use Timber for logging.