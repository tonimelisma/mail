# Melisma Mail - Comprehensive Project Guide

**Version:** 1.5 (Consolidated and Updated on May 10, 2025)

## 1. Project Overview

Melisma Mail is an Android email client aiming to provide a clean, native user experience similar to stock Pixel apps, using Material 3. It's being built as a potential replacement for default mail apps, starting with support for Microsoft Outlook accounts, with plans to support Google (Gmail) and potentially other backends in the future.

The application follows a modular architecture utilizing Hilt for dependency injection and Jetpack Compose with ViewModels (MVVM pattern) for the UI layer. Key technologies include Kotlin, Coroutines, Flow, and Ktor for networking.

### 1.1. Current Status (As of May 10, 2025)

* **Microsoft Account Support:**
    * Users can securely sign in and out using Microsoft accounts (MSAL).
    * Users can fetch and view a list of their mail folders after signing in.
  * Core refactoring is largely complete, including Ktor networking in `:backend-microsoft`,
    Flow-based `MicrosoftTokenProvider`, and the `:data` module with default repositories.
  * `ErrorMapperService` interface defined in `:core-data`. `MicrosoftErrorMapper` implemented.
* **Google Account Support (Architectural Refactor for Consent Handling Implemented):**
    * Foundational components in the `:backend-google` module are significantly updated:
        * `GoogleAuthManager` now uses `androidx.credentials.CredentialManager` for initial
          sign-in (ID Token retrieval) and
          `com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient()` for
          subsequent OAuth2 scope authorization and Access Token retrieval (including providing
          `IntentSender` for consent).
        * `GoogleErrorMapper` created and Hilt modules updated for DI.
        * `GmailApiHelper` (for Gmail API interaction) and `GmailModels` are in place.
    * **Architectural Enhancement for Google Consent:** The system now uses an "Enhanced Delegation
      Pattern with Capability Interfaces." The common `AccountRepository` interface is generic. A
      `GoogleAccountCapability` interface defines Google-specific operations like
      `googleConsentIntent` Flow and `finalizeGoogleScopeConsent`. The `DefaultAccountRepository`
      implements both, and the `MainViewModel` checks for the capability to handle Google's consent
      UI flow.
    * **Key Pending Work for Google Integration:**
        1. **Google Cloud & Project Setup Verification:** Ensure the Web Application OAuth 2.0
           Client ID from Google Cloud Console is correctly configured and accessible to
           `GoogleAuthManager`. Verify API enablement (Gmail API) and consent screen details.
        2. **Data Layer Integration (Post-Capability Refactor):** The `DefaultAccountRepository`,
           `DefaultFolderRepository`, and `DefaultMessageRepository` in the `:data` module need to
           fully utilize `GoogleAuthManager` and `GmailApiHelper` (or its `MailApiService`
           abstraction) for all Google operations, ensuring state (like accounts list) is correctly
           updated.
        3. **UI/UX Adjustments:** UI elements need updates to support adding Google accounts and
           displaying Gmail labels/messages correctly, leveraging the new consent handling
           mechanism.

### 1.2. Setup & Build

1.  **Clone the repository.**
2.  **Open in Android Studio:** Use the latest stable version.
3.  **Sync Gradle:** Allow dependencies to sync. Ensure necessary Android SDK versions are installed.
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

### 2.2. Modules (Revised May 10, 2025)

* **:app**: Main application module with UI (Jetpack Compose), ViewModels, and the `MailApplication`
  class. Handles UI-specific logic like launching `IntentSender` for Google consent.
* **:core-data**: Defines core data interfaces (e.g., generic `AccountRepository`,
  `FolderRepository`, `MessageRepository`), core domain models (Account, MailFolder, Message, etc.),
  DI helpers, shared service interfaces like `ErrorMapperService`, the proposed `MailApiService`,
  and **provider-specific capability interfaces** like `GoogleAccountCapability`.
* **:data**: Implements repository interfaces from `:core-data` (e.g., `DefaultAccountRepository`,
  `DefaultFolderRepository`, `DefaultMessageRepository`). `DefaultAccountRepository` implements
  common interfaces and any relevant provider-specific capability interfaces (e.g.,
  `GoogleAccountCapability`), orchestrating calls to backend-specific modules. This layer
  coordinates with backend-specific modules, ideally through the `MailApiService` abstraction for
  data fetching.
* **:backend-microsoft**: Handles Microsoft/Outlook specific logic: MSAL authentication (
  `MicrosoftAuthManager`, `MicrosoftTokenProvider`), Microsoft Graph API interaction (
  `GraphApiHelper` - to implement `MailApiService`), Ktor client setup for Graph, and
  Microsoft-specific error mapping (`MicrosoftErrorMapper`).
