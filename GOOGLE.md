# Google Integration - Implementation Plan (AppAuth Strategy) - Revised

**Date:** May 11, 2025
**Last Updated:** May 11, 2025 (Based on code review and rework plan)

**Strategy Revision:** This plan uses **AppAuth for Android** for Google API authorization and token
management, and `androidx.credentials.CredentialManager` for initial Google Sign-In (ID Token).

**Objective:** This document outlines the **remaining and rework** steps to fully integrate Google
Sign-In and Gmail API access using the AppAuth strategy. Foundational components like
`GoogleAuthManager` (for ID token), `AppAuthHelperService`, `SecureEncryptionService`, and
`GoogleTokenPersistenceService` have been implemented. This plan focuses on correctly integrating
these pieces into the data and networking layers and completing the UI and testing aspects.

> **DISCLAIMER:** UI implementation and test components need to be manually tested to ensure they
> work properly with actual Google accounts and Gmail API using the AppAuth flow. Ensure all Client
> IDs (Web and Android) and Redirect URIs are correctly configured in your Google Cloud Console and
> match the values used in the code. The Web Client ID for `CredentialManager` is currently
`326576675855-6vc6rrjhijjfch6j6106sd5ui2htbh61.apps.googleusercontent.com`. You will need your
> specific **Android Client ID** and **Redirect URI** for the AppAuth steps below.

## II. Remaining Implementation & Rework Plan

---
### **Phase 1 (Rework & New): Data Layer Integration for AppAuth (`DefaultAccountRepository`)**
---

**Context for Rework:**
The `DefaultAccountRepository` is a critical component for managing user accounts. Currently, its
`addGoogleAccount` method does **not** use the new AppAuth flow (`AppAuthHelperService` and
`GoogleTokenPersistenceService`). Instead, it incorrectly uses an older mechanism (
`GoogleAuthManager.requestAccessToken()`) that was meant to be replaced by the AppAuth strategy.
This means the application isn't correctly performing the OAuth 2.0 authorization code grant flow
with PKCE for Google, nor is it storing the AppAuth-obtained tokens.

Additionally, the `removeGoogleAccount` method is incomplete and does not clear tokens from
`GoogleTokenPersistenceService` or attempt server-side token revocation.

**Task 1.1: Rework `DefaultAccountRepository` for Full AppAuth Integration**

* **Goal:** Modify `DefaultAccountRepository.kt` to correctly use `GoogleAuthManager` (for initial
  ID token via Credential Manager), then `AppAuthHelperService` (for the AppAuth authorization code
  flow), and `GoogleTokenPersistenceService` (to save/manage tokens obtained via AppAuth). Ensure
  proper sign-out.

* **Files to Modify:**
    * `data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt`

