# Google Integration & Credential Manager - Updated Implementation Plan

**Date:** May 10, 2025

This document outlines the remaining steps to integrate Google Sign-In and Gmail API access using
modern Android identity libraries. It assumes foundational project setup (dependencies for
`androidx.credentials`, `com.google.android.libraries.identity.googleid`, and
`com.google.android.gms:play-services-auth`; Web OAuth Client ID) is complete.

## II. Remaining Implementation Phases

### Phase 1: Finalize Authentication & Authorization Components

* **Task 1.1: Implement `GoogleAuthManager.kt` (Authentication & Authorization)**
    * **Goal:** Manage Google Sign-In using `androidx.credentials.CredentialManager` to obtain an ID
      Token, and subsequently use
      `com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient()` to obtain an
      OAuth2 Access Token for Gmail API scopes.
    * **Details (`GoogleAuthManager.kt`):**
        * **Sign-In (ID Token Retrieval):**
            * Inject `CredentialManager.create(context)`.
            * Implement a
              `suspend fun signIn(activity: Activity, filterByAuthorizedAccounts: Boolean): GoogleSignInResult`.
            * Inside `signIn`:
                * Generate and store a secure `nonce`.
                * Construct `com.google.android.libraries.identity.googleid.GetGoogleIdOption` using
                  its `Builder`. Configure it with:
                    * The Web Application Client ID (`setServerClientId`).
                    * The generated `nonce` (`setNonce`).
                    * `setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)`.
                    * `setAutoSelectEnabled(filterByAuthorizedAccounts)`.
                * Create `androidx.credentials.GetCredentialRequest`, adding the
                  `GetGoogleIdOption`.
                * Call `credentialManager.getCredential(activity, request)`.
                * Handle `GetCredentialResponse`:
                    * Verify `response.credential` is `CustomCredential` and its `type` is
                      `com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`.
                    * Parse `credential.data` using
                      `GoogleIdTokenCredential.createFrom(credential.data)`.
                    * Consume the `nonce`. (Optional: Perform strict client-side nonce validation by
                      parsing the ID token JWT).
                    * Return `GoogleSignInResult.Success(googleIdTokenCredential)` or appropriate
                      error/cancelled states (e.g., `UserCancellationException`,
                      `NoCredentialException`).
        * **Access Token Request (Scope Authorization):**
            * Implement
              `suspend fun requestAccessToken(activity: Activity, accountEmail: String?, scopes: List<String>): GoogleScopeAuthResult`.
            * Inside `requestAccessToken`:
                * Use `Identity.getAuthorizationClient(context)`.
                * Create `com.google.android.gms.auth.api.identity.AuthorizationRequest` using its
                  `Builder`, setting the requested `List<Scope>`.
                * Call `authorizationClient.authorize(authorizationRequest).await()`.
                * If `AuthorizationResult.hasResolution()` is true, return
                  `GoogleScopeAuthResult.ConsentRequired(pendingIntent)`.
                * If false, and `AuthorizationResult.accessToken` is available, return
                  `GoogleScopeAuthResult.Success(accessToken, grantedScopes)`.
                * Handle errors appropriately.
        * **Handle Scope Consent Result:**
            * Implement
              `suspend fun handleScopeConsentResult(intent: Intent?): GoogleScopeAuthResult`.
            * Inside, use `authorizationClient.getAuthorizationResultFromIntent(intent)`.
            * Extract and return `accessToken` via `GoogleScopeAuthResult.Success` or handle errors.
        * **Sign-Out:**
            * Implement `suspend fun signOut()`.
            * Call `credentialManager.clearCredentialState(ClearCredentialStateRequest())`.
            * Call `authorizationClient.signOut().await()`.
        * **Account Mapping:**
            * Implement `fun toGenericAccount(idTokenCredential: GoogleIdTokenCredential): Account`
              to map to `core_data.model.Account`.
    * **Files to Modify/Create:**
      `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleAuthManager.kt`.
    * **Note:** The previous `GoogleTokenProvider.kt` (using `GoogleAuthUtil.getToken()`) should be
      **removed or deprecated** as its functionality for access token retrieval is now part of
      `GoogleAuthManager` using the modern `AuthorizationClient`.

### Phase 2: Refactor Error Handling for Google & Hilt Setup