* **:backend-google**: Handles Google/Gmail specific logic:
    * `GoogleAuthManager`: Manages Google Sign-In (ID tokens via Credential Manager) and OAuth2
      access tokens (via `Identity.getAuthorizationClient`), including providing `IntentSender` for
      consent.
    * `GmailApiHelper`: Handles Gmail API interaction (to implement `MailApiService`).
    * Ktor client setup for Gmail.
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
* **Authentication & Core:**
    * **Requirement 5.1 (Authentication - Functional):** As a user, I want to securely sign in and
      out of my supported email accounts (Microsoft Outlook done, Google Gmail in progress with
      modern auth and refined consent handling).
    * **Requirement 5.2 (Basic Error Handling - Functional):** As a user, I want to see clear
      messages if actions fail.
* **User Experience:**
    * **Requirement 5.3 (Visual Polish - Non-Functional):** The app should adhere to Material 3
      guidelines.
    * **Requirement 5.4 (Performance - Non-Functional):** UI interactions should feel smooth and
      responsive.
    * **Requirement 5.5 (Theming - UI/UX):** As a user, I want the app to support Light and Dark
      themes.
* **Offline & Sync:**
    * **Requirement 5.6 (Data Caching - Non-Functional):** The app should cache data locally.
    * **Requirement 5.7 (Background Sync - Functional):** As a user, I want the app to periodically
      check for new emails.
* **Notifications:**
    * **Requirement 5.8 (Push Notifications - Functional):** As a user, I want to receive
      notifications for new emails.
    * **Requirement 5.9 (Actionable Notifications - Functional):** As a user, I want to perform
      quick actions from an email notification.
* **Analytics & Logging:**
    * **Requirement 5.10 (Local Error Logging - Non-Functional):** The app must log critical errors locally.
    * **Requirement 5.11 (Log Export - Functional):** As a user/developer, I want a way to export local error logs.
    * **Requirement 5.12 (Usage Metrics - Non-Functional):** The app should gather anonymized basic usage metrics locally.

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
**EPIC 9: Google Account Integration** (In Progress - Core Auth Refactored, Consent Handling
Architected)

* **Requirement 9.1 (Google Authentication & Consent):** As a user, I want to securely sign in to my
  Google account, including handling the OAuth consent flow for necessary permissions (Architectural
  pattern for consent implemented; full flow integration pending).
* **Requirement 9.2 (View Google Folders/Labels):** As a user, I want to see the list of
  folders/labels for my Google account.
* **Requirement 9.3 (View Google Message List):** As a user, I want to see the list of emails within
  a selected Google folder/label.
* **Requirement 9.4 (View Single Google Message):** As a user, I want to view the full content of an
  email from my Google account.
* *(Mirroring other Epics for Google)* Basic Actions, Attachments, Composing/Sending, etc., for the
  Google account.

---

### 2.4. Architectural Vision & Iterative Implementation (Revised May 10, 2025)

* **Iterative Implementation:** The project follows an iterative approach, focusing on delivering
  core value incrementally.
* **Core Architectural Refinements:**
    * **`ErrorMapperService` Interface in `:core-data`:** Centralized error mapping.
    * **`MailApiService` Abstraction in `:core-data`:** Defines common mail operations (
      `getFolders`, `getMessages`, etc.) to be implemented by `GraphApiHelper` and `GmailApiHelper`.
      Repositories in `:data` will use this abstraction. (Pending Implementation)
    * **Google Consent Handling (Enhanced Delegation Pattern):**
        * The common `AccountRepository` interface (in `:core-data`) is generic and does not include
          provider-specific UI logic like `googleConsentIntent`.
        * A `GoogleAccountCapability` interface (in `:core-data`) defines Google-specific
          operations: `val googleConsentIntent: Flow<IntentSender?>` and
          `suspend fun finalizeGoogleScopeConsent(...)`.
        * `DefaultAccountRepository` (in `:data`) implements both `AccountRepository` and
          `GoogleAccountCapability`. It orchestrates calls to `GoogleAuthManager` (which provides
          the `IntentSender`) and emits the `IntentSender` via the flow defined in
          `GoogleAccountCapability`.
        * `MainViewModel` (in `:app`) is injected with `AccountRepository`. It checks if the
          instance also implements `GoogleAccountCapability` to access the `googleConsentIntent`
          flow and the `finalizeGoogleScopeConsent` method.
        * `MainActivity` (in `:app`) observes the `IntentSender` flow from the `MainViewModel` and
          launches the consent UI.
* **Authentication Layer:**
    * Microsoft: Uses `MicrosoftAuthManager` and `MicrosoftTokenProvider`.
    * Google: Uses the revised `GoogleAuthManager` (Credential Manager for ID Token,
      `Identity.getAuthorizationClient` for Access Token and `IntentSender` for consent).
* **Feature Integration Notes:** Similar to previous, but Google integration will now follow the
  refined consent handling architecture.

## 3. Implementation Plan & Next Steps (Revised May 10, 2025)

The immediate focus is on completing the Google Account integration into the data and UI layers,
leveraging the refactored authentication and the newly implemented consent handling architecture.

### 3.1. Priority Task Groups:

**Group A: Foundational Google Enablement & Core Architectural Refinements**