* **Detailed Steps:**

    1. **Update Dependencies in `DefaultAccountRepository`:**
        * Ensure `AppAuthHelperService` and `GoogleTokenPersistenceService` are injected into the
          constructor.
            ```kotlin
            // data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
            import net.melisma.backend_google.auth.AppAuthHelperService // Add this import
            import net.melisma.backend_google.auth.GoogleTokenPersistenceService // Add this import
            import android.content.Intent // Add this import
            import android.net.Uri // Add this import for Uri.parse()

            // ... other imports

            @Singleton
            class DefaultAccountRepository @Inject constructor(
                private val microsoftAuthManager: MicrosoftAuthManager,
                private val googleAuthManager: GoogleAuthManager, // For initial ID token
                private val appAuthHelperService: AppAuthHelperService, // << ADD THIS
                private val googleTokenPersistenceService: GoogleTokenPersistenceService, // << ADD THIS
                @ApplicationScope private val externalScope: CoroutineScope,
                private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
            ) : AccountRepository, AuthStateListener
                // ... (rest of the class)
            ```

    2. **Implement Communication for AppAuth Intent Launching:**
        * The `DefaultAccountRepository` (a singleton) cannot directly launch an `Activity` (like
          the AppAuth Custom Tab). We need a way to pass the `Intent` created by
          `AppAuthHelperService` to the UI (ViewModel/Activity) to be launched.
        * Add a `SharedFlow` to `DefaultAccountRepository` that will emit the `Intent` for AppAuth.
            ```kotlin
            // data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt

            // At the top of the class, with other StateFlows/SharedFlows:
            private val _appAuthAuthorizationIntent = MutableSharedFlow<Intent?>(
                replay = 0, // No replay needed
                extraBufferCapacity = 1, // Buffer one intent
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            val appAuthAuthorizationIntent: Flow<Intent?> = _appAuthAuthorizationIntent.asSharedFlow()

            // Variables to temporarily hold user details during the multi-step auth flow
            private var pendingGoogleAccountId: String? = null
            private var pendingGoogleEmail: String? = null // Store email if available from ID token
            private var pendingGoogleDisplayName: String? = null // Store display name if available
            ```

    3. **Rework `addGoogleAccount` Method:**
        * This method will now initiate the Credential Manager sign-in. If successful, it checks for
          existing valid tokens. If no valid tokens, it prepares and emits the AppAuth authorization
          intent.
            ```kotlin
            // data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt

            // Modify the existing addGoogleAccount method
            private suspend fun addGoogleAccount(activity: Activity, scopes: List<String>) {
                Log.d(TAG, "addGoogleAccount (AppAuth Flow): Initiating for scopes: $scopes")
                val errorMapper = getErrorMapperForProvider("GOOGLE")
                if (errorMapper == null) {
                    Log.e(TAG, "addGoogleAccount: Google Error handler not found.")
                    tryEmitMessage("Internal error: Google Error handler not found.")
                    _isLoadingAccountAction.value = false
                    return
                }

                _isLoadingAccountAction.value = true // Indicate loading state

                // Step 1: Initial Sign-In with CredentialManager to get ID Token
                when (val signInResult = googleAuthManager.signInWithGoogle(activity)) {
                    is GoogleSignInResult.Success -> {
                        val idTokenCredential = signInResult.idTokenCredential
                        Log.i(TAG, "Google Sign-In (CredentialManager) successful. User ID: ${idTokenCredential.id}")

                        // Store details temporarily for the AppAuth flow
                        pendingGoogleAccountId = idTokenCredential.id
                        pendingGoogleEmail = idTokenCredential.email // May be null
                        pendingGoogleDisplayName = idTokenCredential.displayName

                        // Step 2: Check if valid tokens already exist for this account
                        val existingTokens = googleTokenPersistenceService.getTokens(pendingGoogleAccountId!!)
                        // Check if token exists and is not expired (expiresIn == 0L means no expiry info or non-expiring refresh token scenario)
                        // Add a small buffer (e.g., 5 minutes) to expiry check if desired: System.currentTimeMillis() < (existingTokens.expiresIn - 300000)
                        if (existingTokens != null && (existingTokens.expiresIn == 0L || existingTokens.expiresIn > System.currentTimeMillis())) {
                            Log.i(TAG, "Valid AppAuth tokens already exist for account ${pendingGoogleAccountId}. Finalizing account setup.")
                            val account = Account(
                                id = pendingGoogleAccountId!!,
                                username = pendingGoogleDisplayName ?: pendingGoogleEmail ?: "Google User",
                                providerType = "GOOGLE"
                            )
                            updateAccountsListWithNewAccount(account) // Ensure this adds to _accounts StateFlow
                            tryEmitMessage("Google account '${account.username}' is already configured.")
                            _isLoadingAccountAction.value = false
                            // Reset pending state as we are done for this account
                            resetPendingGoogleAccountState()
                            return
                        }
                        Log.i(TAG, "No valid AppAuth tokens found for ${pendingGoogleAccountId}. Proceeding with AppAuth flow.")

                        // Step 3: Initiate AppAuth Authorization Code Flow
                        try {
                            // IMPORTANT: Replace with your actual Android Client ID from Google Cloud Console
                            val androidClientId = "YOUR_ANDROID_OAUTH_CLIENT_ID"
                            // This redirect URI must match exactly what's configured in Google Cloud Console for your Android Client ID
                            // and in your AndroidManifest.xml for RedirectUriReceiverActivity.
                            val redirectUri = Uri.parse("net.melisma.mail:/oauth2redirect") // Example, ensure it matches manifest placeholder

                            val requiredScopesString = scopes.joinToString(" ").ifBlank { AppAuthHelperService.GMAIL_SCOPES }

                            Log.d(TAG, "Requesting AppAuth intent for Client ID: $androidClientId, Redirect URI: $redirectUri, Scopes: $requiredScopesString")

                            // AppAuthHelperService creates the request. The ViewModel/Activity will get this intent and launch it.
                            val authIntent = appAuthHelperService.initiateAuthorizationRequest(
                                activity = activity, // Note: AppAuthHelperService needs activity context for launching Custom Tab.
                                                         // If repo is true singleton, activity context can be problematic.
                                                         // Alternative: AppAuthHelperService.buildAuthorizationRequest() returns request,
                                                         // ViewModel gets it, then Activity uses AuthorizationService.getAuthorizationRequestIntent()
                                clientId = androidClientId,
                                redirectUri = redirectUri,
                                scopes = requiredScopesString
                            )
                            _appAuthAuthorizationIntent.tryEmit(authIntent) // Emit intent for UI to launch
                            // Message for user
                            tryEmitMessage("Please follow the prompts to authorize your Google account.")
                            // isLoadingAccountAction will be set to false after handling the AppAuth redirect result.
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to initiate AppAuth authorization request", e)
                            tryEmitMessage("Error starting Google authorization: ${errorMapper.mapAuthExceptionToUserMessage(e)}")
                            _isLoadingAccountAction.value = false
                            resetPendingGoogleAccountState()
                        }
                    }
                    is GoogleSignInResult.Error -> {
                        Log.e(TAG, "Google Sign-In (CredentialManager) error: ${signInResult.exception.message}", signInResult.exception)
                        tryEmitMessage("Error adding Google account: ${errorMapper.mapAuthExceptionToUserMessage(signInResult.exception)}")
                        _isLoadingAccountAction.value = false
                        resetPendingGoogleAccountState()
                    }
                    is GoogleSignInResult.Cancelled -> {
                        Log.d(TAG, "Google Sign-In (CredentialManager) was cancelled.")
                        tryEmitMessage("Google account addition cancelled.")
                        _isLoadingAccountAction.value = false
                        resetPendingGoogleAccountState()
                    }
                    is GoogleSignInResult.NoCredentialsAvailable -> {
                        Log.d(TAG, "No Google credentials available for CredentialManager sign-in.")
                        tryEmitMessage("No Google accounts found on this device. Please add one via device settings.")
                        _isLoadingAccountAction.value = false
                        resetPendingGoogleAccountState()
                    }
                }
            }

            private fun resetPendingGoogleAccountState() {
                pendingGoogleAccountId = null
                pendingGoogleEmail = null
                pendingGoogleDisplayName = null
            }

            // Helper to update the accounts list (you might have a similar existing method)
            private fun updateAccountsListWithNewAccount(newAccount: Account) {
                val currentList = _accounts.value.toMutableList()
                currentList.removeAll { it.id == newAccount.id && it.providerType == newAccount.providerType } // Avoid duplicates
                currentList.add(newAccount)
                _accounts.value = currentList // Update the StateFlow
                Log.d(TAG, "Account list updated with: ${newAccount.username}")
            }
            ```
        * **Important Security Note:** The `androidClientId` should **not** be hardcoded like "
          YOUR_ANDROID_OAUTH_CLIENT_ID". Store it securely, perhaps retrieve from `BuildConfig` or a
          similar configuration mechanism. The redirect URI must be *exactly* as configured in your
          Google Cloud Console for that Android Client ID.

    4. **Create `finalizeGoogleAccountSetupWithAppAuth` Method:**
        * This new method will be called after the AppAuth flow completes (i.e., after the user
          interacts with the Custom Tab and is redirected back to `RedirectUriReceiverActivity`).
          The `Activity` handling the result of the AppAuth redirect will pass the `Intent` data to
          the `ViewModel`, which then calls this repository method.
            ```kotlin
            // data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt

            suspend fun finalizeGoogleAccountSetupWithAppAuth(intentData: Intent) {
                val errorMapper = getErrorMapperForProvider("GOOGLE")
                if (errorMapper == null) {
                    Log.e(TAG, "finalizeGoogleAccountSetupWithAppAuth: Google Error handler not found.")
                    tryEmitMessage("Internal error: Google Error handler not found.")
                    _isLoadingAccountAction.value = false
                    resetPendingGoogleAccountState()
                    return
                }

                val currentAccountId = pendingGoogleAccountId
                if (currentAccountId == null) {
                    Log.e(TAG, "finalizeGoogleAccountSetupWithAppAuth called but no pendingGoogleAccountId was set.")
                    tryEmitMessage("Error completing Google setup: Session data missing.")
                    _isLoadingAccountAction.value = false
                    resetPendingGoogleAccountState() // Clean up
                    return
                }

                Log.d(TAG, "finalizeGoogleAccountSetupWithAppAuth: Processing AppAuth redirect for account ID: $currentAccountId")
                _isLoadingAccountAction.value = true // Start loading

                val authResponse = appAuthHelperService.handleAuthorizationResponse(intentData)
                if (authResponse == null) {
                    val appAuthError = appAuthHelperService.lastError.value ?: "Unknown AppAuth authorization error."
                    Log.e(TAG, "AppAuth authorization response was null or error: $appAuthError")
                    tryEmitMessage("Google authorization failed: $appAuthError")
                    _isLoadingAccountAction.value = false
                    resetPendingGoogleAccountState() // Clean up
                    return
                }

                // Step 4: Exchange Authorization Code for Tokens
                try {
                    Log.d(TAG, "Exchanging authorization code for tokens via AppAuthHelperService...")
                    val tokenResponse = appAuthHelperService.performTokenRequest(authResponse)
                    Log.i(TAG, "Token exchange successful. Access token received: ${tokenResponse.accessToken != null}")

                    // Step 5: Persist Tokens Securely
                    val success = googleTokenPersistenceService.saveTokens(
                        accountId = currentAccountId,
                        tokenResponse = tokenResponse, // Pass the raw TokenResponse to save all details
                        email = pendingGoogleEmail,
                        displayName = pendingGoogleDisplayName
                    )

                    if (success) {
                        val newAccount = Account(
                            id = currentAccountId,
                            username = pendingGoogleDisplayName ?: pendingGoogleEmail ?: "Google User ($currentAccountId)",
                            providerType = "GOOGLE"
                        )
                        updateAccountsListWithNewAccount(newAccount)
                        tryEmitMessage("Google account '${newAccount.username}' successfully added and configured!")
                        Log.i(TAG, "Google account setup complete with AppAuth tokens for $currentAccountId.")
                    } else {
                        Log.e(TAG, "Failed to save Google tokens to GoogleTokenPersistenceService for $currentAccountId.")
                        tryEmitMessage("Critical error: Failed to save Google account credentials securely.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during token exchange or saving tokens for $currentAccountId", e)
                    tryEmitMessage("Error finalizing Google account setup: ${errorMapper.mapAuthExceptionToUserMessage(e)}")
                } finally {
                    _isLoadingAccountAction.value = false
                    resetPendingGoogleAccountState() // Clean up pending state
                }
            }
            ```

    5. **Rework `removeGoogleAccount` for Comprehensive Sign-Out:**
        * Ensure it calls `GoogleTokenPersistenceService.clearTokens` and attempts server-side token
          revocation.
            ```kotlin
            // data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt

            // Modify the existing removeGoogleAccount method
            private suspend fun removeGoogleAccount(account: Account) {
                Log.d(TAG, "removeGoogleAccount (AppAuth Flow): Attempting for account: ${account.username} (ID: ${account.id})")
                val errorMapper = getErrorMapperForProvider("GOOGLE")
                if (errorMapper == null) {
                    Log.e(TAG, "removeGoogleAccount: Google Error handler not found.")
                    tryEmitMessage("Internal error: Google Error handler not found.")
                    _isLoadingAccountAction.value = false
                    return
                }

                _isLoadingAccountAction.value = true
                var message = "Google account '${account.username}' removed." // Default success message

                try {
                    // Step 1: Attempt to revoke tokens on server (Best effort)
                    // AppAuth library itself doesn't have a high-level revoke utility for Google.
                    // This typically involves a direct HTTPS POST/GET to Google's revocation endpoint.
                    // Example: POST to [https://oauth2.googleapis.com/revoke?token=TOKEN_TO_REVOKE](https://oauth2.googleapis.com/revoke?token=TOKEN_TO_REVOKE)
                    // You'd need to fetch the access or refresh token from GoogleTokenPersistenceService first.
                    // This is an advanced step; for now, we'll focus on local cleanup.
                    // val tokens = googleTokenPersistenceService.getTokens(account.id)
                    // tokens?.accessToken?.let { /* TODO: Implement appAuthHelperService.revokeToken(it) or direct Ktor call */ }
                    // tokens?.refreshToken?.let { /* TODO: Implement appAuthHelperService.revokeToken(it) or direct Ktor call */ }
                    Log.d(TAG, "Server-side token revocation (TODO) for ${account.id}")


                    // Step 2: Clear local tokens from AccountManager and remove the account entry
                    val clearedLocally = googleTokenPersistenceService.clearTokens(account.id, removeAccount = true)
                    if (clearedLocally) {
                        Log.i(TAG, "Local Google tokens and account entry cleared for ${account.id}.")
                    } else {
                        Log.w(TAG, "Failed to clear all local Google tokens/account for ${account.id}.")
                        message = "Google account '${account.username}' removed, but some local data might persist."
                    }

                    // Step 3: Clear CredentialManager sign-in state for this app
                    // This helps ensure user isn't automatically signed back in via CredentialManager's "one-tap"
                    // if they add the same account again.
                    googleAuthManager.signOut()
                    Log.i(TAG, "CredentialManager state cleared for Google Sign-Out.")

                    // Step 4: Update internal accounts list in the repository
                    val currentList = _accounts.value.toMutableList()
                    currentList.removeAll { it.id == account.id && it.providerType == "GOOGLE" }
                    _accounts.value = currentList
                    Log.i(TAG, "Account ${account.username} removed from repository's list.")

                } catch (e: Exception) {
                    Log.e(TAG, "Error removing Google account ${account.id}", e)
                    message = "Error removing Google account: ${errorMapper.mapAuthExceptionToUserMessage(e)}"
                } finally {
                    tryEmitMessage(message)
                    _isLoadingAccountAction.value = false
                }
            }
            ```

    6. **Remove Obsolete `GoogleAccountCapability` and Related Code:**
        * The `GoogleAccountCapability` interface and its `googleConsentIntent` Flow /
          `finalizeGoogleScopeConsent` method in `DefaultAccountRepository` were tied to the old
          `Identity.getAuthorizationClient()` flow. Since we're moving to AppAuth fully orchestrated
          with `AppAuthHelperService`, this specific capability interface as defined is no longer
          suitable.
        * **Action:**
            * Remove `GoogleAccountCapability` from the interfaces implemented by
              `DefaultAccountRepository`.
            * Remove the `override val googleConsentIntent: Flow<IntentSender?>` and
              `override suspend fun finalizeGoogleScopeConsent(...)` methods from
              `DefaultAccountRepository`. The new `_appAuthAuthorizationIntent` and
              `finalizeGoogleAccountSetupWithAppAuth` handle the AppAuth intent flow.

