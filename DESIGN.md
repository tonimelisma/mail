# Melisma Mail - Comprehensive Project Guide

**Version:** 1.7 (Consolidated and Updated on May 11, 2025 - AppAuth Strategy for Google)

## 1. Project Overview

Melisma Mail is an Android email client aiming to provide a clean, native user experience similar to
stock Pixel apps, using Material 3. It's being built as a potential replacement for default mail
apps, starting with support for Microsoft Outlook accounts, with plans to support Google (Gmail).

The application follows a modular architecture utilizing Hilt for dependency injection and Jetpack
Compose with ViewModels (MVVM pattern) for the UI layer. Key technologies include Kotlin,
Coroutines, Flow, Ktor for networking. For Google OAuth, it uses
`androidx.credentials.CredentialManager` for initial sign-in and **AppAuth for Android** for API
authorization and token management.

### 1.1. Current Status (As of May 11, 2025)

* **Microsoft Account Support:**
    * Users can securely sign in and out using Microsoft accounts (MSAL).
    * Users can fetch and view a list of their mail folders after signing in.
  * Core refactoring is largely complete for Microsoft.
* **Google Account Support (Strategy Update: AppAuth for API Authorization):**
    * Foundational components in `:backend-google` are being adapted for the AppAuth strategy:
        * `GoogleAuthManager` will use `androidx.credentials.CredentialManager` for initial Google
          Sign-In (ID Token retrieval using Web Client ID).
        * **AppAuth for Android library** will be integrated to handle the OAuth2 authorization code
          grant flow (obtaining access and refresh tokens for Gmail API scopes using Android Client
          ID and explicit PKCE).
        * `GmailApiHelper` (for Gmail API interaction using Ktor) will consume tokens obtained via
          the AppAuth flow.
        * `GoogleErrorMapper` remains relevant.
    * **Token Storage (Google):** Tokens obtained via the AppAuth flow will be encrypted using
      Android Keystore-backed keys and then stored in `AccountManager` via a dedicated
      `GoogleTokenPersistenceService`.
    * **Key Pending Work for Google Integration:** Detailed in `GOOGLE.MD`. This involves
      integrating AppAuth, refactoring `GoogleAuthManager`, setting up token persistence with
      `AccountManager` (`GoogleTokenPersistenceService`), and wiring this new flow into the data and
      UI layers.

### 1.2. Setup & Build

1.  **Clone the repository.**
2.  **Open in Android Studio:** Use the latest stable version.
3. **Sync Gradle:** Allow dependencies to sync. Ensure necessary Android SDK versions are installed.
   This will include adding the `net.openid:appauth` library.
4.  **Permissions:** The app requires `INTERNET` permission (declared in `AndroidManifest.xml`).
5. **Build/Run:**
    * Build: `./gradlew build`
    * Install and run on connected device/emulator: `./gradlew installDebug` (or use Android
      Studio's run configurations).

## 2. Requirements & Architecture

### 2.1. Core Principles

* **UI Layer:** Jetpack Compose, ViewModels (MVVM), StateFlow.
* **Data Layer:** Repository pattern, Coroutines/Flow.
* **Dependency Injection:** Hilt.
* **Modularity:** See section 2.2.
* **Google OAuth Strategy:**
    * Initial Authentication (ID Token): `androidx.credentials.CredentialManager`.
    * API Authorization & Token Management (Access/Refresh Tokens): **AppAuth for Android**.

### 2.2. Modules (Revised May 11, 2025 - AppAuth Strategy)

* **:app**: Main application module with UI (Jetpack Compose), ViewModels, and the `MailApplication`
  class. Handles UI-specific logic like launching AppAuth's authorization intent (likely from
  `MainActivity` via an `ActivityResultLauncher` triggered by ViewModel).
* **:core-data**: Defines core data interfaces (e.g., generic `AccountRepository`,
  `FolderRepository`, `MessageRepository`), core domain models (Account, MailFolder, Message, etc.),
  DI helpers, shared service interfaces like `ErrorMapperService`, and the `MailApiService`.
* **:data**: Implements repository interfaces from `:core-data`. `DefaultAccountRepository`
  orchestrates calls to backend-specific modules. For Google, it will coordinate with
  `GoogleAuthManager` (for initial ID token), trigger the AppAuth flow (likely via
  `AppAuthHelperService`), and use `GoogleTokenPersistenceService` for `AccountManager` token
  operations.
