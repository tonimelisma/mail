# Melisma Mail - Architectural Design & Developer Guide

**Version:** 2.0 (Comprehensive Rewrite)
**Date:** May 13, 2025

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
    * Google: `androidx.credentials.CredentialManager` (for initial sign-in/ID Token) & AppAuth for
      Android (for OAuth 2.0 authorization code flow and token management for API access).
    * Microsoft: MSAL (Microsoft Authentication Library) for Android.
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

The UI layer utilizes the MVVM pattern.

* **Views (Composables):** Observe state changes from ViewModels and render the UI. They delegate
  user actions to ViewModels.
* **ViewModels:** Prepare and manage UI-related data (state) for the Views. They interact with
  repositories to fetch and manipulate data, and contain UI logic. They expose state using
  `kotlinx.coroutines.flow.StateFlow`.
* **Models:** Represent the data structures (e.g., `Account`, `Message`, `MailFolder`).

### 2.3. Repository Pattern

Repositories abstract data sources. ViewModels interact with repository interfaces defined in
`:core-data`. Implementations in the `:data` module coordinate data from different sources (network,
future local cache).

### 2.4. Modularity

The application is divided into functionally distinct modules to promote separation of concerns,
reusability, and faster build times. (See Section 3 for module details).

### 2.5. Dependency Injection (Hilt)

Hilt is used for managing dependencies throughout the application, simplifying DI and improving
testability. Key Hilt scopes (e.g., `@Singleton`, `@ViewModelScoped`) are utilized.

### 2.6. Asynchronous Operations

Kotlin Coroutines and Flow are used extensively for managing background tasks, network requests, and
reactive data streams, ensuring a non-blocking UI.

### 2.7. Security

* **Token Storage:** OAuth tokens and other sensitive data are encrypted using
  `SecureEncryptionService` (which internally uses Android Keystore) before being stored in
  `AccountManager`.
* **Communication:** HTTPS is used for all network communication.
* **Provider Libraries:** MSAL and AppAuth are industry-standard libraries for handling OAuth 2.0
  flows securely.

## 3. Module Architecture Deep Dive

This section details the purpose, key components, and interactions within each module.

### 3.1. `:app` Module

* **Purpose & Scope:** The main application module, responsible for the user interface and user
  experience. It orchestrates UI navigation and presents data obtained from the data layer.
* **Key Components & Classes:**
    * `MainActivity.kt`: The primary entry point of the application. Hosts Jetpack Compose UI
      content and handles `ActivityResultLauncher` callbacks for authentication flows (e.g., from
      AppAuth).
    * `MainViewModel.kt`: The central ViewModel for the main screen. It collects data from various
      repositories (`AccountRepository`, `FolderRepository`, `MessageRepository`,
      `ThreadRepository`), manages UI state (`MainScreenState`), and handles user interactions by
      delegating to repositories. It also manages the launching of authentication `Intent`s.
    * `MailApplication.kt`: The `Application` class, typically used for Hilt setup and other
      application-level initializations.
    * **UI Composables (various files/packages):**
        * `MainApp.kt`: Sets up the main navigation and screen structure.
        * Screens (e.g., `HomeScreen.kt`, `SettingsScreen.kt`): Define the layout and behavior of
          different parts of the UI.
        * Component Composables (e.g., `MessageListItem.kt`, `FolderListItem.kt`): Reusable UI
          elements.
    * `di/AppProvidesModule.kt`: Provides application-level dependencies, such as the
      `ApplicationCoroutineScope` and `AuthConfigProvider` (for MSAL config).
* **Data Flow:**
    * ViewModels observe `StateFlow`s exposed by repositories.
    * UI Composables observe `StateFlow`s exposed by ViewModels.
    * User actions in Composables trigger methods in ViewModels, which in turn call methods in
      repositories.
    * For authentication, `MainViewModel` initiates flows in `AccountRepository` and handles
      `Intent`s for UI actions like launching an AppAuth Custom Tab.
* **Specific Patterns/Strategies:**
    * Unidirectional Data Flow (UDF) with `StateFlow` for managing UI state.
    * Jetpack Navigation for Compose to handle screen transitions.

### 3.2. `:core-data` Module

* **Purpose & Scope:** Defines the contracts (interfaces) and core data models for the entire
  application. It acts as the "single source of truth" for data structures and repository
  interfaces, ensuring decoupling between the UI layer and specific data implementations.
