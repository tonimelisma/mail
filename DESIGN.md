# Melisma Mail - Comprehensive Project Guide

**Version:** 1.2 (Consolidated and Updated on May 8, 2025)

## 1. Project Overview

Melisma Mail is an Android email client aiming to provide a clean, native user experience similar to stock Pixel apps, using Material 3. It's being built as a potential replacement for default mail apps, starting with support for Microsoft Outlook accounts, with plans to support Google (Gmail) and potentially other backends in the future.

The application follows a modular architecture utilizing Hilt for dependency injection and Jetpack Compose with ViewModels (MVVM pattern) for the UI layer. Key technologies include Kotlin, Coroutines, Flow, and Ktor for networking.

### 1.1. Current Status (As of May 8, 2025)

* **Microsoft Account Support:**
    * Users can securely sign in and out using Microsoft accounts (MSAL).
    * Users can fetch and view a list of their mail folders after signing in.
    * Core refactoring (Phase 1 of `GOOGLE.md`) is largely complete, including Ktor networking in `:backend-microsoft`, Flow-based `MicrosoftTokenProvider`, and the `:data` module with default repositories.
    * `ErrorMapperService` interface defined (to be homed in `:core-data`).
* **Google Account Support (In Progress):**
    * Foundational components in the `:backend-google` module are implemented (`GoogleAuthManager`, `GoogleTokenProvider`, `GmailApiHelper`, `GmailModels`).
    * **Key Pending Work for Google Integration:**
        1.  **Google Cloud & Project Setup:** Requires adding `app/google-services.json` and activating the Google Services Gradle plugin in `app/build.gradle.kts`. External Google Cloud Console configuration (API enablement, OAuth client, consent screen) must also be verified.
        2.  **Data Layer Integration:** The `:backend-google` module needs to be enabled as a dependency in `data/build.gradle.kts`. The `DefaultAccountRepository`, `DefaultFolderRepository`, and `DefaultMessageRepository` in the `:data` module need to be updated to inject and use Google-specific components (`GoogleAuthManager`, `GoogleTokenProvider`, `GmailApiHelper` or its abstraction) and handle the "GOOGLE" provider type.
        3.  **Error Handling Refinement:** The `ErrorMapperService` implementation needs to be updated/unified to specifically handle Google-related exceptions.
        4.  **UI/UX Adjustments:** UI elements need updates to support adding Google accounts and displaying Gmail labels/messages correctly.

### 1.2. Setup & Build

1.  **Clone the repository.**
2.  **Open in Android Studio:** Use the latest stable version.
3.  **Sync Gradle:** Allow dependencies to sync. Ensure necessary Android SDK versions are installed.
4.  **Permissions:** The app requires `INTERNET` permission (declared in `AndroidManifest.xml`).
5.  **For Google Integration (Once `google-services.json` is added):** Ensure the `com.google.gms.google-services` plugin is active in `app/build.gradle.kts`.
6.  **Build/Run:**
    * Build: `./gradlew build`
    * Install and run on connected device/emulator: `./gradlew installDebug`

## 2. Requirements & Architecture

*(Derived from DESIGN_DOC.md, Version 0.4 and subsequent architectural decisions)*

### 2.1. Core Principles

* **UI Layer:** Jetpack Compose, ViewModels (MVVM), StateFlow.
* **Data Layer:** Repository pattern, Coroutines/Flow.
* **Dependency Injection:** Hilt.
* **Modularity:** See section 2.2.

### 2.2. Modules (Revised May 8, 2025)

* **:app**: Main application module with UI (Jetpack Compose), ViewModels, and the `MailApplication` class.
* **:core-data**: Defines core data interfaces (repositories, data sources like `TokenProvider`), core domain models (Account, MailFolder, Message, etc.), DI helpers like `AuthConfigProvider`, and **shared service interfaces** like `ErrorMapperService` and the proposed `MailApiService`.
* **:data**: Implements repository interfaces from `:core-data` (`DefaultAccountRepository`, `DefaultFolderRepository`, `DefaultMessageRepository`). This layer coordinates with backend-specific modules, ideally through the `MailApiService` abstraction.
* **:backend-microsoft**: Handles Microsoft/Outlook specific logic: MSAL authentication (`MicrosoftAuthManager`, `MicrosoftTokenProvider`), Microsoft Graph API interaction (`GraphApiHelper` - to implement `MailApiService`), Ktor client setup for Graph, and Microsoft-specific error mapping (current `MicrosoftErrorMapper` - to be reviewed/unified).
* **:backend-google**: Handles Google/Gmail specific logic: Google Sign-In (`GoogleAuthManager`), token provision (`GoogleTokenProvider`), Gmail API interaction (`GmailApiHelper` - to implement `MailApiService`), Ktor client setup for Gmail, and (eventually) Google-specific error mapping.