* **Task 2.1: Define Hilt Qualifier for Error Mappers**
    * **Goal:** Allow distinct error mappers for different backends.
    * **Action:** Add a new Hilt qualifier annotation, e.g., `@ErrorMapperType(val value: String)`,
      to `core-data/src/main/java/net/melisma/core_data/di/Qualifiers.kt`.
    * **Files to Modify:** `core-data/src/main/java/net/melisma/core_data/di/Qualifiers.kt`.

* **Task 2.2: Create `GoogleErrorMapper.kt`**
    * **Goal:** Implement `ErrorMapperService` for Google-specific exceptions.
    * **Action:** Create `GoogleErrorMapper.kt` in
      `backend-google/src/main/java/net/melisma/backend_google/errors/`.
    * **Details:**
        * Implement `ErrorMapperService`.
        * Handle exceptions from `GoogleAuthManager`:
            * `androidx.credentials.exceptions.GetCredentialException` subtypes (from `signIn`).
            * `ApiException` (from `handleScopeConsentResult`).
            * Other exceptions from `requestAccessToken`.
        * Map these to user-friendly error messages or states.
    * **Files to Create:**
      `backend-google/src/main/java/net/melisma/backend_google/errors/GoogleErrorMapper.kt`.

* **Task 2.3: Update Hilt Modules for Qualified ErrorMappers**
    * **Goal:** Provide qualified `ErrorMapperService` implementations for each backend and set up
      multibinding for a map.
    * **Action (Microsoft):**
        * Modify
          `backend-microsoft/src/main/java/net/melisma/backend_microsoft/di/BackendMicrosoftModule.kt`.
        * Change the existing `bindErrorMapperService` from a direct `@Binds` to an `@IntoMap`
          provision, qualified with `@ErrorMapperType("MS")` and `@StringKey("MS")`.
    * **Action (Google):**
        * Modify
          `backend-google/src/main/java/net/melisma/backend_google/di/BackendGoogleModule.kt`.
        * Add a new `@Provides` or `@Binds` method to provide `GoogleErrorMapper` as
          `ErrorMapperService`, qualified with `@ErrorMapperType("GOOGLE")` and
          `@StringKey("GOOGLE")` into a map.
    * **Action (Data Layer Multibindings):**
        * Ensure the Hilt module that declares `@Multibinds` for
          `Map<String, @JvmSuppressWildcards TokenProvider>` (if `TokenProvider` interface is still
          used for Microsoft) and `Map<String, @JvmSuppressWildcards MailApiService>` (expected to
          be `data/src/main/java/net/melisma/data/di/MultiBindingModule.kt`) also declares
          `@Multibinds abstract fun provideErrorMapperServices(): Map<String, @JvmSuppressWildcards ErrorMapperService>`.
    * **Files to Modify:** `BackendMicrosoftModule.kt`, `BackendGoogleModule.kt`, and the Hilt
      module for `@Multibinds` (e.g., `data/MultiBindingModule.kt`).

### Phase 3: Integrate Google Logic into `DefaultAccountRepository`

* **Task 3.1: Update `DefaultAccountRepository.kt`**
    * **Goal:** Enable adding, authenticating, managing Google accounts, and handling scope
      authorization.
    * **Actions:**
        * Inject `googleAuthManager: GoogleAuthManager` and
          `mapOfErrorMappers: Map<String, ErrorMapperService>`.
        * In `addAccount(providerType="GOOGLE", activity, ...)`:
            * Call `googleAuthManager.signIn(activity, filterByAuthorizedAccounts = false)`.
            * On `GoogleSignInResult.Success(idTokenCredential)`:
                * Call `googleAuthManager.toGenericAccount(idTokenCredential)` to create an
                  `Account` object.
                * Store this new `Account` (e.g., in-memory list, update `_accounts` StateFlow).
                * **Crucially, after successful sign-in, trigger the request for Gmail API access
                  tokens:**
                    * Call
                      `googleAuthManager.requestAccessToken(activity, newAccount.email, listOf("https://www.googleapis.com/auth/gmail.readonly", ...other_scopes...))`.
                    * Handle the `GoogleScopeAuthResult`:
                        * `GoogleScopeAuthResult.Success(accessToken, _)`: Store the access token
                          securely (e.g., in memory associated with the account for the session) or
                          indicate the account is fully ready.
                        * `GoogleScopeAuthResult.ConsentRequired(pendingIntent)`: Signal to the
                          ViewModel/UI to launch this `pendingIntent`.
                        * `GoogleScopeAuthResult.Error`: Handle error, potentially remove the
                          partially added account or mark it as needing authorization.
            * Handle `GoogleSignInResult.Error`, `Cancelled`, `NoCredentialsAvailable` by emitting
              user messages via the appropriate error mapper.
        * Modify `getAccessTokenForAccount(account, scopes, activity)` (or a similar method for
          refreshing/ensuring tokens):
            * If `account.providerType == "GOOGLE"`:
                * Call `googleAuthManager.requestAccessToken(activity, account.email, scopes)`.
                * If it returns `GoogleScopeAuthResult.ConsentRequired(pendingIntent)`, the
                  repository must signal to the ViewModel/UI to launch the intent. This requires a
                  mechanism (e.g., a `SharedFlow<IntentSender>` event or a specific state in
                  `AuthState`) for the UI to observe and act upon.
                * On `GoogleScopeAuthResult.Success`, return/store the token.
        * Implement a new method, e.g.,
          `suspend fun finalizeGoogleScopeConsent(account: Account, intent: Intent?, activity: Activity)`,
          to be called after the consent intent finishes.
            * Inside, call `googleAuthManager.handleScopeConsentResult(intent)`.
            * On `GoogleScopeAuthResult.Success(accessToken, _)`: Store the token, mark the account
              as fully authorized.
            * On `GoogleScopeAuthResult.Error`: Handle error.
        * In `removeAccount(account)` for Google:
            * Call `googleAuthManager.signOut()`.
            * Remove the account from the internal list and update `_accounts` StateFlow.
        * Update `_accounts` StateFlow management to correctly reflect
          addition/removal/authorization status of Google accounts.
    * **Files to Modify:**
      `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`.