* **Key Components & Classes:**
    * **Repository Interfaces:**
        * `AccountRepository.kt`: Defines operations related to account management, authentication (
          sign-in, sign-out, handling auth results), and observing account states.
        * `FolderRepository.kt`: Defines operations for fetching and managing mail folders.
        * `MessageRepository.kt`: Defines operations for fetching and managing individual messages.
        * `ThreadRepository.kt`: Defines operations for fetching and managing message threads (
          conversations).
    * **Domain Models (e.g., in `model/` package):**
        * `Account.kt`: Represents a user account (MS or Google), including ID, username, provider
          type, and a `needsReauthentication` flag.
        * `MailFolder.kt`: Represents a mail folder/label.
        * `Message.kt`: Represents an individual email message.
        * `MailThread.kt`: Represents a conversation thread, including a `participantsSummary`.
        * `AuthResultModels.kt` (`GenericAuthResult`, `GenericSignOutResult`,
          `GenericAuthErrorType`): Sealed classes defining the possible outcomes of authentication
          operations. `GenericAuthResult.UiActionRequired` is crucial for intent-based auth flows.
    * **Service Interfaces & Utilities:**
        * `MailApiService.kt`: An abstraction for provider-specific mail operations (get folders,
          messages, etc.). Implemented in backend modules.
        * `ErrorMapperService.kt` & `MappedErrorDetails.kt`: Interface and model for structured
          error mapping, allowing consistent error presentation.
        * `SecureEncryptionService.kt`: Interface for encrypting/decrypting data, typically
          implemented using Android Keystore.
        * `AuthConfigProvider.kt`: Interface used by `:backend-microsoft` to get MSAL configuration
          resource ID from the `:app` module, breaking a direct dependency.
    * **DI Helpers (e.g., in `di/` package):**
        * Hilt Qualifiers (e.g., `@MicrosoftRepo`, `@GoogleHttpClient`): Used to distinguish between
          different implementations of the same interface.
        * Custom Hilt Scopes (if any, e.g., `@ApplicationScope` for `CoroutineScope`).
        * `MailDispatchers.kt`: Defines custom coroutine dispatchers.
* **Data Flow:** This module primarily defines interfaces and models, so it doesn't have active data
  flow itself but dictates how data should be structured and accessed.
* **Specific Patterns/Strategies:**
    * Interface Segregation: Clearly defined interfaces for repositories and services.
    * Dependency Inversion: `:app` and `:data` depend on abstractions in `:core-data`, not concrete
      implementations in backend modules directly.

### 3.3. `:data` Module

* **Purpose & Scope:** Implements the repository interfaces defined in `:core-data`. It orchestrates
  data operations, acting as a bridge between the UI layer (ViewModels) and the backend-specific
  services. It can aggregate data from multiple accounts or sources if needed.
* **Key Components & Classes:**
    * `DefaultAccountRepository.kt`: The primary implementation of `AccountRepository`.
        * Manages Google account authentication flows using `AppAuthHelperService` and
          `GoogleTokenPersistenceService`.
        * Delegates Microsoft account operations to a `MicrosoftAccountRepository` instance (
          injected with `@MicrosoftRepo` qualifier).
        * Combines account lists from both providers.
        * Uses `kotlinx.coroutines.channels.Channel` for handling asynchronous UI actions in
          Google's AppAuth flow (e.g., emitting `GenericAuthResult.UiActionRequired` and receiving
          the final result after the external activity completes).
        * Maintains and exposes `OverallApplicationAuthState`.
    * `MicrosoftAccountRepository.kt`: (Conceptually, this might reside here or be delegated to from
      `DefaultAccountRepository` if it were in `:backend-microsoft` directly implementing
      `AccountRepository`. Based on `NIRVANA.MD`, it seems `DefaultAccountRepository` holds an
      instance of a separate `MicrosoftAccountRepository` that directly implements the
      `AccountRepository` for MS accounts). This class would manage MS accounts, interact with
      `MicrosoftAuthManager` for MSAL operations, and use `MicrosoftErrorMapper`.
    * `DefaultFolderRepository.kt`: Implements `FolderRepository`. Uses Hilt multibindings to get a
      map of `MailApiService` implementations (keyed by provider type) to fetch folders for the
      active account(s).
    * `DefaultMessageRepository.kt`: Implements `MessageRepository`, similarly using
      `MailApiService` for data fetching.
    * `DefaultThreadRepository.kt`: Implements `ThreadRepository`, responsible for fetching and
      assembling `MailThread` objects for conversation views.
    * **DI Modules (e.g., in `di/DataModule.kt`):**
        * Binds repository interfaces (e.g., `AccountRepository`) to their default implementations (
          e.g., `DefaultAccountRepository`).
        * Provides `ErrorMapperService` instances from backend modules into a map for
          `DefaultAccountRepository`.