---
### **Phase 2 (Rework & New): Ktor Client HTTP Authentication (`BackendGoogleModule`)**
---

**Context for Rework:**
The Ktor `HttpClient` for Google (provided in `BackendGoogleModule.kt`) currently lacks an
authentication mechanism. The `Auth` plugin, which automatically attaches access tokens to requests
and handles token refresh, was not successfully integrated due to build errors (`HISTORY.MD`).
Without this, `GmailApiHelper` cannot make authenticated API calls unless it manually adds tokens to
every request and handles 401s itself, which is complex and error-prone.

**Task 2.1: Implement Ktor `Auth` Plugin with Bearer Tokens for Google**

* **Goal:** Configure the Ktor `HttpClient` for Google to use the `Auth` plugin. This plugin will
  automatically:
    1. Load the access token from `GoogleTokenPersistenceService` via a `GoogleTokenProvider`.
    2. Attach it as a Bearer token to outgoing requests to Gmail API.
    3. Detect 401 (Unauthorized) responses.
    4. Trigger an access token refresh using the refresh token (via `AppAuthHelperService` and
       `GoogleTokenPersistenceService`).
    5. Retry the original request with the new token.

* **Files to Modify:**
    * `backend-google/src/main/java/net/melisma/backend_google/di/BackendGoogleModule.kt`
