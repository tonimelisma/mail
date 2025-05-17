# Melisma Mail - Comprehensive Project Guide

**Version:** 1.9 (Consolidated and Updated - Reflecting Harmonization Plan Implementation and Future
Work)

## 1. Project Overview

Melisma Mail is an Android email client aiming to provide a clean, native user experience similar to
stock Pixel apps, using Material 3. It's being built as a potential replacement for default mail
apps, supporting Microsoft Outlook and Google (Gmail) accounts.

The application follows a modular architecture utilizing Hilt for dependency injection and Jetpack
Compose with ViewModels (MVVM pattern) for the UI layer. Key technologies include Kotlin,
Coroutines, Flow, and Ktor for networking. For Google OAuth, it uses
`androidx.credentials.CredentialManager` for initial sign-in and **AppAuth for Android** for API
authorization and token management.

### 1.1. Current Status (As of May 12, 2025)

* **Microsoft Account Support:**
    * Users can securely sign in and out using Microsoft accounts (MSAL). (Implemented)
    * Users can fetch and view a list of their mail folders after signing in. (Implemented)
    * Users can fetch and view a list of emails within a selected folder. (Implemented)
* **Google Account Support (AppAuth Strategy for API Authorization):**
    * Users can securely sign in using `androidx.credentials.CredentialManager` to obtain an ID
      Token. (Implemented)
    * The app uses AppAuth for Android to perform the OAuth 2.0 authorization code grant flow,
      obtaining access and refresh tokens for Gmail API scopes. (Implemented)
    * Tokens are securely stored in `AccountManager` using `SecureEncryptionService`. (Implemented)
    * Users can fetch and view a list of their Gmail labels (folders). (Implemented)
    * Users can fetch and view a list of emails within a selected Gmail label. (Implemented)
* **Core Functionality:**
    * Basic UI for displaying accounts, folders, and message lists is in place using Jetpack
      Compose. (Implemented)
  * Harmonized Conversation Views: Email threads in folder views are now consistently displayed with
    participant summaries for both Google and Microsoft accounts, leveraging a unified threading
    model. (Implemented)
* **Identified Issues/Future Work (Action Items):**
    * **Performance - Main Thread Jank:** Logcat indicates "Skipped frames," suggesting potential
      main thread blocking during app startup or initial UI rendering. This needs investigation and
      optimization. (Pending)
    * **Performance - Paging for Email Lists:** For very long email lists, implementing Jetpack
      Paging 3 is advisable to improve performance and memory usage. (Pending)

### 1.2. Setup & Build

1.  **Clone the repository.**
2.  **Open in Android Studio:** Use the latest stable version.
3. **Sync Gradle:** Allow dependencies to sync. Ensure necessary Android SDK versions are installed.
   This will include the `net.openid:appauth` library.
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
* **Google OAuth Strategy (Ideal):**
    * **Primary API Authorization & Token Management:** **AppAuth for Android** library for the
      complete OAuth 2.0 authorization code grant flow (PKCE, token exchange, refresh, revocation)
      using an **Android Client ID**.
* **Microsoft OAuth Strategy (Ideal):**
    * **MSAL (Microsoft Authentication Library) for Android:** For all authentication,
      authorization, and token management (interactive sign-in, silent token acquisition, refresh,
      multi-account).
* **Security**: Secure storage of OAuth tokens and sensitive data using Android Keystore via
  `SecureEncryptionService`, integrated with `AccountManager`.

### 2.2. Modules (Revised - Ideal Future State)

* **:app**: Main application module.
    * **Purpose**: UI (Jetpack Compose), ViewModels, and the `MailApplication` class. Handles
      UI-specific logic, including launching AppAuth's authorization intent via
      `ActivityResultLauncher` and passing necessary `Activity` contexts to repositories for
      authentication operations.
    * **Key Classes/Files**: `MainActivity.kt`, `MainViewModel.kt`, UI Composables.