* **Data Flow:**
    * ViewModels call methods on repository interfaces.
    * `DefaultAccountRepository` coordinates with `:backend-google` services or delegates to
      `MicrosoftAccountRepository`.
    * Other `Default*Repository` classes use injected `MailApiService` implementations (from
      `:backend-*` modules) to fetch data.
    * Data is transformed into the domain models defined in `:core-data` and exposed via `Flow`s.
* **Specific Patterns/Strategies:**
    * Repository Pattern.
    * Facade Pattern (`DefaultAccountRepository` acts as a facade for Google auth and delegates MS
      auth).
    * Strategy Pattern (using a map of `MailApiService` implementations).

### 3.4. `:backend-microsoft` Module

* **Purpose & Scope:** Handles all Microsoft/Outlook specific logic, including MSAL-based
  authentication and Microsoft Graph API interactions.
* **Key Components & Classes:**
    * `MicrosoftAuthManager.kt`: The core MSAL wrapper.
        * Manages `IMultipleAccountPublicClientApplication`.
        * Handles interactive sign-in (`signInInteractive`), silent token acquisition (
          `acquireTokenSilent`), and account removal (`signOut`).
        * Uses `AuthenticationCallback` and `SilentAuthenticationCallback` to bridge MSAL's callback
          system with Kotlin Flow (`callbackFlow`).
        * Integrates logic from the former `MicrosoftTokenPersistenceService` to save/load MSAL
          account identifiers and *non-critical* token information (like access token for quick
          reference, though MSAL manages its own critical refresh tokens securely) to/from Android's
          `AccountManager`, encrypting them with `SecureEncryptionService`.
    * `MicrosoftKtorTokenProvider.kt`: Implements Ktor's `BearerTokenProvider`.
        * Interacts with `MicrosoftAuthManager` to get an `IAccount` and then calls
          `acquireTokenSilent` to obtain a valid access token.
        * Crucially handles `MsalUiRequiredException` from `acquireTokenSilent` by signaling the
          need for re-authentication (e.g., by returning null tokens, causing Ktor's auth to fail,
          which then propagates). The `AccountRepository` layer would then typically mark the
          account as `needsReauthentication`.
        * Returns a placeholder (e.g., account ID) for `BearerTokens.refreshToken` since MSAL
          manages its own refresh tokens.
    * `GraphApiHelper.kt`: Implements `MailApiService` for Microsoft Graph.
        * Uses a Ktor `HttpClient` configured with an `Auth` plugin that uses
          `MicrosoftKtorTokenProvider`.
        * Makes calls to Microsoft Graph API endpoints (e.g., `/me/mailFolders`, `/me/messages`).
        * Updated to support true cross-folder threading for conversation views by querying
          `/me/messages` filtered by `conversationId`.
    * `MicrosoftErrorMapper.kt`: Implements `ErrorMapperService` to map MSAL exceptions and Graph
      API errors to generic `MappedErrorDetails`.
    * `ActiveMicrosoftAccountHolder.kt`: A simple class (likely `@Singleton`) holding the ID of the
      currently active Microsoft account.
    * `di/BackendMicrosoftModule.kt` (and `BackendMicrosoftBindsModule.kt`):
        * Provides the Ktor `HttpClient` specifically for Microsoft Graph, configured with the
          `Auth` plugin and `MicrosoftKtorTokenProvider`.
        * Binds `MicrosoftAccountRepository` (if it's the direct `AccountRepository` impl for MS) or
          provides dependencies for it.
        * Provides `MicrosoftErrorMapper` for the `ErrorMapperService` map in `:data`.
    * `MicrosoftAuthenticatorService.kt`, `MicrosoftStubAuthenticator.kt`: Standard Android
      components required for integrating with `AccountManager` and defining a custom account type (
      `net.melisma.mail.MICROSOFT`).
* **Data Flow:**
    * `MicrosoftAccountRepository` (or `DefaultAccountRepository` delegating to it) calls
      `MicrosoftAuthManager` for auth operations.
    * `GraphApiHelper` is called by repositories in `:data`. It uses its Ktor client, which triggers
      `MicrosoftKtorTokenProvider` for tokens.
    * Data from Graph API is mapped to `:core-data` models.
* **Specific Patterns/Strategies:**
    * MSAL for all Microsoft authentication.
    * Ktor `Auth` plugin for automatic token injection and refresh attempts.
    * `AccountManager` for persisting account identifiers.

### 3.5. `:backend-google` Module

* **Purpose & Scope:** Handles all Google/Gmail specific logic, including AppAuth-based API
  authorization and Gmail API interactions.
* **Key Components & Classes:**
    * `AppAuthHelperService.kt`: Encapsulates all interactions with the AppAuth for Android library.
        * Builds `AuthorizationRequest` objects (using Android Client ID, redirect URI, PKCE).
        * Provides an `Intent` (`createAuthorizationRequestIntent`) for launching the authorization
          flow (Google's consent screen via Custom Tabs).
        * Exchanges authorization codes for `TokenResponse` (access, refresh, ID tokens) via
          `exchangeAuthorizationCode`.
        * Refreshes access tokens using stored refresh tokens (`refreshAccessToken`).
        * Revokes tokens (`revokeToken`) using Google's revocation endpoint via an unauthenticated
          Ktor client.
    * `GoogleTokenPersistenceService.kt`: Manages the secure storage of Google's `AuthState` (which
      includes access, refresh, and ID tokens).
        * Uses `AccountManager` to store the encrypted JSON representation of the `AuthState`.
        * Uses `SecureEncryptionService` (from `:core-data`) for encryption/decryption.
    * `GoogleKtorTokenProvider.kt`: Implements Ktor's `BearerTokenProvider`.
        * Retrieves the encrypted `AuthState` from `GoogleTokenPersistenceService`.
        * If the access token is expired (`AuthState.needsTokenRefresh`), it calls
          `appAuthHelperService.refreshAccessToken()`.
        * If refresh fails due to `invalid_grant` (an `AuthorizationException` type), it clears the
          invalid `AuthState` from persistence and signals the need for re-authentication (e.g., by
          throwing `GoogleNeedsReauthenticationException` or returning null tokens).
        * Returns a placeholder for `BearerTokens.refreshToken` as the full `AuthState` is managed
          separately.
    * `GmailApiHelper.kt`: Implements `MailApiService` for the Gmail API.
        * Uses a Ktor `HttpClient` configured with an `Auth` plugin that uses
          `GoogleKtorTokenProvider`.
        * Makes calls to Gmail API endpoints (e.g., `/users/me/labels`, `/users/me/messages`).
        * Handles `GoogleNeedsReauthenticationException` potentially thrown by the Ktor client (if
          the token provider signals it), mapping it to a `Result.failure`.
    * `GoogleErrorMapper.kt`: Implements `ErrorMapperService` to map AppAuth exceptions (
      `AuthorizationException`), token parsing errors (`DecodeException`), and Gmail API errors to
      generic `MappedErrorDetails`.
    * `ActiveGoogleAccountHolder.kt`: A simple class (likely `@Singleton`) holding the ID of the
      currently active Google account.
    * `di/BackendGoogleModule.kt` (and `BackendGoogleBindsModule.kt`):
        * Provides the Ktor `HttpClient` specifically for Gmail API, configured with the `Auth`
          plugin and `GoogleKtorTokenProvider`.
        * Provides an unauthenticated Ktor client for `AppAuthHelperService`'s `revokeToken` method.
        * Provides dependencies like `AppAuthHelperService`, `GoogleTokenPersistenceService`.
        * Provides `GoogleErrorMapper` for the `ErrorMapperService` map in `:data`.
    * `GoogleAuthenticatorService.kt`, `GoogleStubAuthenticator.kt`: Standard Android components for
      `AccountManager` integration and defining the custom account type (`net.melisma.mail.GOOGLE`).
* **Data Flow:**
    * `DefaultAccountRepository` calls `AppAuthHelperService` (for auth intents and token exchange)
      and `GoogleTokenPersistenceService` (to save/load `AuthState`).
    * `GmailApiHelper` is called by repositories in `:data`. It uses its Ktor client, which triggers
      `GoogleKtorTokenProvider` for tokens. `GoogleKtorTokenProvider` in turn uses
      `GoogleTokenPersistenceService` and `AppAuthHelperService` (for refresh).
    * Data from Gmail API is mapped to `:core-data` models.
* **Specific Patterns/Strategies:**
    * AppAuth for Android for Google OAuth 2.0 API authorization and token management.
    * `androidx.credentials.CredentialManager` (not explicitly detailed in these backend classes but
      orchestrated by `DefaultAccountRepository` or a similar higher-level service) is used for the
      *initial* Google Sign-In to get an ID token, simplifying user account selection. The AppAuth
      flow then uses this user identity to request API permissions.
    * Ktor `Auth` plugin for token injection and refresh.
    * `AccountManager` for persisting the entire `AuthState`.

## 4. Detailed Authentication & Authorization Flows

This section provides a step-by-step walkthrough of the authentication and authorization processes
for each provider.

### 4.1. Google OAuth Flow (CredentialManager + AppAuth)

1. **Sign-In Initiation (UI -> ViewModel -> `DefaultAccountRepository`)**:
    * User initiates Google sign-in.
    * ViewModel calls `AccountRepository.signIn("GOOGLE", activity, loginHint)`.
    * `DefaultAccountRepository` (or a dedicated `GoogleAuthCoordinator` it uses):
        * First, potentially uses `androidx.credentials.CredentialManager` with
          `GetSignInWithGoogleOption` (configured with a **Web Client ID**) to let the user pick a
          Google account on the device and get an ID Token. This simplifies account discovery and
          initial authentication.
        * Once an account/email is identified (and possibly a login hint obtained), it proceeds to
          the AppAuth flow for API authorization.
        * It calls `appAuthHelperService.buildAuthorizationRequest()` using the **Android Client ID
          **, specific redirect URI (e.g., `net.melisma.mail:/oauth2redirect`), and required API
          scopes (e.g., Gmail scopes, `openid`, `email`, `profile`, `offline_access`).
        * `appAuthHelperService.createAuthorizationRequestIntent()` returns an `Intent`.
        * `DefaultAccountRepository` emits `GenericAuthResult.UiActionRequired(intent)` via its
          `googleAuthResultChannel`.

2. **Authorization Grant (AppAuth UI Flow)**:
    * The `:app` module's `MainActivity` (observing the ViewModel) receives the `UiActionRequired`
      and launches the `Intent` using an `ActivityResultLauncher`.
    * AppAuth library opens a Custom Tab to Google's authorization endpoint.
    * User authenticates (if not already signed into the browser session for that account) and
      grants requested permissions.
    * Google redirects back to the app's registered redirect URI, caught by a specific `Activity` (
      e.g., `RedirectUriReceiverActivity` configured in the manifest).