*(Note: `:core-common` module is planned to be deprecated by moving its contents, like `ErrorMapperService` interface, to `:core-data`.)*

### 2.3. Prioritized Requirements Backlog (Epics)

The application development follows a prioritized backlog of features grouped into Epics.

---

**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a selected folder (e.g., Inbox) so that I can see my latest messages.
    * Display sender, subject, snippet/preview, timestamp, unread status.
    * Support pulling down to refresh the message list.
    * Handle loading states (initial load, refresh).
    * Basic pagination or infinite scrolling for loading older messages.
    * Display sender avatars/pictures (lower priority).
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email in the list to view its full content so that I can read the message.
    * Display full headers (From, To, Cc, Subject, Date).
    * Render message body (handle HTML and plain text).
    * Robust rendering of complex HTML emails (Rich Text Formatting - Viewer).
    * Mark message as read upon opening.
    * Handle loading state for message content.
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail folders (Inbox, Sent, Drafts, custom folders) listed previously, so I can view messages in specific folders.
    * UI mechanism to switch folders.
    * Display the selected folder's message list (using Req 1.1).
* **Requirement 1.4 (Unified Inbox - UI/UX):** As a user, I want an option to view emails from all my accounts in a single combined inbox view, so I can see all new mail easily. (High Priority, may need backend support later)
* **Requirement 1.5 (Conversation View - UI/UX):** As a user, I want emails belonging to the same thread to be grouped into a single conversation view, so I can follow the discussion easily. (Medium-High Priority)

---

**EPIC 2: Basic Mail Actions** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread from the message list or message view, so I can manage my inbox status.
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages from the message list or message view, so I can remove unwanted emails.
    * Move message to the 'Deleted Items' folder.
    * Provide undo option.
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages (if supported by the account/backend), so I can remove them from the inbox without deleting them.
    * Move message to the 'Archive' folder.
    * Provide undo option.
* **Requirement 2.4 (Customizable Swipe Actions - UI/UX):** As a user, I want to configure actions (e.g., delete, archive, mark read, move) for left/right swipes on the message list, so I can quickly process emails. (Medium Priority)

---

**EPIC 3: Composing & Sending** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to initiate composing a new email so that I can send a message.
    * Provide fields for To, Cc, Bcc, Subject, Body.
    * Basic rich text editing capabilities/toolbar for the body (bold, italics, lists, etc.).
    * Easy way to attach files using a system picker (integrates with Epic 4). (Medium-High Priority)
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email, so the recipient(s) receive it.
    * Handle sending via the backend API.
    * Place sent message in the 'Sent Items' folder.
    * Handle errors during sending.
    * Option to undo send for a short period after sending. (Medium Priority)
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a received message, so I can respond or share the email.
    * Pre-populate fields (To, Subject, quoted body) appropriately.
    * Allow editing before sending.
* **Requirement 3.4 (Signature Management):** As a user, I want to define and manage email signatures, potentially assigning different signatures per account. (Medium-Low Priority)

---

**EPIC 4: Attachments** (Higher Priority)

* **Requirement 4.1 (View Attachments):** As a user, I want to see a list of attachments within a received email.
* **Requirement 4.2 (Preview Attachments):** As a user, I want to preview common attachment types (Images, PDFs, simple Text/Document files) directly within the app.
* **Requirement 4.3 (Save Attachments):** As a user, I want to save attachments from an email to my device's storage.
* **Requirement 4.4 (Share Attachments):** As a user, I want to share attachments directly from an email using the system share sheet.
* **Requirement 4.5 (Attach Files):** As a user, I want to easily attach files from my device when composing an email (see also Req 3.1).