* **:core-data**: Defines core data interfaces, domain models, DI helpers, and shared services.
    * **Purpose**: Provides abstractions for data handling and common utilities.
  * **Key Classes/Files**: `AccountRepository.kt` (interface); `MailApiService.kt` (interface);
    `Account.kt` (model, including `needsReauthentication` flag), `MailFolder.kt`, `Message.kt`;
    `ErrorMapperService.kt` (interface); `SecureEncryptionService.kt`; `AuthConfigProvider.kt` (
    interface for MSAL config); Hilt Qualifiers/Scopes.

* **:data**: Implements repository interfaces from `:core-data`.
    * **Purpose**: Orchestrates data operations, managing interactions with backend-specific
      services and providing a unified data source to the app layer. Handles aggregation of data
      from multiple accounts if necessary.
    * **Key Classes/Files**: `DefaultAccountRepository.kt` (manages Google accounts, delegates to
      `MicrosoftAccountRepository` for MS), `MicrosoftAccountRepository.kt` (manages MS accounts);
      DI modules.

* **:backend-microsoft**: Handles Microsoft/Outlook specific logic.
    * **Purpose**: Authentication (MSAL) and API interaction (Microsoft Graph) for Microsoft
      accounts.
    * **Key Classes/Files**:
        * `MicrosoftAuthManager.kt`: Core MSAL wrapper (using
          `IMultipleAccountPublicClientApplication`) for interactive sign-in, silent token
          acquisition, account management, and sign-out. Initialized with configuration from
          `AuthConfigProvider`.
        * `MicrosoftTokenPersistenceService.kt`: Securely stores Microsoft account identifiers and
          encrypted access/ID tokens in `AccountManager` (MSAL manages its own refresh token).
        * `MicrosoftKtorTokenProvider.kt`: Provides Bearer tokens to Ktor by calling
          `MicrosoftAuthManager` for silent token acquisition. Returns a placeholder (e.g., account
          ID) for `BearerTokens.refreshToken`. Handles `MsalUiRequiredException` by signaling the
          need for re-authentication.
        * `GraphApiHelper.kt`: Implements `MailApiService` for Microsoft Graph using an auth-enabled
          Ktor client.
        * `MicrosoftAuthenticatorService.kt`, `MicrosoftStubAuthenticator.kt`: For `AccountManager`
          integration.
        * `MicrosoftErrorMapper.kt`; `ActiveMicrosoftAccountHolder.kt`; DI module (
          `BackendMicrosoftModule.kt` - configures Ktor client with `Auth` plugin).

* **:backend-google**: Handles Google/Gmail specific logic.
    * **Purpose**: Authentication (AppAuth for API tokens) and API interaction (Gmail API) for
      Google accounts.
    * **Key Classes/Files**:
        * `AppAuthHelperService.kt`: Encapsulates AppAuth library interactions (building
          authorization requests with PKCE using Android Client ID, performing token exchange, token
          refresh, and token revocation).
        * `GoogleTokenPersistenceService.kt`: Securely stores the complete, encrypted AppAuth
          `AuthState` (access, refresh, ID tokens) in `AccountManager`.
        * `GoogleKtorTokenProvider.kt`: Provides Bearer tokens to Ktor. Loads `AuthState` from
          `GoogleTokenPersistenceService`, handles silent refresh using `AppAuthHelperService` and
          the stored refresh token. Returns a placeholder (e.g., account ID) for
          `BearerTokens.refreshToken`. Handles `invalid_grant` during refresh by clearing the
          invalid `AuthState` and signaling the need for re-authentication.
        * `GmailApiHelper.kt`: Implements `MailApiService` for Gmail using an auth-enabled Ktor
          client.
        * `GoogleAuthenticatorService.kt`, `GoogleStubAuthenticator.kt`: For `AccountManager`
          integration.
        * `GoogleErrorMapper.kt`; `ActiveGoogleAccountHolder.kt`; DI module (
          `BackendGoogleModule.kt` - configures Ktor client with `Auth` plugin).

### 2.3. Prioritized Requirements Backlog (Epics & Status)