3. **Token Exchange & Persistence (Redirect Activity -> `DefaultAccountRepository` -> Services)**:
    * The redirect `Activity` parses the `AuthorizationResponse` or `AuthorizationException` from
      the redirect `Intent`.
    * It then calls
      `AccountRepository.handleAuthenticationResult("GOOGLE", resultCode, dataIntent)` (likely via
      the ViewModel).
    * `DefaultAccountRepository`:
        * Receives the `AuthorizationResponse`.
        * Calls `appAuthHelperService.exchangeAuthorizationCode(authResponse)` (a suspend function).
          This performs the token exchange using AppAuth, sending the authorization code and PKCE
          verifier to Google's token endpoint.
        * Receives a `TokenResponse` (containing access token, refresh token, ID token, expiration
          time).
        * Optionally parses the ID Token again via `appAuthHelperService.parseIdToken()` for user
          details if needed here (though some info might have come from `CredentialManager` step).
        * Calls `googleTokenPersistenceService.saveTokens(accountId, email, ..., tokenResponse)`.
            * `GoogleTokenPersistenceService` creates an `AuthState` object, populates it with the
              `TokenResponse`, serializes it to JSON, encrypts it using `SecureEncryptionService`,
              and stores it in `AccountManager` associated with the user's account ID and the custom
              account type (`net.melisma.mail.GOOGLE`).
        * Updates `ActiveGoogleAccountHolder`.
        * Sends `GenericAuthResult.Success(account)` or `GenericAuthResult.Error` to the
          `googleAuthResultChannel`, completing the original `signIn` flow.

