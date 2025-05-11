# Google Integration & Credential Manager - Implementation Plan

**Date:** May 10, 2025

This document outlines the remaining steps to integrate Google Sign-In and Gmail API access using
modern Android identity libraries. Foundational project setup and core authentication component
refactoring are considered complete.

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

## II. Remaining Implementation Phases

The primary focus is now on integrating the refactored Google authentication and authorization logic
into the data layer and UI.

### Phase 2: Integrate Google Logic into Data Layer Repositories

* **Task 2.1: Update `DefaultAccountRepository.kt`** - **NOT IMPLEMENTED**
    * **Goal:** Enable adding, authenticating, managing Google accounts, and handling scope
      authorization using the revised `GoogleAuthManager`.
    * **Current Status:**
        * Placeholder code exists for Google accounts, but no actual implementation
        * GoogleAuthManager is not injected (commented out with TODO notes)
        * No implementation for Google authentication flow
        * No implementation for Google scope consent handling
    * **Actions Still Needed:**
        * Inject `googleAuthManager: GoogleAuthManager` and
          `mapOfErrorMappers: Map<String, ErrorMapperService>`.
        * In `addAccount(providerType="GOOGLE", activity, ...)`:
            * Call `googleAuthManager.signIn(activity, filterByAuthorizedAccounts = false)`.
            * On `GoogleSignInResult.Success(idTokenCredential)`:
                * Call `googleAuthManager.toGenericAccount(idTokenCredential)` to create an
                  `Account` object.
                * Store this new `Account`.
                * Trigger
                  `googleAuthManager.requestAccessToken(activity, newAccount.id /* or email if available & preferred */, listOf("https://www.googleapis.com/auth/gmail.readonly", ...other_scopes...))`.
                * Handle `GoogleScopeAuthResult`:
                    * `Success(accessToken, _)`: Store token (e.g., in-memory with account session),
                      mark account as ready.
                    * `ConsentRequired(pendingIntent)`: Signal ViewModel/UI to launch
                      `pendingIntent`.
                    * `Error`: Handle error via mapper, potentially clean up partially added
                      account.
          * Handle `GoogleSignInResult.Error`, `Cancelled`, `NoCredentialsAvailable` using the "
            GOOGLE" error mapper.
        * Implement
          `suspend fun finalizeGoogleScopeConsent(account: Account, intent: Intent?, activity: Activity)`:
            * Call `googleAuthManager.handleScopeConsentResult(intent)`.
            * Update account state based on `GoogleScopeAuthResult`.
        * In `removeAccount(account)` for Google:
            * Call `googleAuthManager.signOut()`.
          * Update `_accounts` StateFlow.
        * Ensure `_accounts` StateFlow correctly reflects Google account states (e.g.,
          needs_consent, authorized).
    * **Files to Modify:**
      `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`.

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

* **Task 3.1: Update UI for Google Account Addition & Scope Consent**
    * **Goal:** Allow users to initiate Google Sign-In and handle the OAuth scope consent flow.
    * **Actions:**
        * In `MainActivity` (or relevant Composable host): Set up an
          `ActivityResultLauncher<IntentSenderRequest>` for the Google OAuth scope consent intent.
        * `MainViewModel`:
            * Expose functions that call
              `DefaultAccountRepository.addAccount("GOOGLE", activity, ...)` and
              `DefaultAccountRepository.finalizeGoogleScopeConsent(...)`.
            * Observe signals/states from the repository indicating the need to launch the scope
              consent intent (the `IntentSender` itself) and trigger it via the launcher.
        * `SettingsScreen.kt`: Add an "Add Google Account" button/option.
    * **Files to Modify:** `MainActivity.kt`, `MainViewModel.kt`, `ui/settings/SettingsScreen.kt`.

* **Task 3.2: Update UI for Displaying Google Data**
    * **Goal:** Display Gmail labels and messages.
    * **Actions:**
        * `MailDrawerContent.kt`: Ensure it correctly fetches and displays Gmail labels (via
          `DefaultFolderRepository`).
        * `MessageListContent.kt` / `MessageListItem.kt`: Ensure they display Gmail messages
          correctly.
    * **Files to Modify:** Relevant UI Composable files.

* **Task 3.3: Testing**
    * **Goal:** Verify all Google integration points.
    * **Actions:**
        * Write/update unit tests for the revised `GoogleAuthManager`, `GoogleErrorMapper`, and
          Google-specific paths in `DefaultAccountRepository`, `DefaultFolderRepository`,
          `DefaultMessageRepository`.
        * Test `MailApiService` implementations (`GmailApiHelper`, `GraphApiHelper`).
        * Perform thorough end-to-end manual testing of the Google sign-in flow, scope consent, mail
          fetching, and basic actions.
    * **Files to Modify/Create:** Test files in `src/test` directories of affected modules.

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

---

This plan focuses on leveraging the completed authentication refactor to integrate Google
functionality throughout the data and UI layers, emphasizing the `MailApiService` abstraction for a
cleaner architecture.