The application development follows a prioritized backlog of features grouped into Epics.

---
**EPIC 1: Core Mail Viewing** (Highest Priority)

* **Requirement 1.1 (View Message List):** As a user, I want to see a list of emails within a
  selected folder. **(Implemented for MS & Google)**
    * *Future Enhancement:* Implement Jetpack Paging 3 for very long email lists. **(Pending)**
* **Requirement 1.2 (View Single Message):** As a user, I want to tap on an email in the list to
  view its full content. **(Pending - UI/Navigation only)**
* **Requirement 1.3 (Folder Navigation):** As a user, I want to navigate between different mail
  folders. **(Implemented for MS & Google)**
* **Requirement 1.4 (Unified Inbox - UI/UX):** As a user, I want an option to view emails from all
  my accounts in a single combined inbox view. **(Pending)**
* **Requirement 1.5 (Conversation View - UI/UX):** As a user, I want emails belonging to the same
  thread to be grouped. **(Done for folder views)**
    * *Note:* Folder views now group messages by conversation and display participant summaries for
      both Google and Microsoft accounts, as per the May 2025 harmonization plan.

---
**EPIC 2: Basic Mail Actions** (High Priority)

* **Requirement 2.1 (Mark Read/Unread):** As a user, I want to mark messages as read or unread. **(
  Implemented for Gmail, Pending for MS Graph API)**
* **Requirement 2.2 (Delete Message):** As a user, I want to delete one or more messages. **(
  Implemented for Gmail, Pending for MS Graph API)**
* **Requirement 2.3 (Archive Message):** As a user, I want to archive one or more messages. (This
  often means moving to an "Archive" folder or removing "Inbox" label for Gmail). **(Gmail:
  moveMessage can handle this, MS: Pending)**
* **Requirement 2.4 (Customizable Swipe Actions - UI/UX):** As a user, I want to configure actions
  for swipes on the message list. **(Pending)**

---
**EPIC 3: Composing & Sending** (Medium-High Priority)

* **Requirement 3.1 (Compose New Email):** As a user, I want to initiate composing a new email. **(
  Pending)**
* **Requirement 3.2 (Send Email):** As a user, I want to send the composed email. **(Pending)**
* **Requirement 3.3 (Reply/Reply-All/Forward):** As a user, I want to reply, reply-all, or forward a
  received message. **(Pending)**
* **Requirement 3.4 (Signature Management):** As a user, I want to define and manage email
  signatures. **(Pending)**

---
**EPIC 4: Attachments** (Higher Priority)

* **Requirement 4.1 (View Attachments):** As a user, I want to see a list of attachments within a
  received email. **(Pending)**
* **Requirement 4.2 (Preview Attachments):** As a user, I want to preview common attachment types. *
  *(Pending)**
* **Requirement 4.3 (Save Attachments):** As a user, I want to save attachments from an email. **(
  Pending)**
* **Requirement 4.4 (Share Attachments):** As a user, I want to share attachments directly from an
  email. **(Pending)**
* **Requirement 4.5 (Attach Files):** As a user, I want to easily attach files when composing an
  email. **(Pending)**

---
**EPIC 5: Account & App Foundation** (Underpins everything)

* Authentication & Core:
    * **Requirement 5.1 (Authentication - Functional):** As a user, I want to securely sign in and
      out of my supported email accounts (Microsoft Outlook: **Implemented**, Google Gmail: *
      *Implemented** using AppAuth for API authorization).
    * **Requirement 5.2 (Basic Error Handling - Functional):** As a user, I want to see clear
      messages if actions fail. (Partially Implemented - `ErrorMapperService` exists, UI toasts
      shown).
* User Experience:
    * **Requirement 5.3 (Visual Polish - Non-Functional):** The app should adhere to Material 3
      guidelines. (Partially Implemented - Basic M3 theme in place).
    * **Requirement 5.4 (Performance - Non-Functional):** UI interactions should feel smooth and
      responsive. (Partially Implemented - Core architecture is good, but **jank observed during
      startup/initial load needs addressing - Pending**).
    * **Requirement 5.5 (Theming - UI/UX):** As a user, I want the app to support Light and Dark
      themes. **(Implemented - Dynamic Color also supported)**.