4. **Authenticated API Calls (Ktor Client -> `GoogleKtorTokenProvider`)**:
    * When a Ktor client in `GmailApiHelper` needs to make an authenticated call:
        * The Ktor `Auth` plugin invokes `GoogleKtorTokenProvider.getBearerTokens()`.
        * `GoogleKtorTokenProvider` retrieves the active Google account ID from
          `ActiveGoogleAccountHolder`.
        * It fetches the encrypted `AuthState` JSON from `GoogleTokenPersistenceService` and
          decrypts it.
        * **Token Refresh:** If `AuthState.needsTokenRefresh` is true (access token is expired or
          nearing expiry):
            * It calls `appAuthHelperService.refreshAccessToken(authState)`. `AppAuthHelperService`
              uses the refresh token from the `AuthState` to request a new access token from Google.
            * If refresh is successful, the `AuthState` is updated with the new tokens, and
              `GoogleTokenPersistenceService` is called to save the updated (encrypted) `AuthState`.
            * If refresh fails with an `invalid_grant` error (e.g., refresh token revoked),
              `GoogleKtorTokenProvider` clears the invalid `AuthState` via
              `GoogleTokenPersistenceService`, and throws `GoogleNeedsReauthenticationException` (or
              returns null tokens). This signals to the calling layer that re-login is required.
        * A valid access token is extracted from the `AuthState` and returned as `BearerTokens` to
          the Ktor `Auth` plugin. The `refreshToken` field in `BearerTokens` can be a placeholder as
          AppAuth manages the actual refresh token within the `AuthState`.