---

**EPIC 5: Account & App Foundation** (Underpins everything)

* **Authentication & Core:**
    * **Requirement 5.1 (Authentication - Functional):** As a user, I want to securely sign in and out of my supported email accounts (Microsoft Outlook done, Google Gmail in progress).
    * **Requirement 5.2 (Basic Error Handling - Functional):** As a user, I want to see clear messages if actions fail (e.g., network error, API error), so I understand what went wrong. (ErrorMapperService interface to be in `:core-data`, implementation to be unified).
* **User Experience:**
    * **Requirement 5.3 (Visual Polish - Non-Functional):** The app should adhere to Material 3 guidelines and visually resemble stock Pixel apps for a native Android feel.
    * **Requirement 5.4 (Performance - Non-Functional):** UI interactions should feel smooth and responsive. Network operations should show loading indicators.
    * **Requirement 5.5 (Theming - UI/UX):** As a user, I want the app to support Light and Dark themes, ideally following the system setting. (Medium Priority)
* **Offline & Sync:**
    * **Requirement 5.6 (Data Caching - Non-Functional):** The app should cache folders, message lists, and potentially basic message content/metadata locally for faster access and basic offline viewing.
    * **Requirement 5.7 (Background Sync - Functional):** As a user, I want the app to periodically check for new emails in the background.
* **Notifications:**
    * **Requirement 5.8 (Push Notifications - Functional):** As a user, I want to receive notifications for new emails. (Medium Priority - may require more than basic background sync)
    * **Requirement 5.9 (Actionable Notifications - Functional):** As a user, I want to perform quick actions (e.g., Archive, Delete, Mark Read) directly from an email notification. (Medium-High Priority)
* **Analytics & Logging:**
    * **Requirement 5.10 (Local Error Logging - Non-Functional):** The app must log critical errors locally.
    * **Requirement 5.11 (Log Export - Functional):** As a user/developer, I want a way to export local error logs.
    * **Requirement 5.12 (Usage Metrics - Non-Functional):** The app should gather anonymized basic usage metrics locally.

---

**EPIC 6: Advanced Mail Organization** (Medium Priority)

* **Requirement 6.1 (Move Message):** As a user, I want to move messages between folders so that I can organize my email.
* **Requirement 6.2 (Advanced Search Filters):** As a user, I want to search my emails using advanced filters (keywords, sender, subject, date range, attachment presence), so I can find specific messages quickly.
* **Requirement 6.3 (Junk/Spam Management):** As a user, I want basic controls to mark emails as junk/spam and manage the junk folder. (Medium Priority)
* **Requirement 6.4 (Folder Management):** As a user, I want to create, rename, and delete custom mail folders. (Medium-Low Priority)
* **Requirement 6.5 (Unsubscribe Suggestions):** As a user, I want the app to suggest an easy unsubscribe option for detected mailing lists. (Lower Priority)

---

**EPIC 7: Settings & Configuration** (Lower Priority for initial MVP)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (account view, sign out, log export).
    * (Later) Add setting for background sync frequency/notifications.
    * (Later) Add configuration for swipe actions (Req 2.4).
    * (Later) Add configuration for signatures (Req 3.4).

---

**EPIC 8: Integrations** (Lower Priority)

* **Requirement 8.1 (Calendar Integration):** As a user, I want to quickly create calendar events from email content (e.g., detected dates, meeting invitations).
* **Requirement 8.2 (Task Integration):** As a user, I want to create tasks in relevant apps (e.g., To Do, Google Tasks) based on email content.
* **Requirement 8.3 (Contact Integration):** As a user, I want to easily save email senders as device contacts or view existing contact details.

---

**EPIC 9: Google Account Integration** (In Progress - see Current Status)

* **Requirement 9.1 (Google Authentication):** As a user, I want to securely sign in and out of my Google account.
* **Requirement 9.2 (View Google Folders/Labels):** As a user, I want to see the list of folders/labels for my signed-in Google account.
* **Requirement 9.3 (View Google Message List):** As a user, I want to see the list of emails within a selected Google folder/label.
* **Requirement 9.4 (View Single Google Message):** As a user, I want to view the full content of an email from my Google account.
* *(Mirroring other Epics for Google)* Basic Actions, Attachments, Composing/Sending, etc., for the Google account.