* Offline & Sync:
    * **Requirement 5.6 (Data Caching - Non-Functional):** The app should cache data locally (e.g.,
      using Room database) to improve performance and enable offline access. **(Pending - Currently
      data is fetched on demand and held in memory by repositories/ViewModel state).**
    * **Requirement 5.7 (Background Sync - Functional):** As a user, I want the app to periodically
      check for new emails. **(Pending)**
* Notifications:
    * **Requirement 5.8 (Push Notifications - Functional):** As a user, I want to receive
      notifications for new emails. **(Pending)**
    * **Requirement 5.9 (Actionable Notifications - Functional):** As a user, I want to perform
      quick actions from an email notification. **(Pending)**
* Analytics & Logging:
    * **Requirement 5.10 (Local Error Logging - Non-Functional):** The app must log critical errors
      locally. (Partially Implemented - Android Logcat used extensively. SLF4J present but not
      configured with a provider.)
    * **Requirement 5.11 (Log Export - Functional):** As a user/developer, I want a way to export
      local error logs. **(Pending)**
    * **Requirement 5.12 (Usage Metrics - Non-Functional):** The app should gather anonymized basic
      usage metrics locally. **(Pending)**
* **Requirement 5.13 (New Device Account Restoration - Google):** As a user, I want an easy way to
  restore my Google account linkage when setting up Melisma Mail on a new Android device (via
  Credential Manager's Restore Credentials feature). **(Pending - Requires testing and potential
  explicit handling)**.

---
**EPIC 6: Advanced Mail Organization** (Medium Priority)

* **Requirement 6.1 (Move Message):** As a user, I want to move messages between folders. **(
  Implemented for Gmail, Pending for MS Graph API)**
* **Requirement 6.2 (Advanced Search Filters):** As a user, I want to search my emails using
  advanced filters. **(Pending)**
* **Requirement 6.3 (Junk/Spam Management):** As a user, I want basic controls to mark emails as
  junk/spam. **(Pending - Though underlying APIs for moving to Spam folder exist for Gmail)**.
* **Requirement 6.4 (Folder Management):** As a user, I want to create, rename, and delete custom
  mail folders. **(Pending)**
* **Requirement 6.5 (Unsubscribe Suggestions):** As a user, I want the app to suggest an easy
  unsubscribe option. **(Pending)**
* **Requirement 6.6 (Handling Multiple Credential Types - Future):** As a user, I want the app to
  securely manage different types of credentials if other protocols beyond Google OAuth & MSAL are
  supported in the future (e.g., passkeys for other services, IMAP username/password). **(Future
  Consideration)**

---
**EPIC 7: Settings & Configuration** (Medium Priority)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (e.g., account
  management, theme). **(Partially Implemented - Account management UI via SettingsScreen)**.

---
**EPIC 8: Integrations** (Lower Priority)

* **Requirement 8.1 (Calendar Integration):** As a user, I want to quickly create calendar events
  from email content. **(Pending)**
* **Requirement 8.2 (Task Integration):** As a user, I want to create tasks based on email content.
  **(Pending)**
* **Requirement 8.3 (Contact Integration):** As a user, I want to easily save email senders as
  device contacts. **(Pending)**

---
**EPIC 9: Google Account Integration** (Largely Implemented - Core functionality)

* **Requirement 9.1 (Google Authentication & API Authorization):** As a user, I want to securely
  sign in to my Google account (initial auth via `CredentialManager`) and authorize the app to
  access Gmail APIs (via AppAuth flow, handling consent for necessary permissions, and obtaining
  access/refresh tokens). **(Implemented)**
* **Requirement 9.2 (View Google Folders/Labels):** As a user, I want to see the list of
  folders/labels for my Google account. **(Implemented)**
* **Requirement 9.3 (View Google Message List):** As a user, I want to see the list of emails within
  a selected Google folder/label. **(Implemented)**