### Phase 4: UI/UX Integration and Testing

* **Task 4.1: Update UI for Google Account Addition & Scope Consent**
    * **Goal:** Allow users to initiate Google Sign-In and handle the OAuth scope consent flow.
    * **Actions:**
        * In `MainActivity` (or relevant Composable host):
            * Set up an `ActivityResultLauncher<IntentSenderRequest>` for the Google OAuth scope
              consent intent.
        * `MainViewModel`:
            * Expose functions that call
              `DefaultAccountRepository.addAccount("GOOGLE", activity, ...)` and
              `DefaultAccountRepository.finalizeGoogleScopeConsent(...)`.
            * Observe signals/states from the repository indicating the need to launch the scope
              consent intent (the `IntentSender` itself) and trigger it via the launcher.
        * `SettingsScreen.kt`:
            * Add an "Add Google Account" button/option.
            * On click, call the ViewModel method to start the Google sign-in flow.
    * **Files to Modify:** `MainActivity.kt`, `MainViewModel.kt`, `ui/settings/SettingsScreen.kt`.

* **Task 4.2: Update UI for Displaying Google Data**
    * **Goal:** Display Gmail labels and messages.
    * **Actions:**
        * `MailDrawerContent.kt`: Ensure it correctly fetches and displays Gmail labels (via
          `DefaultFolderRepository`) alongside Microsoft folders.
        * `MessageListContent.kt` / `MessageListItem.kt`: Ensure they display Gmail messages
          correctly.
    * **Files to Modify:** Relevant UI Composable files.

* **Task 4.3: Testing**
    * **Goal:** Verify all Google integration points.
    * **Actions:**
        * Write/update unit tests for the revised `GoogleAuthManager`, `GoogleErrorMapper`, and
          Google-specific paths in `DefaultAccountRepository`.
        * Update existing integration tests affected by Hilt map/multibinding changes.
        * Perform thorough end-to-end manual testing of the Google sign-in flow, scope consent, mail
          fetching, and basic actions.
    * **Files to Modify/Create:** Test files in `src/test` directories of affected modules.

## III. Definition of "Done" for Google Integration (MVP)

* User can add and remove a Google account using `androidx.credentials.CredentialManager` for
  initial sign-in.
* User is prompted for OAuth consent for required Gmail scopes (e.g., read-only access) using
  `Identity.getAuthorizationClient()` if not already granted, and the app can retrieve an access
  token.
* App can fetch and display Gmail labels (as folders).
* App can fetch and display a list of messages for a selected Gmail label using the obtained access
  token.
* Basic error handling for Google authentication and API calls is in place using
  `GoogleErrorMapper`.
* Core mail viewing functionality is on par with existing Microsoft account support.

---

This updated plan focuses on using `androidx.credentials.CredentialManager` for initial
authentication and `Identity.getAuthorizationClient` for subsequent scope authorization and access
token retrieval, aligning with modern best practices.