---

### 2.4. Architectural Vision & Iterative Implementation (Revised May 8, 2025)

* **Iterative Implementation:** The project follows an iterative approach, focusing on delivering core value incrementally based on the prioritized Epics.
* **Core Architectural Refinements (Planned/In Progress):**
    * **`ErrorMapperService` Interface in `:core-data`:** The `ErrorMapperService` interface will reside in `net.melisma.core_data.errors`. The implementation (e.g., a unified `DefaultErrorMapper` handling both Microsoft and Google exceptions) will likely live in `:backend-microsoft` (renamed) or `:data` and be provided via Hilt. This consolidates error mapping contracts within the core data definition layer. The `:core-common` module will likely be deprecated.
    * **`MailApiService` Abstraction in `:core-data`:** A new interface, `MailApiService`, will be defined in `:core-data` (e.g., `net.melisma.core_data.datasource.MailApiService`). This interface will define common mail operations like `getFolders`, `getMessages`, etc.
        * `GraphApiHelper` (in `:backend-microsoft`) and `GmailApiHelper` (in `:backend-google`) will implement this `MailApiService` interface.
        * Repositories in the `:data` module (e.g., `DefaultFolderRepository`, `DefaultMessageRepository`) will inject a `Map<String, @JvmSuppressWildcards MailApiService>` (keyed by provider type) using Hilt multibindings. This will allow repositories to call `mailApiServices[account.providerType]?.getFolders(...)`, making them cleaner and more agnostic to specific API helper implementations.
* **Authentication Layer with Kotlin Flows:** This was implemented for the Microsoft backend (`MicrosoftAuthManager` and `MicrosoftTokenProvider`) using `kotlinx.coroutines.flow.callbackFlow`. A similar Flow-based approach is used for Google authentication.
* **Feature Integration Notes (from DESIGN_DOC.md):**
    * **Attachments (Epic 4):** Will require file handling logic, permissions (if saving), potentially content providers/intents for previewing/sharing. Integrates into message view (`:app`) and repositories (via `MailApiService`).
    * **Notifications (Req 5.8, 5.9):** Requires `NotificationManager`, potentially Foreground Services or `WorkManager`. Actionable notifications need PendingIntents handled by BroadcastReceivers or Services, interacting with repositories.
    * **UI/UX Enhancements (Unified Inbox, Conversation View, Swipes, Theming):** Implemented primarily within `:app`, potentially requiring data layer support.
    * **Advanced Organization (Epic 6):** Extends repositories and potentially database queries. UI changes in `:app`.
    * **Integrations (Epic 8):** May require Android Intents or direct API interaction.

## 3. Implementation Plan & Next Steps (Revised May 8, 2025)

The immediate focus is on completing the Google Account integration, alongside key architectural refinements that will facilitate this and future development.

### 3.1. Priority Task Groups:

**Group A: Foundational Google Enablement & Core Architectural Refinements (Do these first, can be parallelized where possible)**

1.  **Finalize Google Cloud & Project Setup (Blocking for Google Functionality):**
    * Verify Google Cloud Console settings (APIs, OAuth Client ID, Consent Screen).
    * **Action:** Add `google-services.json` to the `app/` directory.
    * **Action:** Activate the `com.google.gms.google-services` Gradle plugin in `app/build.gradle.kts`.
2.  **Refactor Error Handling & Module Structure:**
    * **Action:** Move `ErrorMapperService` interface definition to `net.melisma.core_data.errors`.
    * **Action:** Update `MicrosoftErrorMapper` (consider renaming to `DefaultErrorMapper` and keeping in `:backend-microsoft` or moving to `:data`) to implement the moved interface and **add specific handling for Google exceptions** (`ApiException`, `GoogleAuthException`). Update Hilt bindings if its location changes.
    * **Action:** If `:core-common` is now empty, remove it from the project and update `settings.gradle.kts` and any dependent `build.gradle.kts` files.