1. **Verify Google Cloud & Project Setup (Blocking for Google Functionality):**
    * **Action:** Confirm Google Cloud Console settings (Gmail API enabled, OAuth Client ID for Web
      Application is correct and used by `GoogleAuthManager`, Consent Screen configured).
2. **Implement `MailApiService` Abstraction (Next Key Architectural Step):**
    * **Action:** Define `MailApiService` interface in `:core-data`.
    * **Action:** Modify `GraphApiHelper` and `GmailApiHelper` to implement `MailApiService` and
      update Hilt provisions.

**Group B: Integrate Google into Data Layer (Depends on Group A)**

3. **Enable `:backend-google` Dependency in `:data` (Verify).**
4. **Update `DefaultAccountRepository.kt` for Google:**
    * **Action:** Ensure `GoogleAuthManager` is correctly injected.
    * **Action:** Fully implement the `"GOOGLE"` provider type branches in `addAccount` (using
      `GoogleAuthManager` and emitting `IntentSender` via `GoogleAccountCapability`'s flow) and
      `removeAccount`.
    * **Action:** Ensure `finalizeGoogleScopeConsent` (from `GoogleAccountCapability`) correctly
      interacts with `GoogleAuthManager` and updates account state.
    * **Action:** Update `accounts` StateFlow to correctly combine/observe accounts from all
      providers, reflecting successful Google account addition post-consent.
5. **Update `DefaultFolderRepository.kt` and `DefaultMessageRepository.kt` for
   Google & `MailApiService`:**
    * **Action:** Inject `Map<String, @JvmSuppressWildcards MailApiService>`.
    * **Action:** Use the correct `MailApiService` based on `account.providerType`.

**Group C: UI/UX and Final Testing (Depends on Group B)**

6. **UI/UX Adjustments for Google:**
    * **Action:** Modify `SettingsScreen` for "Add Google Account," triggering
      `MainViewModel.addGoogleAccount()`.
    * **Action:** Ensure `MainActivity` correctly observes
      `MainViewModel.googleConsentIntentForUi` (or its equivalent) and launches the `IntentSender`.
      Ensure the callback correctly calls `MainViewModel.finalizeGoogleScopeConsent()`.
    * **Action:** Update `MailDrawerContent`, `MessageListContent`, etc., for Gmail data.
7. **Thorough End-to-End Testing:**
    * **Action:** Test Google sign-in, consent flow (initial and for new scopes if applicable
      later), sign-out, folder/message fetching.

### 3.2. Prioritization Judgement Call:

1. **Immediately:** Verify **Item 1** (Google Cloud Setup). Complete **Item 2** (`MailApiService`).
2. **Next:** Proceed with **Group B** (Data Layer for Google).
3. **Finally:** Complete **Group C** (UI/UX for Google and Testing).

## 4. Test Strategy

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
  Repositories (including testing `DefaultAccountRepository`'s implementation of
  `GoogleAccountCapability`), Mappers, API Helpers, Auth Managers.
* **Integration Tests (JVM):** Verify interaction between collaborating components.

### 4.4. Tooling & Libraries

* JUnit 4, MockK, `kotlinx-coroutines-test`, Turbine.

### 4.5. Requirements & Best Practices

* AAA structure, isolation, readability, speed, reliability, good coverage.

### 4.6. Future Testing Considerations

* UI/E2E Tests using Jetpack Compose testing APIs and Hilt.
* CI Integration.

## 5. Developer & AI Guidance

### 5.1. Key Architectural Patterns & Conventions
* **Modular Architecture:** As described in section 2.2.
* **MVVM with Jetpack Compose UI.**
* **Repository Pattern:**
    * Generic interfaces (e.g., `AccountRepository`) in `:core-data`.
    * Provider-specific capability interfaces (e.g., `GoogleAccountCapability`) in `:core-data`.
    * Implementations (e.g., `DefaultAccountRepository` implementing both common and capability
      interfaces) in `:data`.
* **Dependency Injection:** Hilt.
* **Asynchronous Operations:** Kotlin Coroutines and Flow.
* **Networking:** Ktor with OkHttp engine.
* **Error Handling:** `ErrorMapperService` from `:core-data`.
* **Authentication:**
    * Microsoft: `MicrosoftAuthManager` in `:backend-microsoft`.
    * Google: `GoogleAuthManager` in `:backend-google` (handles ID token, Access Token, and
      `IntentSender` for consent). `DefaultAccountRepository` uses this and exposes consent flow via
      `GoogleAccountCapability`.
* **UI:** Material 3.

### 5.2. Build and Run Commands
* **Build app:** `./gradlew build`
* **Install and run (debug):** `./gradlew installDebug`
* **Run all unit tests:** `./gradlew test`
* **Clean build:** `./gradlew clean build`

### 5.3. Current Development Focus

Completing Google Account Integration (Epic 9) following the "Enhanced Delegation Pattern with
Capability Interfaces" for consent, implementing the `MailApiService` abstraction, and then
integrating these into the data and UI layers.

---

This consolidated document aims to be a central reference. It should be updated as the project evolves.