* **Requirement 9.4 (View Single Google Message):** As a user, I want to view the full content of an
  email from my Google account. **(Pending - UI/Navigation part)**
* *(Mirroring other Epics for Google)* Basic Actions (Mark Read/Unread, Delete, Move: **Partially
  Implemented**), Attachments (**Pending**), Composing/Sending (**Pending**), etc., for the Google
  account, using tokens obtained via AppAuth.

---

### 2.4. Authentication Flow Details (Ideal Future State)

This section outlines the ideal, end-to-end authentication flows.

#### 2.4.1 Google Authentication Flow (AppAuth)

1. **Login Initiation (UI -> ViewModel -> `DefaultAccountRepository`)**:
    * User action triggers ViewModel, which calls
      `AccountRepository.getAuthenticationIntentRequest("GOOGLE", activity)`.
    * `DefaultAccountRepository` uses `AppAuthHelperService` to build an `AuthorizationRequest` (
      Android Client ID, redirect URI, scopes: `openid`, `email`, `profile`, `offline_access`).
    * `AppAuthHelperService` creates an `Intent` for Google's auth screen.
    * ViewModel launches this `Intent` via `ActivityResultLauncher`.

2. **User Authentication & Grant (Google UI)**:
    * User authenticates and grants permissions. Google redirects to app's
      `RedirectUriReceiverActivity` with an authorization code.

3. **Token Exchange & Persistence (Activity
   Result -> `DefaultAccountRepository` -> `AppAuthHelperService` ->`GoogleTokenPersistenceService`)
   **:
    * Activity receives result, passes it to
      `AccountRepository.handleAuthenticationResult("GOOGLE", ...)`.
    * `DefaultAccountRepository` calls
      `appAuthHelperService.exchangeAuthorizationCode(authResponse)` (suspend function).
        * `AppAuthHelperService` exchanges the code for `TokenResponse` (access, refresh, ID
          tokens).
    * ID token is parsed for user info.
    * `GoogleTokenPersistenceService.saveTokens(accountId, email, ..., tokenResponse)` encrypts and
      stores the full `AuthState` in `AccountManager`.
    * `ActiveGoogleAccountHolder` is updated. UI is notified.

4. **Authenticated API Calls (Ktor + `GoogleKtorTokenProvider`)**:
    * Ktor `Auth` plugin calls `GoogleKtorTokenProvider.getBearerTokens()`.
    * Provider retrieves/decrypts `AuthState` via `GoogleTokenPersistenceService`.
    * **Refresh:** If `AuthState.needsTokenRefresh` is true, provider uses
      `AppAuthHelperService.refreshAccessToken()` with the stored refresh token.
        * On success, updated `AuthState` is re-encrypted and saved.
        * On `invalid_grant`, `AuthState` is cleared, and re-authentication is signaled (provider
          returns null).
    * Valid access token is returned to Ktor as `BearerTokens` (with a placeholder for
      `refreshToken` field).

5. **Logout (UI ->ViewModel -> `DefaultAccountRepository` -> `AppAuthHelperService` ->
   `GoogleTokenPersistenceService`)**:
    * ViewModel calls `AccountRepository.signOut(googleAccount)`.
    * `DefaultAccountRepository`:
        * Retrieves `AuthState` via `GoogleTokenPersistenceService`.
        * Calls `appAuthHelperService.revokeToken(refreshToken)` to revoke it on Google's server.
        * Calls `googleTokenPersistenceService.clearTokens(accountId, removeAccount = true)` to
          delete the `AccountManager` entry and encrypted `AuthState`.
        * Updates active account status and UI.

#### 2.4.2 Microsoft Authentication Flow (MSAL)

1. **MSAL Initialization (`MicrosoftAuthManager`)**:
    * `IMultipleAccountPublicClientApplication` is initialized with config from
      `R.raw.auth_config_msal.json` (client ID, broker redirect URI, authority).