5. **Sign-Out (UI -> ViewModel -> `DefaultAccountRepository` -> Services)**:
    * User initiates sign-out for a Google account.
    * ViewModel calls `AccountRepository.signOut(googleAccount)`.
    * `DefaultAccountRepository`:
        * Retrieves the `AuthState` from `GoogleTokenPersistenceService`.
        * If a refresh token exists in `AuthState`, calls
          `appAuthHelperService.revokeToken(refreshToken)` to attempt revocation on Google's server.
        * Calls `googleTokenPersistenceService.clearTokens(accountId, removeAccount = true)` to
          delete the `AccountManager` entry and the encrypted `AuthState`.
        * Clears `ActiveGoogleAccountHolder` if it was the active account.
        * Emits `GenericSignOutResult.Success` or `Error`.

### 4.2. Microsoft OAuth Flow (MSAL)

1. **MSAL Initialization (`MicrosoftAuthManager`)**:
    * On app startup (or when `MicrosoftAuthManager` is first created), it initializes
      `IMultipleAccountPublicClientApplication` using the configuration from
      `R.raw.auth_config_msal` (provided via `AuthConfigProvider`). This config includes client ID,
      tenant ID, redirect URI (often a broker-compatible one), and authorities.

2. **Sign-In Initiation (UI ->
   ViewModel -> `DefaultAccountRepository` -> `MicrosoftAccountRepository` ->`MicrosoftAuthManager`)
   **:
    * User initiates Microsoft sign-in.
    * ViewModel calls `AccountRepository.signIn("MS", activity, loginHint)`.
    * `DefaultAccountRepository` delegates to `MicrosoftAccountRepository` (the instance qualified
      with `@MicrosoftRepo`).
    * `MicrosoftAccountRepository` calls `microsoftAuthManager.signInInteractive(activity, scopes)`.
        * `MicrosoftAuthManager` builds `AcquireTokenParameters` and calls MSAL's `acquireToken()`
          method.
        * MSAL handles the UI flow, which might involve opening a browser, a Custom Tab, or
          leveraging the Microsoft Authenticator app (broker) if installed. The redirect is handled
          internally by MSAL or the broker.
        * The result (`IAuthenticationResult`, `MsalException`, or cancellation) is delivered to the
          `AuthenticationCallback` provided to `AcquireTokenParameters`.
        * `MicrosoftAuthManager` wraps this result in an `AuthenticationResultWrapper` and emits it
          on the `Flow` returned by `signInInteractive`.
    * `MicrosoftAccountRepository` collects this `Flow`:
        * On `AuthenticationResultWrapper.Success(iAccount, iAuthResult)`:
            * It calls `microsoftAuthManager.saveAccountInfoToAccountManager(iAuthResult)` (which
              was adapted from the old persistence service logic). This stores account identifiers (
              like `IAccount.getId()`, username) and potentially the access/ID tokens (encrypted) in
              Android's `AccountManager`. MSAL itself securely manages its refresh tokens, often
              within the broker or its own encrypted storage.
            * Updates `ActiveMicrosoftAccountHolder`.
            * Returns `GenericAuthResult.Success(account)`.
        * On `Error` or `Cancelled`, returns the corresponding `GenericAuthResult`.