3.  **Implement `MailApiService` Abstraction:**
    * **Action:** Define `MailApiService` interface in `:core-data` (e.g., `net.melisma.core_data.datasource.MailApiService`) with methods for `getFolders`, `getMessages`, etc.
    * **Action:** Modify `GraphApiHelper` in `:backend-microsoft` to implement `MailApiService`. Update its Hilt provision if necessary (e.g., to be part of a multibinding map).
    * **Action:** Modify `GmailApiHelper` in `:backend-google` to implement `MailApiService`. Update its Hilt provision (e.g., to be part of a multibinding map).

**Group B: Integrate Google into Data Layer (Depends on Group A)**

4.  **Enable `:backend-google` Dependency in `:data`:**
    * **Action:** In `data/build.gradle.kts`, uncomment `implementation(project(":backend-google"))`.
5.  **Update `DefaultAccountRepository.kt` for Google:**
    * **Action:** Inject `GoogleAuthManager`.
    * **Action:** Fully implement the `"GOOGLE"` provider type branches in `addAccount` and `removeAccount` using `GoogleAuthManager`.
    * **Action:** Update `accounts` StateFlow to correctly combine/observe accounts from both `MicrosoftAuthManager` and `GoogleAuthManager`.
6.  **Update `DefaultFolderRepository.kt` and `DefaultMessageRepository.kt` for Google & `MailApiService`:**
    * **Action:** Modify constructors to inject `Map<String, @JvmSuppressWildcards TokenProvider>` and `Map<String, @JvmSuppressWildcards MailApiService>`.
    * **Action:** Update internal logic to use the correct `TokenProvider` and `MailApiService` implementation from the maps based on `account.providerType`.

**Group C: UI/UX and Final Testing (Depends on Group B)**

7.  **UI/UX Adjustments for Google:**
    * **Action:** Modify `SettingsScreen` for distinct "Add Google Account".
    * **Action:** Update `MailDrawerContent` to handle Gmail labels.
    * **Action:** Review `MessageListContent` and `MessageListItem` for consistency.
8.  **Thorough End-to-End Testing:**
    * **Action:** Test all features for both Microsoft and Google accounts.

### 3.2. Prioritization Judgement Call:

**It is recommended to tackle Group A tasks first, with a strong emphasis on items 1 (Google Cloud Setup) and 2 (Error Handling & Module Structure) concurrently or in close succession.**

* **Google Cloud Setup (Item 1)** is a fundamental blocker for any Google functionality. Without `google-services.json` and the plugin, Google Sign-In cannot work.
* **Error Handling & Module Structure (Item 2)** and **`MailApiService` Abstraction (Item 3)** are important architectural cleanups. Implementing the `MailApiService` abstraction (Item 3) before or alongside fully wiring up Google logic in the repositories (Group B) will lead to a cleaner and more maintainable `:data` layer. Consolidating `ErrorMapperService` is a straightforward cleanup.

**Therefore, the suggested order is:**

1.  **Immediately:**
    * Address **Item 1** (Google Cloud & Project Setup).
    * Start **Item 2** (Refactor Error Handling & Module Structure - moving `ErrorMapperService` interface, updating its implementation for Google errors, and potentially removing `:core-common`).
    * Start **Item 3** (Implement `MailApiService` Abstraction - define interface, update helpers to implement it).
2.  **Next (once Item 1 is unblocked and Items 2 & 3 are substantially complete):**
    * Proceed with **Group B** (Integrate Google into Data Layer - enabling dependency, updating repositories to use `GoogleAuthManager` and the new `MailApiService` abstraction).
3.  **Finally:**
    * Complete **Group C** (UI/UX Adjustments and Final Testing).

This approach prioritizes unblocking Google functionality while simultaneously making architectural improvements that will benefit the Google integration process itself, particularly in the `:data` layer repositories.

## 4. Test Strategy

*(Derived from TESTING.MD, Version 1.0 - No changes in this section from previous version of this guide)*

### 4.1. Goals

* Verify correctness of individual components.
* Prevent regressions.
* Facilitate refactoring.
* Validate interactions between components.
* Improve code design towards testability.

### 4.2. Scope (Phase 1: Unit & Integration on JVM)

* **In Scope:** Unit tests and JVM-based integration tests in `src/test` of each module.
* **Out of Scope (For Now):** UI/E2E tests (`src/androidTest`), performance, security, usability testing.