2. **Login Initiation (UI ->
   ViewModel -> `DefaultAccountRepository` -> `MicrosoftAccountRepository` ->`MicrosoftAuthManager`)
   **:
    * User action triggers ViewModel, calls
      `AccountRepository.getAuthenticationIntentRequest("MS", activity)`.
    * `DefaultAccountRepository` delegates to `MicrosoftAccountRepository`.
    * `MicrosoftAccountRepository` calls `microsoftAuthManager.signInInteractive(activity, scopes)`.
        * `MicrosoftAuthManager` uses MSAL to launch external UI (browser/broker). MSAL handles the
          redirect internally.
        * Results (success/error/cancel) are delivered to an `AuthenticationCallback` within
          `signInInteractive`, which emits `AuthenticationResultWrapper` on a Flow.
    * `MicrosoftAccountRepository` collects this Flow:
        * On success (`IAuthenticationResult`): Calls
          `MicrosoftTokenPersistenceService.saveAccountInfo(authResult)` to encrypt and store
          access/ID tokens and account identifiers in `AccountManager` (MSAL manages refresh token).
          Updates `ActiveMicrosoftAccountHolder`. Notifies UI.
    * `getAuthenticationIntentRequest` for "MS" returns `flowOf(null)`.

3. **Authenticated API Calls (Ktor + `MicrosoftKtorTokenProvider`)**:
    * Ktor `Auth` plugin calls `MicrosoftKtorTokenProvider.loadBearerTokens()` or
      `refreshBearerTokens()`.
    * Provider gets active MSAL account ID, then retrieves the `IAccount` object via
      `microsoftAuthManager.getAccount(accountId).firstOrNull()`.
    * Calls `microsoftAuthManager.acquireTokenSilent(iAccount, scopes)`.
        * MSAL attempts silent token acquisition (cache or internal refresh token via broker).
          Results delivered to `SilentAuthenticationCallback`, emitted as
          `AuthenticationResultWrapper` on a Flow.
    * Provider collects this Flow:
        * On success: Returns `BearerTokens` (access token + placeholder for refresh token).
        * On `MsalUiRequiredException`: Signals re-authentication is needed (provider returns null).
    * Valid access token is returned to Ktor.

4. **Logout (UI ->ViewModel -> `DefaultAccountRepository` -> `MicrosoftAccountRepository` ->
   `MicrosoftAuthManager` -> `MicrosoftTokenPersistenceService`)**:
    * ViewModel calls `AccountRepository.signOut(microsoftAccount)`.
    * `DefaultAccountRepository` delegates to `MicrosoftAccountRepository`.
    * `MicrosoftAccountRepository`:
        * Retrieves `IAccount` via `MicrosoftAuthManager`.
        * Calls `microsoftAuthManager.signOut(iAccount)` (MSAL removes account from its
          cache/broker).
        * On successful MSAL sign-out, calls
          `microsoftTokenPersistenceService.deleteAccount(iAccount.id)` to remove the
          `AccountManager` entry and encrypted tokens.
        * Updates active account status and UI.

---
**EPIC 6: Settings & Configuration** (Medium Priority)

* **Requirement 7.1 (Basic Settings):** As a user, I want access to basic settings (e.g., account
  management, theme). **(Partially Implemented - Account management UI via SettingsScreen)**.

---

### 2.4. Architectural Vision & Iterative Implementation (Revised May 12, 2025)

* **Iterative Implementation:** The project follows an iterative approach, focusing on delivering
  core value incrementally. The current focus is on stabilizing core viewing features for both
  Microsoft and Google accounts and then proceeding with mail actions and composition.
* **Core Architectural Refinements:**
    * `ErrorMapperService` Interface in `:core-data` (`ErrorMapperService.kt`), with implementations
      in `:backend-microsoft` (`MicrosoftErrorMapper.kt`) and `:backend-google` (
      `GoogleErrorMapper.kt`).
    * `MailApiService` Abstraction in `:core-data` (`MailApiService.kt`): Defines common mail
      operations (e.g., `getMailFolders`, `getMessagesForFolder`) implemented by
      `GraphApiHelper.kt` (for Microsoft) and `GmailApiHelper.kt` (for Google). Repositories in
      `:data` (e.g., `DefaultFolderRepository.kt`, `DefaultMessageRepository.kt`) use this
      abstraction via Hilt multibindings.
