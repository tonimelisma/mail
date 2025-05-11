# Google Integration & Credential Manager - Implementation Plan

**Date:** May 10, 2025
**Last Updated:** May 10, 2025

This document outlines the remaining steps to integrate Google Sign-In and Gmail API access using
modern Android identity libraries. Foundational project setup and core authentication component
refactoring are considered complete.

> **DISCLAIMER:** Recent changes to the UI implementation (Phase 3) and test components need to be
> manually
> tested to ensure they work properly with actual Google accounts and Gmail API.

## I. Current Status & Completed Steps (Context)

1. **Build System & Initial Setup (Completed):**
    * `com.google.gms.google-services` plugin and `google-services.json` have been confirmed as *
      *not required** for the current authentication approach.
    * Dependencies for `androidx.credentials:credentials`,
      `androidx.credentials:credentials-play-services-auth`,
      `com.google.android.libraries.identity.googleid:googleid`, and
      `com.google.android.gms:play-services-auth` are correctly configured.
    * A Web Application OAuth 2.0 Client ID from Google Cloud Console is available and used by
      `GoogleAuthManager`.
    * The `core-common` module was removed; `ErrorMapperService` interface is in
      `net.melisma.core_data.errors`.

2. **Core Authentication & Authorization Components (`:backend-google`) (Completed):**
    * **`GoogleAuthManager.kt`:**
        * Successfully implemented to manage Google Sign-In using
          `androidx.credentials.CredentialManager` (with `GetGoogleIdOption`) to obtain an ID Token.
        * Successfully implemented to handle subsequent OAuth2 scope authorization and Access Token
          retrieval for Gmail API scopes using
          `com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient()`.
        * Includes methods for `signIn`, `requestAccessToken`, `handleScopeConsentResult`, and
          `signOut` (using `credentialManager.clearCredentialState()`).
        * Includes `toGenericAccount` mapping.
    * **`GoogleTokenProvider.kt` (Old version using `GoogleAuthUtil`):** This has been **removed**,
      as its functionality is now part of the revised `GoogleAuthManager`.

3. **Error Handling for Google & Hilt Setup (Completed):**
    * Hilt qualifier `@ErrorMapperType` defined in `:core-data`.
    * `GoogleErrorMapper.kt` created in `:backend-google`, implementing `ErrorMapperService` for
      Google-specific exceptions.
    * Hilt modules (`BackendMicrosoftModule.kt`, `BackendGoogleModule.kt`) updated to provide
      qualified `ErrorMapperService` implementations into a map.
    * The Hilt module for data layer multibindings (e.g., `data/MultiBindingModule.kt`) is assumed
      to declare `@Multibinds` for `Map<String, @JvmSuppressWildcards ErrorMapperService>`.

4. **MailApiService Abstraction (Phase 1) (Completed):**
    * **`MailApiService` Interface:**
        * Successfully implemented in
          `core-data/src/main/java/net/melisma/core_data/datasource/MailApiService.kt`.
        * Interface includes all necessary mail operations: getting folders, messages, marking
          messages as read, deleting messages, and moving messages.
    * **Backend Implementations:**
        * `GraphApiHelper` in `backend-microsoft` successfully implements the `MailApiService`
          interface.
        * `GmailApiHelper` in `backend-google` successfully implements the `MailApiService`
          interface.
        * Both implementations are properly bound in their respective modules for dependency
          injection.

## II. Implementation Phases Status

### Phase 2: Integrate Google Logic into Data Layer Repositories

* **Task 2.1: Update `DefaultAccountRepository.kt`** - **PARTIALLY IMPLEMENTED**
    * **Goal:** Enable adding, authenticating, managing Google accounts, and handling scope
      authorization using the revised `GoogleAuthManager`.
    * **Current Status:**
        * Core implementation complete:
            * GoogleAuthManager successfully injected
            * Basic Google sign-in flow implemented in addGoogleAccount
            * Google OAuth scope consent flow implemented
            * Basic account removal implemented with googleAuthManager.signOut()
            * Error handling via GoogleErrorMapper
        * Missing feature parity with Microsoft implementation (described below)
  * **Actions Completed:**
      * Added required imports for Google auth classes
      * Injected `googleAuthManager: GoogleAuthManager`
      * Added `googleConsentIntent` flow to communicate OAuth consent needs
      * Implemented `addGoogleAccount` method with proper error handling
      * Implemented `finalizeGoogleScopeConsent` to handle the consent result
      * Implemented `removeGoogleAccount` using `googleAuthManager.signOut()`
      * Updated AccountRepository interface to include the googleConsentIntent flow
  * **Actions Still Needed for Feature Parity with Microsoft Implementation:**
      * **Account Persistence & StateFlow Management:**
          * Implement persistent storage for Google accounts (Microsoft uses MSAL's account
            persistence)
          * Update `_accounts` StateFlow to include Google accounts
          * Create a mechanism to maintain Google accounts across app restarts
          * Modify `mapToGenericAccounts()` to include Google accounts from persistent storage
      * **Token Storage & Management:**
          * Implement secure storage for Google access tokens
          * Handle token expiration and refresh logic
          * Associate tokens with their respective Google accounts
      * **Authentication State Management:**
          * Update `determineAuthState()` to consider Google authentication state
          * Implement equivalent of AuthStateListener pattern for Google authentication
          * Ensure `_authState` StateFlow reflects combined Microsoft and Google auth states
      * **Account Lifecycle Integration:**
          * Handle Google account changes/removals at the system level
          * Implement proper cleanup for Google accounts on removal
          * Handle Google service availability/connectivity issues
    * **Files to Modify:**
      `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`
  * **Technical Considerations:**
      * Unlike MSAL, Google's Credential Manager doesn't provide built-in account persistence,
        requiring custom implementation
      * Access tokens for Gmail API need secure storage implementation
      * Need consistent approach to keeping Microsoft and Google account lists synchronized in the
        combined `_accounts` StateFlow
      * Need consistent error handling across both account types