### 4.3. Test Levels & Techniques

* **Unit Tests:**
    * **Purpose:** Verify logic of a single class in isolation.
    * **Location:** `src/test/...` within each module.
    * **Techniques:** MockK for mocking, `kotlinx-coroutines-test` for coroutines, Turbine for Flow testing, JUnit assertions, MockK `verify`.
    * **Focus:** Business logic, state transformations, error handling within the unit.
    * **Targets:** ViewModels, Repositories, Mappers, API Helpers.
* **Integration Tests (JVM):**
    * **Purpose:** Verify interaction between collaborating components on the JVM.
    * **Location:** `src/test/...` (often in the higher-level component's module).
    * **Techniques:** Partial mocking/fakes, controlled dependencies, coroutine/Flow testing, assertions, MockK `verify`.
    * **Focus:** Data flow between layers (ViewModel <-> Repository, Repository <-> DataSource), error propagation.

### 4.4. Tooling & Libraries

* **Test Runner:** JUnit 4
* **Mocking:** MockK (`io.mockk:mockk`)
* **Coroutine/Flow Testing:** `kotlinx-coroutines-test`, `app.cash.turbine:turbine`
* **Assertions:** JUnit assertions, MockK `verify`.

### 4.5. Requirements & Best Practices

* **Location:** `src/test` directory.
* **Naming:** `[ClassNameUnderTest]Test.kt`; methods describe scenarios (e.g., `` `when X_and Y_then Z` ``).
* **Structure:** Arrange-Act-Assert (AAA).
* **Isolation:** Unit tests mock all external dependencies. Integration tests define clear boundaries.
* **Readability & Maintainability:** Clear, concise, focused tests.
* **No Android Framework Dependencies (where possible in `src/test`):** Mock if essential.
* **Speed & Reliability:** Fast, consistent JVM tests. Avoid flakiness.
* **Code Coverage:** Aim for good coverage of critical logic. Quality over quantity.

### 4.6. Future Testing Considerations

* UI/E2E Tests (`src/androidTest`) using Jetpack Compose testing APIs and Hilt.
* CI Integration for all tests.

## 5. Developer & AI Guidance

*(Derived from CLAUDE.MD - No changes in this section from previous version of this guide)*

### 5.1. Key Architectural Patterns & Conventions

* **Modular Architecture:** As described in section 2.2.
* **MVVM with Jetpack Compose UI.**
* **Repository Pattern:** Interfaces in `:core-data`, implementations in `:data`.
* **Dependency Injection:** Hilt.
* **Asynchronous Operations:** Kotlin Coroutines and Flow.
* **Networking:** Ktor with OkHttp engine. Avoid direct `HttpURLConnection`.
* **Error Handling:** Use the `ErrorMapperService` from `:core-data` (interface) to map exceptions to user-friendly messages.
* **Authentication:**
    * Microsoft: Uses `MicrosoftAuthManager` and `MicrosoftTokenProvider` in `:backend-microsoft`.
    * Google: Uses `GoogleAuthManager` and `GoogleTokenProvider` in `:backend-google`.
* **UI:** Follow Material 3 design guidelines to match Pixel aesthetics.

### 5.2. Build and Run Commands

* **Build app:** `./gradlew build`
* **Install and run (debug):** `./gradlew installDebug`
* **Run all unit tests:** `./gradlew test`
* **Run specific test class:** `./gradlew :module-name:testDebugUnitTest --tests "net.melisma.package.TestClass"`
* **Run specific test method:** `./gradlew :module-name:testDebugUnitTest --tests "net.melisma.package.TestClass.testMethod"`
* **Clean build:** `./gradlew clean build`
* **Kotlin linting:** `./gradlew ktlintCheck`
* **Android linting:** `./gradlew lint`
* **Test coverage report (if configured):** `./gradlew testDebugUnitTestCoverage`

### 5.3. Current Development Focus

The primary focus is completing Google Account Integration (Epic 9), followed by the prioritized backlog items (Core Mail Viewing, Basic Mail Actions, etc.). Refer to the "Implementation Plan & Next Steps" (Section 3) for immediate next steps.

---

This consolidated document aims to be a central reference. It should be updated as the project evolves.