* **Conversation View Harmonization:**
    * `DefaultThreadRepository.kt` in `:data` is the central component for fetching and assembling
      `MailThread` objects for all conversation-aware list views (e.g., Inbox, Sent).
    * `MailThread.kt` model in `:core-data` is the primary data object for these lists, featuring a
      `participantsSummary`.
    * `GraphApiHelper.kt`'s `getMessagesForThread` method has been updated to query `/me/messages` (
      filtered by `conversationId`), ensuring true cross-folder threading for Microsoft accounts.
      `GmailApiHelper.kt` already supported this.
    * The UI (`MainApp.kt` in `:app`) displays `ThreadListContent.kt` (which uses
      `ThreadListItem.kt`) when the view mode is set to "Threads", correctly rendering participant
      summaries.
* **Authentication Layer:**
    * **Microsoft:** Uses `MicrosoftAuthManager.kt` (MSAL wrapper) in `:backend-microsoft`. Token
      storage and management are handled by MSAL's internal mechanisms. Ktor integration via
      `MicrosoftKtorTokenProvider.kt`.
    * **Google:**
        * **Initial Sign-In (ID Token):** `GoogleAuthManager.kt` in `:backend-google` uses
          `androidx.credentials.CredentialManager` with `GetSignInWithGoogleOption` (configured with
          a Web Client ID:
          `326576675855-6vc6rrjhijjfch6j6106sd5ui2htbh61.apps.googleusercontent.com`). This step
          primarily identifies the user and obtains an ID Token.
        * **API Authorization & Tokens (Access/Refresh):** The **AppAuth for Android library** flow
          is managed by `AppAuthHelperService.kt` in `:backend-google`. It uses the **Android Client
          ID** (from `BuildConfig.GOOGLE_ANDROID_CLIENT_ID` e.g.,
          `326576675855-r404vqtrr8ohbpl7g6tianaekkt70igd.apps.googleusercontent.com`) and manages
          the authorization code grant with PKCE. AppAuth handles launching the consent UI via
          Custom Tabs.
        * **Token Storage:** `GoogleTokenPersistenceService.kt` (in `:backend-google`) handles
          encrypting (via `SecureEncryptionService.kt` from `:core-data`, which uses Android
          Keystore) and storing AppAuth-obtained Google tokens (access, refresh, ID) into Android's
          `AccountManager` under a custom account type (`net.melisma.mail.GOOGLE`).
          `GoogleStubAuthenticator.kt` and `GoogleAuthenticatorService.kt` facilitate this
          `AccountManager` integration.
        * **Ktor Integration:** Ktor's `Auth` plugin is configured in `BackendGoogleModule.kt` to
          use `GoogleKtorTokenProvider.kt`, which loads tokens from `GoogleTokenPersistenceService`
          and refreshes them using `AppAuthHelperService`.
* **Consent Handling for Google:** AppAuth's standard flow using Custom Tabs presents Google's
  consent screen. `DefaultAccountRepository.kt` coordinates the overall flow: initial
  `CredentialManager` sign-in, triggering the AppAuth flow (via an `Intent` emitted to the
  `MainActivity`), and persisting tokens. The previous `GoogleAccountCapability` for `IntentSender`
  appears to be a legacy path, with the AppAuth flow being the primary mechanism for API
  authorization.
* **State Management:**
    * Repositories (`DefaultAccountRepository`, `DefaultFolderRepository`,
      `DefaultMessageRepository`) expose data and loading states via Kotlin `StateFlow`.
    * `MainViewModel.kt` collects these flows, combines them into `MainScreenState`, and exposes
      this to the Compose UI (`MainActivity.kt`).
    * `ActiveGoogleAccountHolder.kt` and `ActiveMicrosoftAccountHolder.kt` manage the currently
      selected account ID for each provider in-memory. *Future Improvement: Persist this active
      account state (e.g., via SharedPreferences) to restore across app sessions.*