* **:backend-microsoft**: Handles Microsoft/Outlook specific logic: MSAL authentication (
  `MicrosoftAuthManager`, `MicrosoftTokenProvider`), Microsoft Graph API interaction (
  `GraphApiHelper` - implements `MailApiService`), Ktor client setup for Graph, and
  `MicrosoftErrorMapper`. (Largely unchanged by Google strategy shift).
* **:backend-google**: Handles Google/Gmail specific logic:
    * `GoogleAuthManager`: Manages initial Google Sign-In (ID tokens via `CredentialManager` using
      Web Client ID). May coordinate launching the AppAuth flow.
    * `AppAuthHelperService` (or similar): Encapsulates AppAuth library interactions (building
      authorization requests with PKCE, performing token requests using Android Client ID, handling
      redirects).
    * `GoogleTokenPersistenceService`: Manages secure storage (encryption via Keystore) of Google
      OAuth tokens (obtained from AppAuth flow) into `AccountManager`.
    * `SecureEncryptionService`: A utility service (potentially in `:core-data` or a shared util
      module) for Keystore-based encryption/decryption, used by `GoogleTokenPersistenceService`.
    * `GmailApiHelper`: Handles Gmail API interaction (implements `MailApiService`) using Ktor, with
      tokens supplied from `GoogleTokenPersistenceService`.
    * Ktor client setup for Gmail (including `Auth` plugin for token refresh).
    * `GoogleErrorMapper`: Handles Google-specific error mapping.

### 2.3. Prioritized Requirements Backlog (Epics)

The application development follows a prioritized backlog of features grouped into Epics.

---
**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a
  selected folder.
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email in the list to
  view its full content.
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail
  folders.
* **Requirement 1.4 (Unified Inbox - UI/UX):** As a user, I want an option to view emails from all
  my accounts in a single combined inbox view.
* **Requirement 1.5 (Conversation View - UI/UX):** As a user, I want emails belonging to the same
  thread to be grouped.

---
**EPIC 2: Basic Mail Actions** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread.
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages.
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages.
* **Requirement 2.4 (Customizable Swipe Actions - UI/UX):** As a user, I want to configure actions
  for swipes on the message list.

---
**EPIC 3: Composing & Sending** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to initiate composing a new email.
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email.
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a
  received message.
* **Requirement 3.4 (Signature Management):** As a user, I want to define and manage email
  signatures.

---
**EPIC 4: Attachments** (Higher Priority)
* **Requirement 4.1 (View Attachments):** As a user, I want to see a list of attachments within a received email.
* **Requirement 4.2 (Preview Attachments):** As a user, I want to preview common attachment types.
* **Requirement 4.3 (Save Attachments):** As a user, I want to save attachments from an email.
* **Requirement 4.4 (Share Attachments):** As a user, I want to share attachments directly from an
  email.
* **Requirement 4.5 (Attach Files):** As a user, I want to easily attach files when composing an
  email.

---
**EPIC 5: Account & App Foundation** (Underpins everything)

* Authentication & Core:
    * **Requirement 5.1 (Authentication - Functional):** As a user, I want to securely sign in and
      out of my supported email accounts (Microsoft Outlook done, Google Gmail in progress using
      Credential Manager for initial sign-in and AppAuth for API authorization).
    * **Requirement 5.2 (Basic Error Handling - Functional):** As a user, I want to see clear
      messages if actions fail.
* User Experience:
    * **Requirement 5.3 (Visual Polish - Non-Functional):** The app should adhere to Material 3
      guidelines.
    * **Requirement 5.4 (Performance - Non-Functional):** UI interactions should feel smooth and
      responsive.
    * **Requirement 5.5 (Theming - UI/UX):** As a user, I want the app to support Light and Dark
      themes.
* Offline & Sync:
    * **Requirement 5.6 (Data Caching - Non-Functional):** The app should cache data locally.
  * **Requirement 5.7 (Background Sync - Functional):** As a user, I want the app to periodically
    check for new emails.
* Notifications:
    * **Requirement 5.8 (Push Notifications - Functional):** As a user, I want to receive
      notifications for new emails.
    * **Requirement 5.9 (Actionable Notifications - Functional):** As a user, I want to perform
      quick actions from an email notification.
* Analytics & Logging:
    * **Requirement 5.10 (Local Error Logging - Non-Functional):** The app must log critical errors locally.
    * **Requirement 5.11 (Log Export - Functional):** As a user/developer, I want a way to export local error logs.
    * **Requirement 5.12 (Usage Metrics - Non-Functional):** The app should gather anonymized basic usage metrics locally.