3. **Authenticated API Calls (Ktor Client -> `MicrosoftKtorTokenProvider`)**:
    * When a Ktor client in `GraphApiHelper` needs to make an authenticated call:
        * The Ktor `Auth` plugin invokes `MicrosoftKtorTokenProvider.getBearerTokens()`.
        * `MicrosoftKtorTokenProvider` retrieves the active Microsoft account ID from
          `ActiveMicrosoftAccountHolder`.
        * It calls `microsoftAuthManager.getAccount(accountId)` to get the `IAccount` object.
        * It then calls `microsoftAuthManager.acquireTokenSilent(iAccount, scopes)`.
            * `MicrosoftAuthManager` builds `AcquireTokenSilentParameters` and calls MSAL's
              `acquireTokenSilentAsync()`.
            * MSAL attempts to get a token silently from its cache or by using its refresh token (
              possibly via the broker).
            * The result is delivered to a `SilentAuthenticationCallback` and emitted as an
              `AuthenticationResultWrapper` on the `Flow`.
        * `MicrosoftKtorTokenProvider` collects this `Flow`:
            * On `AuthenticationResultWrapper.Success`, it extracts the access token and returns it
              in `BearerTokens`. The `refreshToken` field in `BearerTokens` is a placeholder.
            * If `acquireTokenSilent` results in an `MsalUiRequiredException` (wrapped in
              `AuthenticationResultWrapper.Error`), this indicates silent renewal failed and
              interactive sign-in is needed. The provider then signals this (e.g., returns null
              tokens), and `AccountRepository` should mark the account with
              `needsReauthentication = true`.
        * A valid access token is returned to the Ktor `Auth` plugin.

4. **Sign-Out (UI ->ViewModel -> `DefaultAccountRepository` -> `MicrosoftAccountRepository` ->
   `MicrosoftAuthManager`)**:
    * User initiates sign-out for a Microsoft account.
    * ViewModel calls `AccountRepository.signOut(microsoftAccount)`.
    * `DefaultAccountRepository` delegates to `MicrosoftAccountRepository`.
    * `MicrosoftAccountRepository`:
        * Gets the `IAccount` object corresponding to the `Account` model via
          `MicrosoftAuthManager`.
        * Calls `microsoftAuthManager.signOut(iAccount)`.
            * `MicrosoftAuthManager` calls MSAL's `removeAccount()`. MSAL clears its cached tokens
              for that account and signs the user out from the broker if applicable.
            * On successful removal by MSAL, `MicrosoftAuthManager` also calls
              `deleteAccountFromAccountManager(iAccount.getId())` to remove the persisted account
              identifiers from Android's `AccountManager`.
        * Clears `ActiveMicrosoftAccountHolder` if it was the active account.
        * Emits `GenericSignOutResult.Success` or `Error`.

## 5. UI Architecture (MVVM & Jetpack Compose)

* **Pattern:** MVVM is strictly followed in the `:app` module.
* **ViewModels (`androidx.lifecycle.ViewModel`):**
    * Located in `net.melisma.mail` package (e.g., `MainViewModel.kt`).
    * Responsible for holding and managing UI-related state (`MainScreenState`).
    * Expose state to Composables via `kotlinx.coroutines.flow.StateFlow`.
    * Fetch data from Repositories (defined in `:core-data`, implemented in `:data`).
    * Handle UI logic and user actions, delegating business logic to repositories.
    * Scoped to the lifecycle of a Composable screen or navigation graph.
* **Views (Jetpack Compose Composables):**
    * Located in `net.melisma.mail.ui` sub-packages.
    * Declarative UI defined using Kotlin functions.
    * Observe `StateFlow` from ViewModels using `collectAsStateWithLifecycle()`.
    * Stateless or stateful based on the complexity, with state often hoisted to the ViewModel or
      parent Composables.
* **State Management:**
    * `MainScreenState` (or similar data classes) represents the complete state for a given screen.
      It is immutable.
    * ViewModels update state by creating new instances of the state data class using
      `_uiState.update { currentState.copy(...) }`.
* **Navigation:**
    * Jetpack Navigation for Compose is used for navigating between different screens/Composables.
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

* **Unit Tests:** Focus on ViewModels, Repositories, Mappers, API Helpers, Authentication
  Components (`MicrosoftAuthManager`, `AppAuthHelperService`, Token Persistence services), and
  utility classes.
* **Integration Tests (JVM-based):** Verify interactions between collaborating components within a
  module or across closely related modules (e.g., ViewModel with Repository, Repository with API
  service and Error Mapper).
* **UI/E2E Tests (Future):** While not the immediate focus, end-to-end tests using Jetpack Compose
  testing APIs and Hilt will be essential for validating full user flows, including AppAuth's Custom
  Tab interactions.

### 6.3. Test Levels & Techniques

