// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.melisma.backend_google.auth.AppAuthHelperService
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleSignInResult
import net.melisma.backend_google.auth.GoogleTokenPersistenceService
import net.melisma.backend_microsoft.auth.AddAccountResult
import net.melisma.backend_microsoft.auth.AuthStateListener
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.RemoveAccountResult
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val googleAuthManager: GoogleAuthManager,
    private val appAuthHelperService: AppAuthHelperService,
    private val googleTokenPersistenceService: GoogleTokenPersistenceService,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : AccountRepository, AuthStateListener {

    private val TAG = "DefaultAccountRepo" // Logging TAG

    private val _authState = MutableStateFlow(determineInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _accounts = MutableStateFlow(mapToGenericAccounts(microsoftAuthManager.accounts))
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _isLoadingAccountAction = MutableStateFlow(false)
    override val isLoadingAccountAction: StateFlow<Boolean> = _isLoadingAccountAction.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    // SharedFlow for AppAuth authorization intent
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

    init {
        Log.d(
            TAG,
            "Initializing DefaultAccountRepository. Injected errorMappers keys: ${errorMappers.keys}"
        )
        Log.d(TAG, "Registering as AuthStateListener for MicrosoftAuthManager")
        microsoftAuthManager.setAuthStateListener(this)
        syncStateFromManager()
    }

    override fun onAuthStateChanged(
        isInitialized: Boolean,
        msAccounts: List<IAccount>,
        error: MsalException?
    ) {
        Log.d(
            TAG,
            "onAuthStateChanged (MSAL): init=$isInitialized, msAccountCount=${msAccounts.size}, errorPresent=${error != null}"
        )
        _authState.value = determineAuthState(isInitialized, error)
        val newGenericAccounts = mapToGenericAccounts(msAccounts)
        Log.d(TAG, "onAuthStateChanged: Mapped to ${newGenericAccounts.size} generic accounts.")
        _accounts.value = newGenericAccounts
        _isLoadingAccountAction.value = false
    }

    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        Log.d(TAG, "addAccount called. ProviderType: $providerType, Scopes: $scopes")
        when (providerType.uppercase()) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            "GOOGLE" -> addGoogleAccount(activity, scopes)
            else -> {
                Log.w(TAG, "Unsupported provider type for addAccount: $providerType")
                tryEmitMessage("Unsupported account provider: $providerType")
            }
        }
    }


    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        Log.d(
            TAG,
            "addAccount (legacy overload) called, defaulting to MS provider. Scopes: $scopes"
        )
        addMicrosoftAccount(activity, scopes)
    }

    override suspend fun removeAccount(account: Account) {
        Log.d(
            TAG,
            "removeAccount called for account: ${account.username} (Provider: ${account.providerType})"
        )
        _isLoadingAccountAction.value = true
        when (account.providerType.uppercase()) {
            "MS" -> removeMicrosoftAccount(account)
            "GOOGLE" -> removeGoogleAccount(account)
            else -> {
                Log.w(
                    TAG,
                    "Cannot remove account with unsupported provider type: ${account.providerType}"
                )
                tryEmitMessage("Cannot remove account with unsupported provider type.")
                _isLoadingAccountAction.value = false
            }
        }
    }

    override fun clearAccountActionMessage() {
        Log.d(TAG, "clearAccountActionMessage called.")
        tryEmitMessage(null)
    }

    private fun getErrorMapperForProvider(providerType: String): ErrorMapperService? {
        val providerKey = providerType.uppercase()
        Log.d(
            TAG,
            "getErrorMapperForProvider: Attempting to get mapper for key '$providerKey'. Available keys: ${errorMappers.keys}"
        )
        val mapper = errorMappers[providerKey]
        if (mapper == null) {
            Log.e(TAG, "No ErrorMapperService found for provider type: $providerKey")
        }
        return mapper
    }

    private suspend fun addMicrosoftAccount(activity: Activity, scopes: List<String>) {
        Log.d(TAG, "addMicrosoftAccount: Attempting for scopes: $scopes")
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            Log.e(TAG, "addMicrosoftAccount: MS Error handler not found.")
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        val currentAuthState = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(TAG, "addMicrosoftAccount: Current MS Auth State before adding: $currentAuthState")
        if (currentAuthState !is AuthState.Initialized) {
            Log.w(TAG, "addMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }
        _isLoadingAccountAction.value = true
        Log.d(TAG, "addMicrosoftAccount: Calling MicrosoftAuthManager.addAccount...")
        microsoftAuthManager.addAccount(activity, scopes)
            .onEach { result ->
                Log.d(TAG, "addMicrosoftAccount: Result from MicrosoftAuthManager: $result")
                val message = when (result) {
                    is AddAccountResult.Success -> "Microsoft account added: ${result.account.username}"
                    is AddAccountResult.Error -> "Error adding Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"
                    is AddAccountResult.Cancelled -> "Microsoft account addition cancelled."
                    is AddAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                }
                tryEmitMessage(message)
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in addMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope)
    }

    private suspend fun removeMicrosoftAccount(account: Account) {
        Log.d(TAG, "removeMicrosoftAccount: Attempting for account: ${account.username}")
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            Log.e(TAG, "removeMicrosoftAccount: MS Error handler not found.")
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        val currentAuthState = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(
            TAG,
            "removeMicrosoftAccount: Current MS Auth State before removing: $currentAuthState"
        )
        if (currentAuthState !is AuthState.Initialized) {
            Log.w(TAG, "removeMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }

        val msalAccountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }
        if (msalAccountToRemove == null) {
            Log.e(
                TAG,
                "Microsoft account to remove (ID: ${account.id}, Username: ${account.username}) not found in MS manager."
            )
            tryEmitMessage("Microsoft account not found for removal.")
            _isLoadingAccountAction.value = false
            return
        }

        Log.d(
            TAG,
            "removeMicrosoftAccount: Calling MicrosoftAuthManager.removeAccount for MSAL account: ${msalAccountToRemove.username}"
        )
        microsoftAuthManager.removeAccount(msalAccountToRemove)
            .onEach { result ->
                Log.d(TAG, "removeMicrosoftAccount: Result from MicrosoftAuthManager: $result")
                val message = when (result) {
                    is RemoveAccountResult.Success -> "Microsoft account removed: ${msalAccountToRemove.username}"
                    is RemoveAccountResult.Error -> "Error removing Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"
                    is RemoveAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                    is RemoveAccountResult.AccountNotFound -> "Microsoft account to remove not found by MS auth manager."
                }
                tryEmitMessage(message)
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in removeMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope)
    }

    private fun tryEmitMessage(message: String?) {
        Log.d(TAG, "tryEmitMessage: '$message'")
        externalScope.launch {
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                Log.w(TAG, "Failed to emit account action message (Buffer full?): $message")
            }
        }
    }

    private fun syncStateFromManager() {
        Log.d(TAG, "syncStateFromManager called.")
        onAuthStateChanged(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.accounts,
            microsoftAuthManager.initializationError
        )
    }

    private fun determineInitialAuthState(): AuthState {
        val state = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(TAG, "determineInitialAuthState: $state")
        return state
    }

    private fun determineAuthState(isInitialized: Boolean, error: MsalException?): AuthState {
        val state = when {
            !isInitialized && error != null -> AuthState.InitializationError(error)
            !isInitialized && error == null -> AuthState.Initializing
            else -> AuthState.Initialized
        }
        Log.v(
            TAG,
            "determineAuthState: isInitialized=$isInitialized, errorPresent=${error != null} -> $state"
        )
        return state
    }

    private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
        Log.d(TAG, "mapToGenericAccounts: Mapping ${msalAccounts.size} MSAL accounts.")
        // We'll keep track of both MS and Google accounts in this repository
        // Start with Microsoft accounts
        val accounts = msalAccounts.map { msalAccount ->
            Account(
                id = msalAccount.id ?: "",
                username = msalAccount.username ?: "Unknown User",
                providerType = "MS"
            )
        }.toMutableList()

        // In a more complete implementation, we would also fetch and add Google accounts here
        // from a store maintained by this repository

        return accounts.also {
            Log.d(
                TAG,
                "mapToGenericAccounts: Resulting generic accounts: ${it.joinToString { acc -> acc.username }}"
            )
        }
    }

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
                Log.i(
                    TAG,
                    "Google Sign-In (CredentialManager) successful. User ID: ${idTokenCredential.id}"
                )

                // Store details temporarily for the AppAuth flow
                pendingGoogleAccountId = idTokenCredential.id
                pendingGoogleEmail = idTokenCredential.email // May be null
                pendingGoogleDisplayName = idTokenCredential.displayName

                // Step 2: Check if valid tokens already exist for this account
                val existingTokens =
                    googleTokenPersistenceService.getTokens(pendingGoogleAccountId!!)
                // Check if token exists and is not expired (expiresIn == 0L means no expiry info or non-expiring refresh token scenario)
                // Add a small buffer (e.g., 5 minutes) to expiry check if desired: System.currentTimeMillis() < (existingTokens.expiresIn - 300000)
                if (existingTokens != null && (existingTokens.expiresIn == 0L || existingTokens.expiresIn > System.currentTimeMillis())) {
                    Log.i(
                        TAG,
                        "Valid AppAuth tokens already exist for account ${pendingGoogleAccountId}. Finalizing account setup."
                    )
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
                Log.i(
                    TAG,
                    "No valid AppAuth tokens found for ${pendingGoogleAccountId}. Proceeding with AppAuth flow."
                )

                // Step 3: Initiate AppAuth Authorization Code Flow
                try {
                    // IMPORTANT: Replace with your actual Android Client ID from Google Cloud Console
                    val androidClientId =
                        "326576675855-6vc6rrjhijjfch6j6106sd5ui2htbh61.apps.googleusercontent.com"
                    // This redirect URI must match exactly what's configured in Google Cloud Console for your Android Client ID
                    // and in your AndroidManifest.xml for RedirectUriReceiverActivity.
                    val redirectUri =
                        Uri.parse("net.melisma.mail:/oauth2redirect") // Example, ensure it matches manifest placeholder

                    val requiredScopesString =
                        scopes.joinToString(" ").ifBlank { AppAuthHelperService.GMAIL_SCOPES }

                    Log.d(
                        TAG,
                        "Requesting AppAuth intent for Client ID: $androidClientId, Redirect URI: $redirectUri, Scopes: $requiredScopesString"
                    )

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
                    tryEmitMessage(
                        "Error starting Google authorization: ${
                            errorMapper.mapAuthExceptionToUserMessage(
                                e
                            )
                        }"
                    )
                    _isLoadingAccountAction.value = false
                    resetPendingGoogleAccountState()
                }
            }
            is GoogleSignInResult.Error -> {
                Log.e(
                    TAG,
                    "Google Sign-In (CredentialManager) error: ${signInResult.exception.message}",
                    signInResult.exception
                )
                tryEmitMessage(
                    "Error adding Google account: ${
                        errorMapper.mapAuthExceptionToUserMessage(
                            signInResult.exception
                        )
                    }"
                )
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

    // Helper to update the accounts list
    private fun updateAccountsListWithNewAccount(newAccount: Account) {
        val currentList = _accounts.value.toMutableList()
        currentList.removeAll { it.id == newAccount.id && it.providerType == newAccount.providerType } // Avoid duplicates
        currentList.add(newAccount)
        _accounts.value = currentList // Update the StateFlow
        Log.d(TAG, "Account list updated with: ${newAccount.username}")
    }

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
            Log.e(
                TAG,
                "finalizeGoogleAccountSetupWithAppAuth called but no pendingGoogleAccountId was set."
            )
            tryEmitMessage("Error completing Google setup: Session data missing.")
            _isLoadingAccountAction.value = false
            resetPendingGoogleAccountState() // Clean up
            return
        }

        Log.d(
            TAG,
            "finalizeGoogleAccountSetupWithAppAuth: Processing AppAuth redirect for account ID: $currentAccountId"
        )
        _isLoadingAccountAction.value = true // Start loading

        val authResponse = appAuthHelperService.handleAuthorizationResponse(intentData)
        if (authResponse == null) {
            val appAuthError =
                appAuthHelperService.lastError.value ?: "Unknown AppAuth authorization error."
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
            Log.i(
                TAG,
                "Token exchange successful. Access token received: ${tokenResponse.accessToken != null}"
            )

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
                    username = pendingGoogleDisplayName ?: pendingGoogleEmail
                    ?: "Google User ($currentAccountId)",
                    providerType = "GOOGLE"
                )
                updateAccountsListWithNewAccount(newAccount)
                tryEmitMessage("Google account '${newAccount.username}' successfully added and configured!")
                Log.i(
                    TAG,
                    "Google account setup complete with AppAuth tokens for $currentAccountId."
                )
            } else {
                Log.e(
                    TAG,
                    "Failed to save Google tokens to GoogleTokenPersistenceService for $currentAccountId."
                )
                tryEmitMessage("Critical error: Failed to save Google account credentials securely.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token exchange or saving tokens for $currentAccountId", e)
            tryEmitMessage(
                "Error finalizing Google account setup: ${
                    errorMapper.mapAuthExceptionToUserMessage(
                        e
                    )
                }"
            )
        } finally {
            _isLoadingAccountAction.value = false
            resetPendingGoogleAccountState() // Clean up pending state
        }
    }

    private suspend fun removeGoogleAccount(account: Account) {
        Log.d(
            TAG,
            "removeGoogleAccount (AppAuth Flow): Attempting for account: ${account.username} (ID: ${account.id})"
        )
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
            val clearedLocally =
                googleTokenPersistenceService.clearTokens(account.id, removeAccount = true)
            if (clearedLocally) {
                Log.i(TAG, "Local Google tokens and account entry cleared for ${account.id}.")
            } else {
                Log.w(TAG, "Failed to clear all local Google tokens/account for ${account.id}.")
                message =
                    "Google account '${account.username}' removed, but some local data might persist."
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
            message =
                "Error removing Google account: ${errorMapper.mapAuthExceptionToUserMessage(e)}"
        } finally {
            tryEmitMessage(message)
            _isLoadingAccountAction.value = false
        }
    }
}