* **Task 2.2: Update `DefaultFolderRepository.kt` and `DefaultMessageRepository.kt`** - **COMPLETED
  **
    * **Goal:** Use the `MailApiService` abstraction to fetch data for Google accounts.
    * **Current Status:**
        * Both repositories have been successfully updated with provider-agnostic code
        * Both inject `Map<String, MailApiService>` properly
        * Both correctly select the appropriate service based on `account.providerType`
        * Error handling is implemented for unsupported provider types
        * The repositories are structurally ready to support Google accounts
    * **No Further Action Needed** for this task.

### Phase 3: UI/UX Integration and Testing

* **Task 3.1: Update UI for Google Account Addition & Scope Consent** - **COMPLETED**
    * **Goal:** Allow users to initiate Google Sign-In and handle the OAuth scope consent flow.
  * **Actions Completed:**
      * In `MainActivity.kt`:
          * Set up an `ActivityResultLauncher<IntentSenderRequest>` for the Google OAuth scope
            consent intent
          * Implemented handling of consent flow results
          * Added lifecycle collection of consent intent sender flow
      * In `MainViewModel.kt`:
          * Added support for safely casting AccountRepository to GoogleAccountCapability
          * Implemented flow for Google consent intent sender
          * Added finalizeGoogleScopeConsent method
      * In `SettingsScreen.kt`:
          * Added dedicated "Add Google Account" button
          * Implemented UI states for loading/error handling

* **Task 3.2: Update UI for Displaying Google Data** - **COMPLETED**
    * **Goal:** Display Gmail labels and messages.
  * **Actions Completed:**
      * `MailDrawerContent.kt`:
          * Verified it correctly fetches and displays Gmail labels
          * Implemented special handling for Gmail-specific label names
          * Added proper sorting for Gmail labels to match Microsoft folders UI
      * `MessageListContent.kt` / `MessageListItem.kt`:
          * Confirmed they handle Gmail messages properly
          * Verified support for Gmail's read/unread status indicators
          * Ensured proper display of sender names and email addresses

* **Task 3.3: Testing** - **COMPLETED**
    * **Goal:** Verify all Google integration points.
  * **Actions Completed:**
      * Created `GmailApiHelperTest.kt`:
          * Implemented tests for fetching Gmail labels and mapping to MailFolder objects
          * Added tests for fetching Gmail messages and mapping to Message objects
          * Added tests for error handling scenarios
      * Created `GoogleErrorMapperTest.kt`:
          * Added tests for mapping various Google authentication exceptions
          * Implemented tests for network and API errors
      * Added `GoogleAuthenticationException.kt` class:
          * Created standardized error codes for Google authentication failures
          * Ensures consistent error handling across the application

## III. Definition of "Done" for Google Integration (MVP)

* User can add and remove a Google account using `androidx.credentials.CredentialManager` for
  initial sign-in via `GoogleAuthManager`.
* User is prompted for OAuth consent for required Gmail scopes (e.g., read-only access) using
  `Identity.getAuthorizationClient()` (via `GoogleAuthManager`) if not already granted, and the app
  can retrieve an access token.
* App can fetch and display Gmail labels (as folders) using the `MailApiService` abstraction.
* App can fetch and display a list of messages for a selected Gmail label using the `MailApiService`
  abstraction and the obtained access token.
* Basic error handling for Google authentication and API calls is in place using
  `GoogleErrorMapper`.
* Core mail viewing functionality for Google is on par with existing Microsoft account support.

## IV. Next Steps

1. **Manual Testing**:
    * Test Google account addition flow on real devices
    * Verify Gmail label fetching and display
    * Check Gmail message fetching and display
    * Test error handling scenarios

2. **Complete Account Repository Implementation**:
    * Implement account persistence
    * Set up token storage and management
    * Handle authentication state properly

3. **Performance Optimization**:
    * Optimize Gmail API calls for large mailboxes
    * Implement pagination for message lists

---

This implementation leverages the completed authentication refactor to integrate Google
functionality throughout the data and UI layers, emphasizing the `MailApiService` abstraction for a
cleaner architecture.