* **Unit Tests:**
    * **Tools:** JUnit 4/5, MockK (for mocking dependencies), `kotlinx-coroutines-test` (for testing
      coroutines and Flow), Turbine (for testing `Flow` emissions).
    * **Focus:** Isolate the class under test, mock its dependencies, and verify its logic, state
      changes, and interactions with mocks.
    * **Structure:** Typically follow AAA (Arrange, Act, Assert) pattern.
* **Integration Tests (JVM):**
    * **Tools:** JUnit 4/5, Hilt for providing real or partial implementations of dependencies where
      appropriate (or MockK for external boundaries).
    * **Focus:** Verify the contract between components. For example, ensure
      `DefaultAccountRepository` correctly processes data from a mocked `AppAuthHelperService` and
      `GoogleTokenPersistenceService`.

### 6.4. Key Areas for Testing

* **Authentication Flows:** Success, error, and cancellation paths for both Google and Microsoft
  sign-in/out, token refresh.
* **Data Repositories:** Correct data fetching, mapping, caching (once implemented), and error
  propagation.
* **ViewModels:** State updates in response to repository emissions or user actions, correct
  delegation to repositories.
* **Error Mappers:** Accurate mapping of provider-specific exceptions to generic error models.
* **API Helpers (`GraphApiHelper`, `GmailApiHelper`):** Correct request formation and response
  parsing.

### 6.5. Best Practices

* Tests should be readable, maintainable, and fast.
* Aim for good test coverage of business logic and critical paths.
* Tests should be independent and not rely on the state of other tests.

## 7. Setup, Build, and Development Environment

### 7.1. Prerequisites

* Latest stable version of Android Studio.
* Android SDK (ensure appropriate API levels are installed as per `build.gradle.kts` files).
* JDK (typically bundled with Android Studio).

### 7.2. Project Setup

1. **Clone the repository.**
2. **Open in Android Studio.**
3. **Sync Gradle:** Allow Android Studio to download all dependencies and sync the project. This
   will include libraries like MSAL, AppAuth, Ktor, Hilt, etc.
4. **Configuration (Client IDs, etc.):**
    * **Microsoft (MSAL):** Configuration is primarily in
      `app/src/main/res/raw/auth_config_msal.json`. Ensure this file contains the correct
      `client_id` and `redirect_uri` for your Azure AD app registration.
    * **Google (CredentialManager & AppAuth):**
        * **Web Client ID (for CredentialManager):** Used in `GoogleAuthManager.kt` (or similar) for
          `GetSignInWithGoogleOption`. This ID is typically obtained from Google Cloud Console for
          a "Web application" type OAuth client, even though it's used on Android.
        * **Android Client ID (for AppAuth):** Used in `AppAuthHelperService.kt`. This ID is
          obtained from Google Cloud Console for an "Android application" type OAuth client and is
          linked to your app's package name and SHA-1 signing certificate fingerprint. It's often
          provided via `BuildConfig`.
        * **Redirect URI (for AppAuth):** Must be configured in `AndroidManifest.xml` (e.g., using a
          custom scheme like `net.melisma.mail:/oauth2redirect`) and match the one registered in
          Google Cloud Console for your Android Client ID.
    * Ensure `buildConfigField` values for client IDs or redirect URIs in module-level
      `build.gradle.kts` files are correctly set up if used.

### 7.3. Build Commands

* **Build Project:** `./gradlew build` (or via Android Studio Build menu)
* **Clean Project:** `./gradlew clean`
* **Install Debug APK:** `./gradlew installDebug`
* **Run Unit Tests (All Modules):** `./gradlew testDebugUnitTest` (or per module:
  `./gradlew :<module-name>:testDebugUnitTest`)
* **Run Android Instrumented Tests (All Modules):** `./gradlew connectedDebugAndroidTest` (requires
  connected device/emulator)

### 7.4. Development Guidance

* **Branching Strategy:** Follow a standard Git flow (e.g., feature branches off `develop`, merge
  via PRs).
* **Code Style:** Adhere to Kotlin coding conventions. Use Android Studio's formatter.
* **Logging:** Use Timber for logging (`Timber.d()`, `Timber.e()`, etc.). Avoid raw
  `android.util.Log`.
* **Addressing `TODO`s:** Regularly review and address `TODO` comments. The `TODO.md` file tracks
  larger outstanding refactoring items.

This document provides a foundational understanding of the Melisma Mail application's architecture
and development practices. It should be treated as a living document and updated as the project
evolves. 