* **New Files to Create (Recommended):**
    * `backend-google/src/main/java/net/melisma/backend_google/auth/GoogleTokenProvider.kt` (to
      encapsulate token loading/refresh logic for Ktor)
    * `backend-google/src/main/java/net/melisma/backend_google/auth/ActiveGoogleAccountHolder.kt` (
      or similar mechanism to get current user's ID)

* **Detailed Steps:**

    1. **(Optional but Recommended) Create `ActiveGoogleAccountHolder`:**
        * The Ktor client needs to know *which* Google account's tokens to use if multiple accounts
          are supported. This service/class will hold the ID of the currently active/selected Google
          account.
            ```kotlin
            // backend-google/src/main/java/net/melisma/backend_google/auth/ActiveGoogleAccountHolder.kt
            package net.melisma.backend_google.auth

            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow
            import javax.inject.Inject
            import javax.inject.Singleton

            @Singleton
            class ActiveGoogleAccountHolder @Inject constructor() {
                // This could be backed by SharedPreferences or another persistent store
                private val _activeAccountId = MutableStateFlow<String?>(null)
                val activeAccountId: StateFlow<String?> = _activeAccountId

                fun setActiveAccountId(accountId: String?) {
                    _activeAccountId.value = accountId
                    // Persist this choice if needed, so it survives app restarts
                }

                // This method might be called from non-suspend context if Ktor's loadTokens
                // needs it synchronously at startup, but ideally loadTokens is fully async.
                // For use in Ktor's suspendable loadTokens/refreshTokens, it's fine.
                fun getActiveAccountIdValue(): String? = _activeAccountId.value
            }
            ```
        * **Provide it via Hilt:**
            ```kotlin
            // backend-google/src/main/java/net/melisma/backend_google/di/BackendGoogleModule.kt
            // Add to existing module or a new AuthUtilModule
            // @Provides
            // @Singleton
            // fun provideActiveGoogleAccountHolder(): ActiveGoogleAccountHolder {
            //     return ActiveGoogleAccountHolder()
            // }
            // (This is already a @Singleton class with @Inject constructor, so Hilt can provide it directly)
            ```

    2. **Create `GoogleTokenProvider.kt` (Recommended):**
        * This class will abstract the token loading and refreshing logic from the Ktor `HttpClient`
          configuration.
            ```kotlin
            // backend-google/src/main/java/net/melisma/backend_google/auth/GoogleTokenProvider.kt
            package net.melisma.backend_google.auth

            import android.net.Uri
            import android.util.Log
            import io.ktor.client.plugins.auth.providers.BearerTokens
            import kotlinx.coroutines.sync.Mutex
            import kotlinx.coroutines.sync.withLock
            import javax.inject.Inject
            import javax.inject.Singleton

            @Singleton
            class GoogleKtorTokenProvider @Inject constructor(
                private val tokenPersistenceService: GoogleTokenPersistenceService,
                private val appAuthHelperService: AppAuthHelperService,
                private val activeAccountHolder: ActiveGoogleAccountHolder
            ) {
                private val TAG = "GoogleKtorTokenProvider"
                private val refreshMutex = Mutex()

                // IMPORTANT: These should NOT be hardcoded. Get them from a secure config.
                // For example, BuildConfig fields or an injected configuration service.
                private val androidClientId = "YOUR_ANDROID_OAUTH_CLIENT_ID" // REPLACE
                private val redirectUriForRefresh = Uri.parse("net.melisma.mail:/oauth2redirect") // REPLACE if different, must match AppAuth config

                suspend fun loadBearerTokens(): BearerTokens? {
                    val accountId = activeAccountHolder.getActiveAccountIdValue()
                    if (accountId == null) {
                        Log.w(TAG, "No active Google account ID for Ktor loadTokens.")
                        return null
                    }

                    val storedTokens = tokenPersistenceService.getTokens(accountId)
                    return if (storedTokens?.accessToken?.isNotBlank() == true && storedTokens.refreshToken?.isNotBlank() == true) {
                        // Check for expiry here if you want to be proactive, though Ktor refresh will handle it.
                        // val fiveMinutesInMillis = 5 * 60 * 1000
                        // if (storedTokens.expiresIn != 0L && storedTokens.expiresIn < (System.currentTimeMillis() + fiveMinutesInMillis)) {
                        //    Log.d(TAG, "Access token for $accountId is expiring soon, attempting refresh proactively.")
                        //    return refreshBearerTokens(accountId, storedTokens.refreshToken)
                        // }
                        Log.d(TAG, "Ktor: Loaded tokens for account $accountId. Access: ...${storedTokens.accessToken.takeLast(6)}, Refresh: ...${storedTokens.refreshToken.takeLast(6)}")
                        BearerTokens(storedTokens.accessToken, storedTokens.refreshToken)
                    } else {
                        Log.w(TAG, "Ktor: No valid tokens found in persistence for account $accountId.")
                        null
                    }
                }

                suspend fun refreshBearerTokens(oldBearerTokens: BearerTokens?): BearerTokens? {
                    val accountId = activeAccountHolder.getActiveAccountIdValue()
                    if (accountId == null) {
                        Log.e(TAG, "Ktor Refresh: No active Google account ID.")
                        return null
                    }

                    // Use the refresh token from oldBearerTokens if available and matches,
                    // otherwise try to fetch the latest from persistence.
                    var refreshTokenToUse = oldBearerTokens?.refreshToken
                    if (refreshTokenToUse.isNullOrBlank()) {
                        val currentTokensFromPersistence = tokenPersistenceService.getTokens(accountId)
                        refreshTokenToUse = currentTokensFromPersistence?.refreshToken
                    }

                    if (refreshTokenToUse.isNullOrBlank()) {
                        Log.e(TAG, "Ktor Refresh: No refresh token available for account $accountId.")
                        // This is a critical failure; user might need to re-authenticate.
                        // Consider clearing tokens to force re-auth.
                        tokenPersistenceService.clearTokens(accountId, removeAccount = false)
                        return null
                    }

                    Log.d(TAG, "Ktor Refresh: Attempting to refresh tokens for account $accountId using refresh token: ...${refreshTokenToUse.takeLast(6)}")

                    return refreshMutex.withLock { // Prevent multiple concurrent refresh attempts
                        // Double-check: Another request might have refreshed tokens while this one was waiting for the mutex.
                        // If loadBearerTokens() doesn't find an expired token, it won't trigger refresh,
                        // but if it did, and another call refreshed it, we check here.
                        val latestPersistedTokens = tokenPersistenceService.getTokens(accountId)
                        if (latestPersistedTokens?.accessToken != null &&
                            (oldBearerTokens == null || latestPersistedTokens.accessToken != oldBearerTokens.accessToken) &&
                            (latestPersistedTokens.expiresIn == 0L || latestPersistedTokens.expiresIn > System.currentTimeMillis())) {
                            Log.i(TAG, "Ktor Refresh: Tokens seem to have been refreshed by another process. Using new tokens for $accountId.")
                            return@withLock BearerTokens(latestPersistedTokens.accessToken, latestPersistedTokens.refreshToken ?: refreshTokenToUse)
                        }

                        try {
                            val refreshedTokenResponse = appAuthHelperService.refreshAccessToken(
                                refreshToken = refreshTokenToUse,
                                clientId = androidClientId, // Must be the Android Client ID used with AppAuth
                                redirectUri = redirectUriForRefresh // Must match AppAuth config
                            )

                            // Persist the newly refreshed tokens (AppAuth might return a new refresh token)
                            // Pass the raw TokenResponse so all fields (new expiry, scopes, potentially new refresh token) are updated.
                            val saved = tokenPersistenceService.saveTokens(
                                accountId,
                                refreshedTokenResponse,
                                email = tokenPersistenceService.getAccountInfo(accountId)["email"], // Preserve existing email
                                displayName = tokenPersistenceService.getAccountInfo(accountId)["displayName"] // Preserve existing display name
                            )

                            if (!saved) {
                               Log.e(TAG, "Ktor Refresh: Failed to save refreshed tokens for $accountId.")
                               // If saving fails, we might be in a bad state.
                               // Returning null will likely cause the original request to fail, prompting user action or re-auth.
                               return@withLock null
                            }

                            val newAccessToken = refreshedTokenResponse.accessToken
                            val newRefreshToken = refreshedTokenResponse.refreshToken ?: refreshTokenToUse // Use new if provided, else old

                            if (newAccessToken.isNullOrBlank()) {
                                Log.e(TAG, "Ktor Refresh: Refreshed access token is blank for $accountId.")
                                return@withLock null
                            }

                            Log.i(TAG, "Ktor Refresh: Tokens refreshed successfully for account $accountId.")
                            BearerTokens(newAccessToken, newRefreshToken)
                        } catch (e: Exception) {
                            Log.e(TAG, "Ktor Refresh: Failed to refresh tokens for account $accountId.", e)
                            // If refresh fails (e.g., refresh_token revoked, invalid_grant),
                            // the user might need to re-authenticate.
                            // Clear out the potentially problematic tokens.
                            tokenPersistenceService.clearTokens(accountId, removeAccount = false)
                            null // Returning null tells Ktor refresh failed.
                        }
                    }
                }
            }
            ```
        * **Provide it via Hilt:** This is a `@Singleton` with `@Inject` constructor, so Hilt can
          provide it.

    3. **Update `BackendGoogleModule.kt` to use Ktor `Auth` Plugin:**
       ```kotlin
       // backend-google/src/main/java/net/melisma/backend_google/di/BackendGoogleModule.kt
       import io.ktor.client.plugins.auth.Auth // << ADD
       import io.ktor.client.plugins.auth.providers.bearer // << ADD
       import io.ktor.client.plugins.logging.Logging // For logging Ktor requests (optional)
       import io.ktor.client.plugins.logging.LogLevel // For logging Ktor requests (optional)
       import net.melisma.backend_google.auth.GoogleKtorTokenProvider // << ADD (if you created it)

       // ... other imports

       @Provides
       @Singleton
       @GoogleHttpClient
       fun provideGoogleHttpClient(
           json: Json,
           // Inject your GoogleKtorTokenProvider (recommended)
           googleKtorTokenProvider: GoogleKtorTokenProvider
           // OR, if not using GoogleKtorTokenProvider, inject its dependencies here:
           // tokenPersistenceService: GoogleTokenPersistenceService,
           // appAuthHelperService: AppAuthHelperService,
           // activeAccountHolder: ActiveGoogleAccountHolder
       ): HttpClient {
           Log.d("BackendGoogleModule", "Providing Google HTTPClient with Auth plugin.")
           return HttpClient(OkHttp) {
               engine {
                   config {
                       connectTimeout(30, TimeUnit.SECONDS)
                       readTimeout(30, TimeUnit.SECONDS)
                       // Consider adding network interceptors for logging or error handling if needed
                   }
               }

               install(ContentNegotiation) {
                   json(json) // Ensure your Json instance is configured as needed (e.g., ignoreUnknownKeys)
               }

               // Optional: Logging for Ktor requests/responses (very useful for debugging)
               install(Logging) {
                   level = LogLevel.ALL // Or LogLevel.HEADERS, LogLevel.BODY, etc.
                   logger = object : io.ktor.client.plugins.logging.Logger {
                       override fun log(message: String) {
                           Log.d("KtorGoogleClient", message)
                       }
                   }
               }

               defaultRequest {
                   header(HttpHeaders.Accept, ContentType.Application.Json)
                   // You can set common base URL parts here if all Gmail API calls share it
                   // url("[https://gmail.googleapis.com/gmail/v1/users/](https://gmail.googleapis.com/gmail/v1/users/)")
               }

               // Install the Auth plugin for Bearer Token authentication
               install(Auth) {
                   bearer {
                       loadTokens {
                           // Delegate to the token provider
                           googleKtorTokenProvider.loadBearerTokens()
                       }

                       refreshTokens { // Ktor provides old BearerTokens? as 'this.oldTokens' or parameter
                           googleKtorTokenProvider.refreshBearerTokens(this.oldTokens)
                       }

                       // Optional: Define when to send the bearer token.
                       // By default, it's sent for all requests if tokens are available.
                       // You might restrict it to specific hosts if this HttpClient is used for other things.
                       // sendWithoutRequest { request ->
                       //    request.url.host == "gmail.googleapis.com"
                       // }

                       // Optional: Configure to only attempt refresh on 401 Unauthorized.
                       // By default, Ktor tries to refresh if loadTokens returns null or expired tokens
                       // AND if a request fails with 401.
                       // refreshTokensWhen { response ->
                       //    response.status == HttpStatusCode.Unauthorized
                       // }
                   }
               }
           }
       }
       ```
        * **Ensure Dependencies:** Confirm `ktor-client-auth` is in
          `backend-google/build.gradle.kts`. (It is).
        * **Client IDs:** The `androidClientId` and `redirectUriForRefresh` in
          `GoogleKtorTokenProvider` **must** be the correct values for your Android OAuth Client ID
          configured in Google Cloud Console and used by AppAuth. Store these securely (e.g.,
          `BuildConfig`).
        * **Active Account ID:** The `ActiveGoogleAccountHolder` (or your chosen mechanism) is
          crucial for multi-account support. When a user switches accounts in the UI, this holder
          must be updated.

**Task 2.2: Verify `GmailApiHelper.kt` for Authenticated Calls**

* **Goal:** Ensure `GmailApiHelper.kt` (which implements `MailApiService`) uses the Ktor
  `HttpClient` (now configured with Auth) to make calls to Gmail API endpoints.
* **Files to Modify/Verify:**
    * `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt` (This file was not
      provided previously, assume it exists as per `BackendGoogleModule.kt`).
* **Detailed Steps:**
    1. **Constructor Injection:** Ensure `GmailApiHelper` receives the `@GoogleHttpClient` (which
       now has the Auth plugin) via its constructor.
       ```kotlin
       // backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt
       // (Conceptual - ensure it exists and is structured like this)
       package net.melisma.backend_google

       import io.ktor.client.HttpClient
       import io.ktor.client.call.body
       import io.ktor.client.request.get
       import io.ktor.client.request.headers
       import net.melisma.backend_google.di.GoogleHttpClient
       import net.melisma.backend_google.errors.GoogleErrorMapper
       import net.melisma.core_data.datasource.MailApiService
       import net.melisma.core_data.model.MailFolder // Assuming model exists
       import net.melisma.core_data.model.Message // Assuming model exists
       import javax.inject.Inject

       class GmailApiHelper @Inject constructor(
           @GoogleHttpClient private val httpClient: HttpClient,
           private val errorMapper: GoogleErrorMapper // Assuming you have a GoogleErrorMapper
       ) : MailApiService {

           private val GMAIL_API_BASE_URL = "[https://gmail.googleapis.com/gmail/v1/users/](https://gmail.googleapis.com/gmail/v1/users/)"

           override suspend fun getFolders(accountId: String): Result<List<MailFolder>> {
               return try {
                   // Account ID is typically 'me' for the authenticated user with Gmail API
                   val response: GmailLabelsResponse = httpClient.get("${GMAIL_API_BASE_URL}me/labels").body()
                   val mailFolders = response.labels?.map {
                       MailFolder(id = it.id ?: "", name = it.name ?: "Unknown Label", providerType = "GOOGLE", messageCount = it.messagesTotal ?: 0)
                   } ?: emptyList()
                   Result.success(mailFolders)
               } catch (e: Exception) {
                   Log.e("GmailApiHelper", "Error fetching folders for $accountId", e)
                   Result.failure(errorMapper.mapApiThrowable(e))
               }
           }

           override suspend fun getMessages(accountId: String, folderId: String, pageToken: String?): Result<Pair<List<Message>, String?>> {
               // Implement fetching messages for a label (folderId)
               // Example: GET ${GMAIL_API_BASE_URL}me/messages?labelIds=$folderId&pageToken=$pageToken
               // This will likely involve multiple calls: one to get message IDs, then calls to get each message detail.
               // Handle pagination with pageToken.
               // Map Gmail API response to your List<Message> and next page token.
               return Result.failure(NotImplementedError("getMessages for Gmail not implemented yet.")) // Placeholder
           }

           // Define data classes for Gmail API responses, e.g.:
           // @kotlinx.serialization.Serializable
           // data class GmailLabelsResponse(val labels: List<GmailLabel>?)
           // @kotlinx.serialization.Serializable
           // data class GmailLabel(val id: String?, val name: String?, val messagesTotal: Int?)

           // ... other MailApiService methods (getMessageDetails, etc.)
       }
       ```
    2. **API Calls:** When making GET/POST requests (e.g., to fetch labels or messages), the Ktor
       `Auth` plugin should now automatically handle attaching the Bearer token. You should not need
       to manually add `Authorization` headers in `GmailApiHelper`'s methods.
    3. **Error Handling:** `GmailApiHelper` should catch exceptions from Ktor calls and use
       `GoogleErrorMapper` to convert them into application-specific errors. The Auth plugin will
       handle 401s for token refresh; if refresh fails catastrophically, the request will ultimately
       fail, and that failure should be mapped.

---
### **Phase 3 (New): UI/UX Integration for AppAuth Flow**
---

**Context:**
The UI needs to correctly trigger the new AppAuth flow and handle its asynchronous nature, including
launching the AppAuth Custom Tab intent and processing its result.

**Task 3.1: Update ViewModel and Activity/Screen for Google Account Addition (AppAuth)**

* **Goal:** Connect the "Add Google Account" UI action through the `ViewModel` to the
  `DefaultAccountRepository`'s AppAuth flow. The Activity/Screen must launch the `Intent` provided
  by the repository and then pass the result back.
* **Files to Modify (Examples - actual files might differ):**
    * `ui/settings/SettingsViewModel.kt` (or your equivalent ViewModel)
    * `ui/settings/SettingsScreen.kt` (or your Composable screen)
    * `MainActivity.kt` (or the Activity hosting the Composable)

* **Detailed Steps:**

    1. **ViewModel (`SettingsViewModel.kt` or similar):**
        * Inject `DefaultAccountRepository`.
        * Expose a method to initiate Google account addition.
        * Observe `defaultAccountRepository.appAuthAuthorizationIntent` to receive the `Intent` to
          be launched.
        * Provide a method to finalize the setup after the AppAuth redirect.
            ```kotlin
            // Example: SettingsViewModel.kt
            import androidx.lifecycle.ViewModel
            import androidx.lifecycle.viewModelScope
            import android.app.Activity
            import android.content.Intent
            import dagger.hilt.android.lifecycle.HiltViewModel
            import kotlinx.coroutines.flow.SharedFlow
            import kotlinx.coroutines.flow.SharingStarted
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.shareIn
            import kotlinx.coroutines.launch
            import net.melisma.data.repository.DefaultAccountRepository // Your repository
            import javax.inject.Inject

            @HiltViewModel
            class SettingsViewModel @Inject constructor(
                private val accountRepository: DefaultAccountRepository
            ) : ViewModel() {

                // To signal the Activity/Screen to launch the AppAuth Intent
                val appAuthAuthorizationIntent: SharedFlow<Intent?> =
                    accountRepository.appAuthAuthorizationIntent.shareIn(
                        scope = viewModelScope,
                        started = SharingStarted.Lazily // Or Eagerly, if needed immediately
                    )

                val isLoading: StateFlow<Boolean> = accountRepository.isLoadingAccountAction
                val userMessage: SharedFlow<String?> = accountRepository.accountActionMessage.shareIn(
                    scope = viewModelScope,
                    started = SharingStarted.Lazily
                )

                fun onAddGoogleAccountClicked(activity: Activity) {
                    viewModelScope.launch {
                        // Define desired scopes for Gmail API
                        val gmailScopes = listOf(
                            "[https.www.googleapis.com/auth/gmail.readonly](https://https.www.googleapis.com/auth/gmail.readonly)", // Basic view
                            "[https://www.googleapis.com/auth/userinfo.email](https://www.googleapis.com/auth/userinfo.email)",   // Get user's email address
                            "[https://www.googleapis.com/auth/userinfo.profile](https://www.googleapis.com/auth/userinfo.profile)"  // Get basic profile info
                            // Add "[https://mail.google.com/](https://mail.google.com/)" if you need full mail access (send, modify, etc.)
                        )
                        // The addAccount method in repository will now trigger the CredentialManager
                        // and then potentially emit an AppAuth intent to appAuthAuthorizationIntent Flow.
                        accountRepository.addAccount(activity, gmailScopes, "GOOGLE")
                    }
                }

                fun handleAppAuthRedirect(intentData: Intent) {
                    viewModelScope.launch {
                        // This is called after the AppAuth flow completes and RedirectUriReceiverActivity sends result back
                        accountRepository.finalizeGoogleAccountSetupWithAppAuth(intentData)
                    }
                }

                fun clearUserMessage() {
                    // If your repository has a method to clear one-time messages
                    // accountRepository.clearAccountActionMessage()
                }
            }
            ```

    2. **Activity (`MainActivity.kt` or host Activity):**
        * Create an `ActivityResultLauncher` to handle the AppAuth `Intent` result.
        * Observe the `appAuthAuthorizationIntent` from the `ViewModel` and launch the intent when
          it's emitted.
        * The launcher's callback will receive the result from `RedirectUriReceiverActivity` and
          pass it to the ViewModel's `handleAppAuthRedirect` method.
            ```kotlin
            // Example: MainActivity.kt
            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.activity.result.ActivityResultLauncher
            import androidx.activity.result.contract.ActivityResultContracts
            import androidx.activity.viewModels // If using activity-ktx for viewModels delegate
            import androidx.compose.runtime.LaunchedEffect
            import androidx.compose.runtime.collectAsState
            import androidx.compose.runtime.getValue
            import androidx.lifecycle.flowWithLifecycle
            import androidx.lifecycle.lifecycleScope
            import dagger.hilt.android.AndroidEntryPoint
            import kotlinx.coroutines.flow.launchIn
            import kotlinx.coroutines.flow.onEach
            // Assuming SettingsViewModel and SettingsScreen exist
            // import your.package.ui.settings.SettingsScreen
            // import your.package.ui.settings.SettingsViewModel


            @AndroidEntryPoint
            class MainActivity : ComponentActivity() {

                // Assuming SettingsViewModel is used here or in a Composable hosted by MainActivity
                // If SettingsScreen is navigated to, the launcher might need to be in that screen's context
                // or passed down. For simplicity, shown here.
                private val settingsViewModel: SettingsViewModel by viewModels() // Example instantiation

                private lateinit var appAuthLauncher: ActivityResultLauncher<Intent>

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)

                    appAuthLauncher = registerForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                        // No need for custom contract if RedirectUriReceiverActivity handles the result processing
                        // and we just get the raw intent back.
                    ) { result ->
                        // The result.data is the Intent from RedirectUriReceiverActivity
                        result.data?.let { intent ->
                            Log.d("MainActivity", "AppAuthLauncher received result. Calling ViewModel to handle.")
                            settingsViewModel.handleAppAuthRedirect(intent)
                        } ?: run {
                            Log.w("MainActivity", "AppAuthLauncher result.data is null. User might have cancelled.")
                            // Optionally, inform ViewModel about cancellation if possible/needed
                            // settingsViewModel.handleAppAuthCancellation()
                        }
                    }

                    setContent {
                        // Your app's Composable content
                        // Example:
                        // MelismaMailTheme {
                        //    YourNavHost {
                        //        composable("settings") {
                        //            val settingsVm: SettingsViewModel = hiltViewModel() // Get ViewModel for the screen
                        //            SettingsScreen(viewModel = settingsVm, activity = this@MainActivity)
                        //        }
                        //    }
                        // }

                        // Observe the intent from ViewModel to launch AppAuth
                        // This should ideally be tied to the lifecycle of the screen that can launch it.
                        LaunchedEffect(Unit) { // Or key on a specific trigger
                            settingsViewModel.appAuthAuthorizationIntent
                                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                                .onEach { intent ->
                                    intent?.let {
                                        Log.d("MainActivity", "Received AppAuth intent from ViewModel. Launching...")
                                        appAuthLauncher.launch(it)
                                        // Potentially clear the intent from the ViewModel/Repo after launching
                                        // to prevent re-launch on config change, if it's a single-shot event.
                                    }
                                }
                                .launchIn(lifecycleScope)
                        }
                    }
                }
            }
            ```

    3. **Composable Screen (`SettingsScreen.kt`):**
        * Get the `Activity` context correctly (e.g., `LocalContext.current as Activity`).
        * Call the `ViewModel.onAddGoogleAccountClicked(activity)` method.
        * Display loading states and messages from the ViewModel.
            ```kotlin
            // Example: SettingsScreen.kt
            import android.app.Activity
            import androidx.compose.material3.Button
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.LaunchedEffect
            import androidx.compose.runtime.collectAsState
            import androidx.compose.runtime.getValue
            import androidx.compose.ui.platform.LocalContext
            import androidx.compose.material3.CircularProgressIndicator // For loading
            import com.google.android.material.snackbar.Snackbar // Example for messages

            @Composable
            fun SettingsScreen(
                viewModel: SettingsViewModel // Injected or passed
                // activity: Activity, // Pass activity context if needed by VM or for launching intents directly
            ) {
                val context = LocalContext.current
                val isLoading by viewModel.isLoading.collectAsState()
                val userMessage by viewModel.userMessage.collectAsState(initial = null)

                // Show messages (e.g., with a Snackbar)
                LaunchedEffect(userMessage) {
                    userMessage?.let { message ->
                        // Show Snackbar or Toast
                        // Example: Snackbar.make((context as Activity).findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
                        // viewModel.clearUserMessage() // Optionally clear after showing
                        Log.i("SettingsScreen", "UserMessage: $message")
                    }
                }

                Button(
                    onClick = {
                        // Get activity context carefully if not passed directly
                        val activity = context as? Activity
                        if (activity != null) {
                            viewModel.onAddGoogleAccountClicked(activity)
                        } else {
                            Log.e("SettingsScreen", "Context is not an Activity, cannot start Google Sign-In")
                            // Show error to user
                        }
                    },
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text("Add Google Account")
                    }
                }
                // ... other settings UI
            }
            ```

**Task 3.2: Implement UI for Displaying Google Data (Labels/Messages)**

* **Goal:** Once authentication is working, fetch and display Gmail labels (as folders) and messages
  using the existing UI patterns for Microsoft accounts, but adapted for Google data.
* **Files to Modify (Examples):** `FolderListScreen.kt`, `MessageListScreen.kt`, relevant
  ViewModels.
* **Steps:**
    1. When a Google account is active, ViewModels should request data (labels, messages) via
       `DefaultFolderRepository` and `DefaultMessageRepository` (which will use `GmailApiHelper` via
       `MailApiService` abstraction).
    2. Update Composables to correctly display Gmail label names and message snippets.
    3. Ensure pagination works for message lists from Gmail.

---
### **Phase 4 (New & Rework): Comprehensive Testing**
---

**Context for Rework:**
Existing tests for `AppAuthHelperService` are basic. The previous attempt at Ktor Auth plugin
integration failed, so no tests exist for it. End-to-end testing of the full AppAuth flow, token
persistence, and refresh is crucial.

**Task 4.1: Enhance Unit Tests**

* **Goal:** Add more comprehensive unit tests for `AppAuthHelperService`,
  `GoogleTokenPersistenceService`, `GoogleKtorTokenProvider`, and critical logic in
  `DefaultAccountRepository`.
* **Files to Modify/Create:** Relevant test files in `src/test/java`.
* **Steps:**
    1. **`AppAuthHelperServiceTest.kt`:**
        * Add tests for `handleAuthorizationResponse` (mocking `Intent` data for success and error
          cases).
        * Add tests for `performTokenRequest` (mocking `AuthorizationResponse` and
          `AuthorizationService.performTokenRequest` callback).
        * Add tests for `refreshAccessToken` (mocking `AuthorizationService.performTokenRequest`
          callback for refresh grant).
        * Test error states and `lastError` emission.
    2. **`GoogleTokenPersistenceServiceTest.kt` (New):**
        * Mock `Context`, `AccountManager`, and `SecureEncryptionService`.
        * Test `saveTokens`: verify `AccountManager.setUserData` is called with correctly encrypted
          data.
        * Test `getTokens`: verify decryption and correct data retrieval; test token expiry logic.
        * Test `updateAccessToken`.
        * Test `clearTokens` and account removal.
    3. **`GoogleKtorTokenProviderTest.kt` (New):**
        * Mock dependencies (`GoogleTokenPersistenceService`, `AppAuthHelperService`,
          `ActiveGoogleAccountHolder`).
        * Test `loadBearerTokens`: successful load, no account, no tokens.
        * Test `refreshBearerTokens`: successful refresh, refresh failure, no refresh token.
        * Verify interaction with mutex and calls to persistence/helper services.
    4. **`DefaultAccountRepositoryTest.kt`:**
        * Focus on the Google account addition and removal logic.
        * Mock all auth manager dependencies (`GoogleAuthManager`, `AppAuthHelperService`,
          `GoogleTokenPersistenceService`).
        * Test the sequence of calls in `addGoogleAccount` (Credential Manager sign-in, AppAuth
          intent emission).
        * Test `finalizeGoogleAccountSetupWithAppAuth` (token exchange, token saving).
        * Test `removeGoogleAccount` (clearing tokens, sign-out calls).
        * Use `Turbine` to test `Flow` emissions (`_appAuthAuthorizationIntent`, `_accounts`,
          `_accountActionMessage`).

**Task 4.2: Implement End-to-End (E2E) Instrumented Tests**

* **Goal:** Test the entire Google sign-in, AppAuth flow, token refresh, and basic data retrieval on
  an emulator or device.
* **Files to Create:** In `src/androidTest/java`.
* **Steps:**
    1. **Google Account Addition E2E Test:**
        * Use Espresso/UI Automator to tap "Add Google Account".
        * Handle the `CredentialManager` prompt (may require UI Automator if it's outside app
          process).
        * Handle the AppAuth Custom Tab (will likely require UI Automator to interact with the
          browser and Google's consent screen). This is complex.
        * Verify that after the flow, a Google account is listed in the app and tokens are stored (
          indirectly, by checking if subsequent API calls succeed).
    2. **Token Refresh E2E Test:**
        * Sign in with a Google account.
        * Fetch some data (e.g., labels).
        * Artificially expire the access token (e.g., by revoking permission from Google Account
          settings online, then waiting, or if possible, using a test API to invalidate it). This is
          hard to automate reliably.
        * Alternatively, if Ktor client logging is enabled, observe logs for token refresh attempts
          on subsequent API calls after a delay.
        * Attempt to fetch data again and verify it succeeds (meaning refresh worked).
    3. **Sign-Out E2E Test:**
        * Sign in.
        * Sign out from the app.
        * Verify the account is removed from the app's UI.
        * Attempt to re-add the account; verify the full auth flow is triggered (not silent re-auth
          if tokens weren't fully cleared).

**Task 4.3: Manual Testing (Critical)**

* **Goal:** Manually test all aspects of the Google integration on real devices with various Android
  versions and Google account configurations.
* **Steps:**
    * Test adding a Google account (Credential Manager + AppAuth Custom Tab flow).
    * Test consent screen interaction.
    * Verify Gmail labels/folders are fetched and displayed.
    * Verify messages are fetched and displayed.
    * Leave the app, wait for token to expire (or revoke permissions from Google account settings),
      re-enter app, and see if data still loads (testing refresh).
    * Test comprehensive sign-out.
    * Test error conditions: no network, user cancels Custom Tab, Google API errors.
    * Test with accounts that have 2FA, different security settings if possible.
    * Test the "Enhanced Account Discovery" (sign-in with no prior authorization vs. already
      authorized).

---
### **Phase 5 (New): Finalizing Core Mail Functionality for Google**
---

**Task 5.1: Implement Remaining `MailApiService` Methods in `GmailApiHelper`**

* **Goal:** Ensure all methods defined in the `MailApiService` interface (e.g., `getMessageDetails`,
  `sendMessage`, `deleteMessage`, `markAsRead`, etc.) are fully implemented in `GmailApiHelper.kt`
  using the authenticated Ktor client.
* **Files to Modify:** `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`
* **Steps:**
    1. Review `MailApiService.kt` in `:core-data`.
    2. For each method, identify the corresponding Gmail API endpoint(s).
    3. Implement the Ktor calls (e.g., `httpClient.get(...)`, `httpClient.post(...)`) in
       `GmailApiHelper.kt`.
    4. Map request parameters and response DTOs (Data Transfer Objects) correctly. Create new Kotlin
       data classes for any Gmail API responses not yet modeled.
    5. Use `GoogleErrorMapper` for consistent error handling.

**Task 5.2: Connect UI Actions to `GmailApiHelper` via Repositories/ViewModels**

* **Goal:** Wire up UI elements for mail actions (view, delete, mark read, compose, send for Google
  accounts) to their respective ViewModel methods, which in turn call repository methods that
  delegate to `GmailApiHelper`.
* **Files to Modify:** Relevant Composable screens, ViewModels, and potentially
  `DefaultMessageRepository.kt`, `DefaultComposeRepository.kt` (if they exist).
* **Steps:** Similar to how Microsoft account actions are wired, but ensuring the "GOOGLE" provider
  path is taken.

## III. Definition of "Done" for Google Integration (MVP - AppAuth Strategy)

* **Data Layer Reworked:** `DefaultAccountRepository.addGoogleAccount` and `removeGoogleAccount`
  fully utilize the AppAuth flow with `AppAuthHelperService` and `GoogleTokenPersistenceService`.
* **Ktor Auth Integrated:** Ktor client for Google uses the `Auth` plugin, automatically
  loading/refreshing tokens via `GoogleKtorTokenProvider` (or similar) which uses
  `GoogleTokenPersistenceService` and `AppAuthHelperService`.
* **User Authentication:**
    * User can initiate Google Sign-In via `androidx.credentials.CredentialManager` (
      `GetSignInWithGoogleOption` using Web Client ID, nonce, and enhanced account discovery).
    * App successfully launches AppAuth flow using Android Client ID for Gmail API scope consent,
      displaying Google's consent screen in a Custom Tab.
    * App successfully exchanges the authorization code (obtained via AppAuth redirect) for access
      and refresh tokens using AppAuth and explicit PKCE.
* **Token Management:**
    * Google OAuth access and refresh tokens are encrypted (via `SecureEncryptionService` with
      Keystore) and stored in `AccountManager` (via `GoogleTokenPersistenceService`).
    * Ktor client automatically uses the Google access token for Gmail API calls and can refresh it
      using the stored refresh token.
* **Core Mail Viewing:** App can fetch and display Gmail labels (as folders) and a list of messages
  for a selected label using the `MailApiService` abstraction with Google.
* **Sign-Out:** Comprehensive sign-out for Google accounts is functional (clears local tokens from
  `AccountManager`, clears `CredentialManager` state, and ideally attempts server-side token
  revocation).
* **Error Handling:** Basic error handling for Google authentication (Credential Manager, AppAuth)
  and Gmail API calls is in place using `GoogleErrorMapper`.
* **Configuration:** Google Cloud Console (Android Client ID with correct package name, SHA-1, and
  Redirect URI; Web Client ID; Gmail API enabled; Consent Screen "In Production") is correctly
  configured and documented internally. Client IDs and sensitive URIs are not hardcoded directly in
  widespread code but managed via a secure configuration approach (e.g., `BuildConfig` from gradle
  properties, or a non-versioned config file).
* **Testing:** Key new components and flows are unit-tested. Core E2E scenarios (sign-in, data
  fetch, sign-out) are manually and (where feasible) instrumentally tested.

## IV. Next Steps (Prioritized from this Revised Plan)

1. **Phase 1 (Rework): Data Layer Integration for AppAuth**
    * Task 1.1: Rework `DefaultAccountRepository` for Full AppAuth Integration (all sub-steps: DI,
      Intent Flow, `addGoogleAccount`, `finalizeGoogleAccountSetupWithAppAuth`,
      `removeGoogleAccount`, cleanup).
2. **Phase 2 (Rework & New): Ktor Client HTTP Authentication**
    * Task 2.1: Implement Ktor `Auth` Plugin with Bearer Tokens (create `ActiveGoogleAccountHolder`,
      `GoogleKtorTokenProvider`, update `BackendGoogleModule`).
    * Task 2.2: Verify/Implement `GmailApiHelper.kt` for basic authenticated calls (e.g., fetching
      labels).
3. **Phase 3 (New): UI/UX Integration for AppAuth Flow**
    * Task 3.1: Update ViewModel and Activity/Screen for Google Account Addition (wiring up
      `addGoogleAccount` from UI, launching AppAuth intent, handling redirect result).
4. **Phase 4 (New & Rework): Comprehensive Testing**
    * Start with manual E2E testing of the reworked add/remove account flows.
    * Task 4.1: Enhance Unit Tests for new/reworked repository logic, token provider.
    * Task 4.2 & 4.3: Progress on E2E and further manual testing.
5. **Phase 5 (New): Finalizing Core Mail Functionality for Google**
    * Task 5.1 & 5.2: Implement remaining `MailApiService` methods in `GmailApiHelper` and connect
      UI.

## V. Future Investigations (Post-MVP)

* **Server-Side Token Revocation for Google:** Implement a robust mechanism for revoking tokens on
  Google's servers during sign-out as part of `DefaultAccountRepository.removeGoogleAccount`. This
  usually involves a Ktor `POST` request to `https://oauth2.googleapis.com/revoke` with the token.
* **Refined State Management for Auth:** Improve UI feedback during the multi-step auth process
  using a more granular state representation.
* **Secure Configuration Management:** Ensure Client IDs and other sensitive configuration values
  are not hardcoded and are managed securely (e.g., loaded from `gradle.properties` into
  `BuildConfig`, or using encrypted SharedPreferences for runtime configuration if applicable).
* **Advanced Error Handling:** More nuanced error messages and recovery paths for specific OAuth
  errors (e.g., `invalid_grant` during token refresh might require full re-authentication).

---
This revised plan focuses on correcting the existing integration issues and completing the AppAuth
flow as originally intended.