* **Performance Action Items:**
    * **Address Main Thread Jank:** Investigate and resolve observed "Skipped frames" during app
      startup/initial load using Android Studio Profiler.
    * **Implement Paging:** For long lists of emails (Requirement 1.1), implement the Jetpack Paging
      3 library to improve performance and memory efficiency.
* **Local Caching Strategy (Future Enhancement):**
    * While not yet implemented, a future enhancement will involve introducing a local database (
      e.g., Room) as a single source of truth (Requirement 5.6). Repositories will synchronize
      network data to the database, and ViewModels will observe data from the database, enabling
      better offline support and faster initial loads.

## 4. Test Strategy

*(No significant changes from previous version, but tests for Google now cover CredentialManager for
ID token, AppAuth flows for API tokens, AccountManager token storage, and Ktor refresh logic.)*

### 4.1. Goals
* Verify correctness of individual components.
* Prevent regressions.
* Facilitate refactoring.
* Validate interactions between components.
* Improve code design towards testability.

### 4.2. Scope (Phase 1: Unit & Integration on JVM)

* **In Scope:** Unit tests and JVM-based integration tests.
* **Out of Scope (For Now):** UI/E2E tests, performance, security, usability testing (beyond initial
  developer checks).

### 4.3. Test Levels & Techniques

* **Unit Tests:** MockK, `kotlinx-coroutines-test`, Turbine, JUnit. Focus on ViewModels (
  `MainViewModelTest.kt`), Repositories (`DefaultAccountRepositoryTest.kt` - assumed), Mappers (
  `GoogleErrorMapperTest.kt`), API Helpers (`GmailApiHelperTest.kt`, `GraphApiHelperTest.kt`),
  Authentication components (`GoogleAuthManagerTest.kt` - assumed, `AppAuthHelperServiceTest.kt`),
  Token Persistence services.
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
* Performance testing after addressing jank.

## 5. Developer & AI Guidance

### 5.1. Key Architectural Patterns & Conventions
* **Modular Architecture:** As described in section 2.2.
* **MVVM with Jetpack Compose UI.**
* **Repository Pattern.**
* **Dependency Injection:** Hilt.
* **Asynchronous Operations:** Kotlin Coroutines and Flow.
* **Networking:** Ktor with OkHttp engine.
* **Error Handling:** `ErrorMapperService` from `:core-data` (`GoogleErrorMapper.kt`,
  `MicrosoftErrorMapper.kt`).
* **Authentication:**
    * Microsoft: `MicrosoftAuthManager.kt` (MSAL) in `:backend-microsoft`.
    * Google:
        * Initial Sign-In (ID Token): `GoogleAuthManager.kt` in `:backend-google` (using
          `CredentialManager` with Web Client ID).
        * API Authorization & Tokens (Access/Refresh): AppAuth for Android (via
          `AppAuthHelperService.kt` in `:backend-google`, using Android Client ID).
        * Token Storage: `GoogleTokenPersistenceService.kt` using `AccountManager` (with Keystore
          encryption via `SecureEncryptionService.kt`).
* **UI:** Material 3 (`Theme.kt`, `Color.kt`, `Type.kt`).

### 5.2. Build and Run Commands
* **Build app:** `./gradlew build`
* **Install and run (debug):** `./gradlew installDebug`
* **Run all unit tests:** `./gradlew test`
* **Clean build:** `./gradlew clean build`

### 5.3. Current Development Focus

1. Complete core mail actions (Mark Read/Unread, Delete, Move) for Microsoft Graph API.
2. Address performance jank observed during startup (Action Item).
3. Begin implementation of "Compose New Email" (EPIC 3).
4. Plan and implement Jetpack Paging 3 for message lists (Action Item).
5. Plan and implement local database caching (Room).

---

This consolidated document aims to be a central reference. It should be updated as the project evolves.