* **NEW Requirement 5.13 (New Device Account Restoration - Google):** As a user, I want an easy way
  to restore my Google account linkage when setting up Melisma Mail on a new Android device (via
  Credential Manager's Restore Credentials feature).

---
**EPIC 6: Advanced Mail Organization** (Medium Priority)

* **Requirement 6.1 (Move Message):** As a user, I want to move messages between folders.
* **Requirement 6.2 (Advanced Search Filters):** As a user, I want to search my emails using
  advanced filters.
* **Requirement 6.3 (Junk/Spam Management):** As a user, I want basic controls to mark emails as
  junk/spam.
* **Requirement 6.4 (Folder Management):** As a user, I want to create, rename, and delete custom
  mail folders.
* **Requirement 6.5 (Unsubscribe Suggestions):** As a user, I want the app to suggest an easy
  unsubscribe option.
* **NEW Requirement 6.6 (Handling Multiple Credential Types - Future):** As a user, I want the app
  to securely manage different types of credentials if other protocols beyond Google OAuth & MSAL
  are supported in the future (e.g., passkeys for other services, IMAP username/password).

---
**EPIC 7: Settings & Configuration** (Lower Priority for initial MVP)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings.

---
**EPIC 8: Integrations** (Lower Priority)

* **Requirement 8.1 (Calendar Integration):** As a user, I want to quickly create calendar events
  from email content.
* **Requirement 8.2 (Task Integration):** As a user, I want to create tasks based on email content.
* **Requirement 8.3 (Contact Integration):** As a user, I want to easily save email senders as
  device contacts.

---
**EPIC 9: Google Account Integration** (In Progress - Strategy Updated to AppAuth)

* **Requirement 9.1 (Google Authentication & API Authorization):** As a user, I want to securely
  sign in to my Google account (initial auth via `CredentialManager`) and authorize the app to
  access Gmail APIs (via AppAuth flow, handling consent for necessary permissions, and obtaining
  access/refresh tokens).
* **Requirement 9.2 (View Google Folders/Labels):** As a user, I want to see the list of
  folders/labels for my Google account.
* **Requirement 9.3 (View Google Message List):** As a user, I want to see the list of emails within
  a selected Google folder/label.
* **Requirement 9.4 (View Single Google Message):** As a user, I want to view the full content of an
  email from my Google account.
* *(Mirroring other Epics for Google)* Basic Actions, Attachments, Composing/Sending, etc., for the
  Google account, using tokens obtained via AppAuth.

---

### 2.4. Architectural Vision & Iterative Implementation (Revised May 11, 2025)

* **Iterative Implementation:** The project follows an iterative approach, focusing on delivering
  core value incrementally.
* **Core Architectural Refinements:**
    * `ErrorMapperService` Interface in `:core-data`.
    * `MailApiService` Abstraction in `:core-data`: Defines common mail operations (`getFolders`,
      `getMessages`, etc.) to be implemented by `GraphApiHelper` (for Microsoft) and
      `GmailApiHelper` (for Google). Repositories in `:data` will use this abstraction.
* **Authentication Layer:**
    * **Microsoft:** Uses `MicrosoftAuthManager` (MSAL) in `:backend-microsoft`. Token storage and
      management are handled by MSAL's internal mechanisms.
    * **Google:**
        * **Initial Sign-In (ID Token):** `GoogleAuthManager` in `:backend-google` uses
          `androidx.credentials.CredentialManager` with `GetSignInWithGoogleOption` (configured with
          Web Client ID). This step primarily identifies the user.
        * **API Authorization (Access/Refresh Tokens):** The **AppAuth for Android library** flow
          will be used. This is likely orchestrated by an `AppAuthHelperService` (or similar) in
          `:backend-google`. It will use the **Android Client ID** and manage the authorization code
          grant with PKCE. AppAuth handles launching the consent UI via Custom Tabs.
        * **Token Storage:** A `GoogleTokenPersistenceService` (in `:backend-google` or `:data`)
          will handle encrypting (via a `SecureEncryptionService` using Android Keystore) and
          storing AppAuth-obtained Google tokens into `AccountManager`.
* **Consent Handling for Google:** AppAuth's standard flow using Custom Tabs will present Google's
  consent screen. The `DefaultAccountRepository` will coordinate the overall flow, from initial
  `CredentialManager` sign-in to triggering the AppAuth flow and persisting tokens. The previous
  `GoogleAccountCapability` for `IntentSender` is superseded by AppAuth's direct handling of the
  consent intent.

## 3. Development Roadmap Overview (Revised May 11, 2025)

The immediate and primary development focus is the **Google Account Integration (EPIC 9)** as
detailed in the separate `GOOGLE.MD` document. This involves implementing the AppAuth strategy for
API authorization, token management, and persistence.

Once the Google MVP functionality (sign-in, view folders/messages) is stable, development will
proceed based on the prioritized Epics outlined in Section 2.3. The general order will be:

1. Complete Google Integration (as per `GOOGLE.MD`).
2. Address remaining high-priority items in EPIC 1 (Core Mail Viewing) and EPIC 2 (Basic Mail
   Actions) for both providers.
3. Continue with EPIC 3 (Composing & Sending) and EPIC 4 (Attachments).
4. Iteratively implement features from EPIC 5 (Account & App Foundation - e.g., Background Sync,
   Notifications), EPIC 6 (Advanced Mail Organization), and other lower-priority epics.

Specific task breakdowns for epics beyond the current Google integration will be detailed in
separate implementation plans or task management systems as they are approached.

## 4. Test Strategy

*(No significant changes from previous version, but tests for Google will now need to cover
CredentialManager for ID token, AppAuth flows for API tokens, AccountManager token storage, and Ktor
refresh logic.)*

### 4.1. Goals
* Verify correctness of individual components.
* Prevent regressions.
* Facilitate refactoring.
* Validate interactions between components.
* Improve code design towards testability.

### 4.2. Scope (Phase 1: Unit & Integration on JVM)

* **In Scope:** Unit tests and JVM-based integration tests.
* **Out of Scope (For Now):** UI/E2E tests, performance, security, usability testing.

### 4.3. Test Levels & Techniques

* **Unit Tests:** MockK, `kotlinx-coroutines-test`, Turbine, JUnit. Focus on ViewModels,
  Repositories, Mappers, API Helpers (`GmailApiHelper`, `GraphApiHelper`), Authentication
  components (`GoogleAuthManager`, `AppAuthHelperService`), Token Persistence services (
  `GoogleTokenPersistenceService`).
* **Integration Tests (JVM):** Verify interaction between collaborating components (e.g.,
  `DefaultAccountRepository` with `GoogleAuthManager` and `AppAuthHelperService`).

### 4.4. Tooling & Libraries

* JUnit 4, MockK, `kotlinx-coroutines-test`, Turbine.

### 4.5. Requirements & Best Practices

* AAA structure, isolation, readability, speed, reliability, good coverage.

### 4.6. Future Testing Considerations

* UI/E2E Tests using Jetpack Compose testing APIs and Hilt, especially for the Custom Tab
  interactions of AppAuth.
* CI Integration.

## 5. Developer & AI Guidance

### 5.1. Key Architectural Patterns & Conventions
* **Modular Architecture:** As described in section 2.2.
* **MVVM with Jetpack Compose UI.**
* **Repository Pattern.**
* **Dependency Injection:** Hilt.
* **Asynchronous Operations:** Kotlin Coroutines and Flow.
* **Networking:** Ktor with OkHttp engine.
* **Error Handling:** `ErrorMapperService` from `:core-data`.
* **Authentication:**
    * Microsoft: `MicrosoftAuthManager` (MSAL) in `:backend-microsoft`.
    * Google:
        * Initial Sign-In (ID Token): `GoogleAuthManager` in `:backend-google` (using
          `CredentialManager` with Web Client ID).
        * API Authorization & Tokens (Access/Refresh): AppAuth for Android (via
          `AppAuthHelperService` in `:backend-google`, using Android Client ID).
        * Token Storage: `GoogleTokenPersistenceService` using `AccountManager` (with Keystore
          encryption via `SecureEncryptionService`).
* **UI:** Material 3.

### 5.2. Build and Run Commands
* **Build app:** `./gradlew build`
* **Install and run (debug):** `./gradlew installDebug`
* **Run all unit tests:** `./gradlew test`
* **Clean build:** `./gradlew clean build`

### 5.3. Current Development Focus

Completing Google Account Integration (Epic 9) using `CredentialManager` for initial sign-in and *
*AppAuth for Android** for API authorization and token management. Implementing secure token storage
in `AccountManager`. Detailed steps are in `GOOGLE.MD`.

---

This consolidated document aims to be a central reference. It should be updated as the project evolves.

