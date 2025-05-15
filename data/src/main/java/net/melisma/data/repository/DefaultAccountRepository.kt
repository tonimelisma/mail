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
import kotlinx.coroutines.launch
import net.melisma.backend_google.auth.AppAuthHelperService
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleSignInResult
import net.melisma.backend_google.auth.GoogleTokenPersistenceService
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
import net.melisma.backend_microsoft.auth.AuthStateListener
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
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
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val activeGoogleAccountHolder: net.melisma.backend_google.auth.ActiveGoogleAccountHolder,
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder
) : AccountRepository, AuthStateListener {

    private val TAG = "DefaultAccountRepo_AppAuth" // Specific tag for AppAuth debugging

    private val _authState = MutableStateFlow(determineInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _accounts = MutableStateFlow(
        mapToGenericAccounts(
            microsoftAuthManager.accounts,
            emptyList()
        )
    ) // Initialize with empty Google list
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _isLoadingAccountAction = MutableStateFlow(false)
    override val isLoadingAccountAction: StateFlow<Boolean> = _isLoadingAccountAction.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    // This flow will emit the Intent for AppAuth authorization
    // It's crucial that MainViewModel collects this and passes it to MainActivity
    private val _appAuthAuthorizationIntentInternal = MutableSharedFlow<Intent?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val appAuthAuthorizationIntent: Flow<Intent?> =
        _appAuthAuthorizationIntentInternal.asSharedFlow()


    private var pendingGoogleAccountId: String? = null
    private var pendingGoogleEmail: String? = null
    private var pendingGoogleDisplayName: String? = null
    private var providerTypeForLoadingAction: String? = null


    init {
        Log.d(
            TAG,
            "Initializing DefaultAccountRepository. Injected errorMappers keys: ${errorMappers.keys}"
        )
        Log.d(TAG, "Registering as AuthStateListener for MicrosoftAuthManager")
        microsoftAuthManager.setAuthStateListener(this)
        syncStateFromManager() // This will call onAuthStateChanged

        // Load initial Google accounts
        externalScope.launch {
            Log.d(TAG, "Init: Fetching initial persisted Google accounts.")
            val persistedGoogleAccounts = googleTokenPersistenceService.getAllPersistedAccounts()
            Log.d(TAG, "Init: Found ${persistedGoogleAccounts.size} persisted Google accounts.")
            val currentMsAccounts = microsoftAuthManager.accounts
            _accounts.value = mapToGenericAccounts(currentMsAccounts, persistedGoogleAccounts)
            Log.d(TAG, "Init: Combined accounts list updated. Total: ${_accounts.value.size}")

            // Set active Google account if one exists and none is active
            if (activeGoogleAccountHolder.activeAccountId.value == null && persistedGoogleAccounts.isNotEmpty()) {
                val firstGoogleAccount = persistedGoogleAccounts.first()
                Log.i(
                    TAG,
                    "Init: Setting active Google account from persisted: ${
                        firstGoogleAccount.accountId.take(5)
                    }..."
                )
                activeGoogleAccountHolder.setActiveAccountId(firstGoogleAccount.accountId)
            }
        }
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

        externalScope.launch {
            val persistedGoogleAccounts = googleTokenPersistenceService.getAllPersistedAccounts()
            val newGenericAccounts = mapToGenericAccounts(msAccounts, persistedGoogleAccounts)
            Log.d(
                TAG,
                "onAuthStateChanged (MSAL): Mapped to ${newGenericAccounts.size} generic accounts (MS + existing Google)."
            )
            _accounts.value = newGenericAccounts

            val firstMicrosoftAccount = newGenericAccounts.find { it.providerType == "MS" }
            if (firstMicrosoftAccount != null) {
                if (activeMicrosoftAccountHolder.activeMicrosoftAccountId.value != firstMicrosoftAccount.id) {
                    Log.i(
                        TAG,
                        "onAuthStateChanged (MSAL): Setting active Microsoft account ID: ${firstMicrosoftAccount.id}"
                    )
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(firstMicrosoftAccount.id)
                }
            } else {
                if (activeMicrosoftAccountHolder.activeMicrosoftAccountId.value != null) {
                    Log.i(
                        TAG,
                        "onAuthStateChanged (MSAL): No Microsoft accounts found. Clearing active Microsoft account ID."
                    )
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                }
            }
        }

        if (_isLoadingAccountAction.value && providerTypeForLoadingAction == "MS") {
            Log.d(
                TAG,
                "MSAL AuthState changed, setting isLoadingAccountAction to false for MS action."
            )
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
        }
    }

    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        Log.d(TAG, "addAccount called. ProviderType: $providerType, Scopes: $scopes")
        providerTypeForLoadingAction = providerType.uppercase()
        _isLoadingAccountAction.value = true

        when (providerType.uppercase()) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            "GOOGLE" -> addGoogleAccountWithAppAuth(
                activity,
                scopes
            ) // Changed method name for clarity
            else -> {
                Log.w(TAG, "Unsupported provider type for addAccount: $providerType")
                tryEmitMessage("Unsupported account provider: $providerType")
                _isLoadingAccountAction.value = false
                providerTypeForLoadingAction = null
            }
        }
    }

    @Deprecated(
        "Use addAccount with providerType instead",
        ReplaceWith("addAccount(activity, scopes, \"MS\")")
    )
    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        addAccount(activity, scopes, "MS")
    }


    override suspend fun removeAccount(account: Account) {
        Log.d(
            TAG,
            "removeAccount called for account: ${account.username} (Provider: ${account.providerType})"
        )
        providerTypeForLoadingAction = account.providerType.uppercase()
        _isLoadingAccountAction.value = true

        when (account.providerType.uppercase()) {
            "MS" -> removeMicrosoftAccount(account)
            "GOOGLE" -> removeGoogleAccountWithAppAuth(account) // Changed method name for clarity
            else -> {
                Log.w(
                    TAG,
                    "Cannot remove account with unsupported provider type: ${account.providerType}"
                )
                tryEmitMessage("Cannot remove account with unsupported provider type.")
                _isLoadingAccountAction.value = false
                providerTypeForLoadingAction = null
            }
        }
    }

    override fun clearAccountActionMessage() {
        Log.d(TAG, "clearAccountActionMessage called.")
        tryEmitMessage(null)
    }

    private fun getErrorMapperForProvider(providerType: String): ErrorMapperService? { /* ... (no changes) ... */ return errorMappers[providerType.uppercase()]
    }

    private suspend fun addMicrosoftAccount(
        activity: Activity,
        scopes: List<String>
    ) { /* ... (no changes from previous, ensure activeMicrosoftAccountHolder is set on success) ... */
    }

    private suspend fun removeMicrosoftAccount(account: Account) {
        Log.i(
            TAG,
            "removeMicrosoftAccount: Attempting for account: ${account.username} (ID: ${account.id})"
        )
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            Log.e(
                TAG,
                "removeMicrosoftAccount: Microsoft Error handler not found for account ${account.id}."
            )
            tryEmitMessage("Internal error: Microsoft Error handler not found.")
            _isLoadingAccountAction.value = false // Reset here as we are returning early
            providerTypeForLoadingAction = null
            return
        }
        // _isLoadingAccountAction is already true, set by the calling removeAccount method

        var message: String? = null
        try {
            val accountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }
            if (accountToRemove == null) {
                Log.e(
                    TAG,
                    "Account to remove (ID: ${account.id}) not found in MicrosoftAuthManager's list before removal call."
                )
                message = "Account not found for removal."
                // No need to call MSAL if account not found by manager
            } else {
                Log.d(
                    TAG,
                    "Calling microsoftAuthManager.removeAccount for ${accountToRemove.username}"
                )
                // Collect the Flow from MicrosoftAuthManager
                // This flow emits a single result (Success or Error)
                microsoftAuthManager.removeAccount(accountToRemove)
                    .collect { result -> // Changed from onEach for single emission if that's the case, or use .first()
                        Log.d(TAG, "removeMicrosoftAccount: MSAL removeAccount result: $result")
                        when (result) {
                            is net.melisma.backend_microsoft.auth.RemoveAccountResult.Success -> {
                                message = "Account removed: ${accountToRemove.username}"
                                Log.i(
                                    TAG,
                                    "MSAL account removal successful for ${accountToRemove.username}"
                                )
                                // The AuthStateListener should update the accounts list.
                                // We also need to ensure the active account holder is updated if this was the active account.
                                if (activeMicrosoftAccountHolder.activeMicrosoftAccountId.value == account.id) {
                                    Log.i(
                                        TAG,
                                        "Cleared active Microsoft account ID: ${account.id} as it was removed."
                                    )
                                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                                }
                            }

                            is net.melisma.backend_microsoft.auth.RemoveAccountResult.Error -> {
                                message = "Error removing account: ${
                                    errorMapper.mapAuthExceptionToUserMessage(result.exception)
                                }"
                                Log.e(
                                    TAG,
                                    "MSAL account removal error for ${accountToRemove.username}",
                                    result.exception
                                )
                            }

                            is net.melisma.backend_microsoft.auth.RemoveAccountResult.NotInitialized -> {
                                message = "Authentication system not ready."
                                Log.w(TAG, "MSAL not initialized during removeMicrosoftAccount.")
                            }

                            is net.melisma.backend_microsoft.auth.RemoveAccountResult.AccountNotFound -> {
                                // This case might be redundant if we check accountToRemove existence before calling
                                message = "Account to remove not found by authentication manager."
                                Log.w(
                                    TAG,
                                    "MSAL could not find account ${accountToRemove.username} to remove."
                                )
                            }
                        }
                    }
            }
            // Regardless of MSAL operation outcome, refresh accounts from source of truth
            // onAuthStateChanged will be triggered by MSAL eventually if removal was successful there,
            // which in turn updates _accounts. Here we can force an update based on current MSAL state.
            val currentMsAccounts = microsoftAuthManager.accounts
            val persistedGoogleAccounts = googleTokenPersistenceService.getAllPersistedAccounts()
            _accounts.value = mapToGenericAccounts(currentMsAccounts, persistedGoogleAccounts)
            Log.i(
                TAG,
                "Refreshed accounts list in removeMicrosoftAccount. New MS count: ${currentMsAccounts.size}, Total: ${_accounts.value.size}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Exception in removeMicrosoftAccount for ${account.username}", e)
            message =
                "An unexpected error occurred while removing the Microsoft account: ${e.message}"
        } finally {
            Log.d(
                TAG,
                "removeMicrosoftAccount finally block for ${account.id}. Emitting message: '$message'"
            )
            tryEmitMessage(message)
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
            Log.d(TAG, "isLoadingAccountAction set to false for MS account ${account.id}.")
        }
    }

    private fun tryEmitMessage(message: String?) { /* ... (no changes) ... */
    }

    private fun syncStateFromManager() {
        Log.d(TAG, "syncStateFromManager called.")
        onAuthStateChanged(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.accounts,
            microsoftAuthManager.initializationError
        )
    }

    private fun determineInitialAuthState(): AuthState { /* ... (no changes) ... */ return determineAuthState(
        microsoftAuthManager.isInitialized,
        microsoftAuthManager.initializationError
    )
    }

    private fun determineAuthState(
        isInitialized: Boolean,
        error: MsalException?
    ): AuthState { /* ... (no changes) ... */
        return when {
            !isInitialized && error != null -> AuthState.InitializationError(error)
            !isInitialized && error == null -> AuthState.Initializing
            else -> AuthState.Initialized
        }
    }

    private fun mapToGenericAccounts(
        msalAccounts: List<IAccount>,
        googlePersistedAccounts: List<GoogleTokenPersistenceService.PersistedGoogleAccount>
    ): List<Account> {
        Log.d(
            TAG,
            "mapToGenericAccounts: Mapping ${msalAccounts.size} MSAL accounts and ${googlePersistedAccounts.size} Google accounts."
        )
        val accounts = mutableListOf<Account>()

        msalAccounts.forEach { msalAccount ->
            accounts.add(
                Account(
                    id = msalAccount.id ?: "", // MSAL account ID
                    username = msalAccount.username ?: "Unknown MS User",
                providerType = "MS"
                )
            )
        }

        googlePersistedAccounts.forEach { persistedGoogle ->
            accounts.add(
                Account(
                    id = persistedGoogle.accountId, // This is the Google User ID (sub claim)
                    username = persistedGoogle.displayName ?: persistedGoogle.email
                    ?: "Unknown Google User",
                    providerType = "GOOGLE"
                )
            )
        }
        Log.d(
            TAG,
            "mapToGenericAccounts: Resulting generic accounts: ${accounts.joinToString { "${it.username}(${it.providerType})" }}"
        )
        return accounts.distinctBy { "${it.id}-${it.providerType}" }
    }


    // --- Google AppAuth Flow ---
    private suspend fun addGoogleAccountWithAppAuth(activity: Activity, scopes: List<String>) {
        Log.i(TAG, "addGoogleAccountWithAppAuth: Initiating for scopes: $scopes")
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(TAG, "addGoogleAccountWithAppAuth: Google Error handler not found.")
            tryEmitMessage("Internal error: Google Error handler not found.")
            _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
            return
        }
        // _isLoadingAccountAction is already true

        Log.d(
            TAG,
            "addGoogleAccountWithAppAuth: Calling googleAuthManager.signInWithGoogle (CredentialManager)."
        )
        when (val signInResult = googleAuthManager.signInWithGoogle(activity)) {
            is GoogleSignInResult.Success -> {
                val idTokenCredential = signInResult.idTokenCredential
                Log.i(
                    TAG,
                    "addGoogleAccountWithAppAuth: CredentialManager Sign-In successful. User ID: ${idTokenCredential.id}"
                )

                pendingGoogleAccountId = idTokenCredential.id
                pendingGoogleEmail =
                    googleAuthManager.getEmailFromCredential(idTokenCredential) // This might be null
                pendingGoogleDisplayName = idTokenCredential.displayName
                Log.d(
                    TAG,
                    "addGoogleAccountWithAppAuth: Stored pending Google account details: ID=$pendingGoogleAccountId, Email=$pendingGoogleEmail, DisplayName=$pendingGoogleDisplayName"
                )

                Log.d(
                    TAG,
                    "addGoogleAccountWithAppAuth: Checking for existing AppAuth tokens for account ID: $pendingGoogleAccountId"
                )
                val existingTokens =
                    googleTokenPersistenceService.getTokens(pendingGoogleAccountId!!)
                val isTokenValid =
                    existingTokens != null && (existingTokens.expiresIn == 0L || existingTokens.expiresIn > (System.currentTimeMillis() + 60000)) // Check if valid for at least 1 more minute
                Log.d(
                    TAG,
                    "addGoogleAccountWithAppAuth: Existing tokens for $pendingGoogleAccountId: Present=${existingTokens != null}, Valid=${isTokenValid}, ExpiresAt=${existingTokens?.expiresIn}"
                )

                if (isTokenValid) {
                    Log.i(
                        TAG,
                        "addGoogleAccountWithAppAuth: Valid AppAuth tokens already exist for $pendingGoogleAccountId. Finalizing account setup without new AppAuth flow."
                    )
                    val account = Account(
                        id = pendingGoogleAccountId!!,
                        username = pendingGoogleDisplayName ?: pendingGoogleEmail
                        ?: "Google User (${pendingGoogleAccountId!!.take(5)}...)",
                        providerType = "GOOGLE"
                    )
                    updateAccountsListWithNewAccount(account)
                    activeGoogleAccountHolder.setActiveAccountId(pendingGoogleAccountId)
                    Log.i(
                        TAG,
                        "addGoogleAccountWithAppAuth: Set active Google account ID for Ktor: $pendingGoogleAccountId"
                    )
                    tryEmitMessage("Google account '${account.username}' is already configured.")
                    _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
                    resetPendingGoogleAccountState()
                    return
                }
                Log.i(
                    TAG,
                    "addGoogleAccountWithAppAuth: No valid AppAuth tokens for $pendingGoogleAccountId. Proceeding with AppAuth authorization flow."
                )

                try {
                    val androidClientId =
                        net.melisma.backend_google.BuildConfig.GOOGLE_ANDROID_CLIENT_ID
                    val redirectUri =
                        Uri.parse("net.melisma.mail:/oauth2redirect") // Ensure this matches AndroidManifest and Google Cloud Console
                    val requiredScopesString =
                        scopes.joinToString(" ").ifBlank { AppAuthHelperService.GMAIL_SCOPES }

                    Log.i(
                        TAG,
                        "addGoogleAccountWithAppAuth: Requesting AppAuth intent. ClientID: $androidClientId, Redirect: $redirectUri, Scopes: '$requiredScopesString'"
                    )
                    val authIntent = appAuthHelperService.initiateAuthorizationRequest(
                        activity = activity,
                        clientId = androidClientId,
                        redirectUri = redirectUri,
                        scopes = requiredScopesString
                    )
                    Log.d(
                        TAG,
                        "addGoogleAccountWithAppAuth: AppAuth Intent created: ${authIntent.action}. Emitting to _appAuthAuthorizationIntentInternal."
                    )
                    val emitted = _appAuthAuthorizationIntentInternal.tryEmit(authIntent)
                    if (emitted) {
                        Log.d(
                            TAG,
                            "addGoogleAccountWithAppAuth: AppAuth Intent successfully emitted."
                        )
                        tryEmitMessage("Please follow the prompts to authorize your Google account.")
                        // isLoadingAccountAction remains true until finalizeGoogleAccountSetupWithAppAuth completes or errors
                    } else {
                        Log.e(
                            TAG,
                            "addGoogleAccountWithAppAuth: Failed to emit AppAuth Intent to flow. This is a problem!"
                        )
                        tryEmitMessage("Error initiating Google authorization: Could not start process.")
                        _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
                        resetPendingGoogleAccountState()
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "addGoogleAccountWithAppAuth: Failed to initiate AppAuth authorization request",
                        e
                    )
                    tryEmitMessage(
                        "Error starting Google authorization: ${
                            errorMapper.mapAuthExceptionToUserMessage(
                                e
                            )
                        }"
                    )
                    _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
                    resetPendingGoogleAccountState()
                }
            }
            is GoogleSignInResult.Error -> {
                Log.e(
                    TAG,
                    "addGoogleAccountWithAppAuth: CredentialManager Sign-In error: ${signInResult.exception.message}",
                    signInResult.exception
                )
                tryEmitMessage(
                    "Error adding Google account: ${
                        errorMapper.mapAuthExceptionToUserMessage(
                            signInResult.exception
                        )
                    }"
                )
                _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
                resetPendingGoogleAccountState()
            }
            is GoogleSignInResult.Cancelled -> {
                Log.d(TAG, "addGoogleAccountWithAppAuth: CredentialManager Sign-In was cancelled.")
                tryEmitMessage("Google account addition cancelled.")
                _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
                resetPendingGoogleAccountState()
            }
            is GoogleSignInResult.NoCredentialsAvailable -> {
                Log.d(
                    TAG,
                    "addGoogleAccountWithAppAuth: No Google credentials available for CredentialManager."
                )
                tryEmitMessage("No Google accounts found on this device. Please add one via device settings.")
                _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
                resetPendingGoogleAccountState()
            }
        }
    }

    // Public to be called by ViewModel
    fun resetPendingGoogleAccountState() {
        Log.d(
            TAG,
            "resetPendingGoogleAccountState called. Clearing pendingGoogleAccountId, Email, DisplayName."
        )
        pendingGoogleAccountId = null
        pendingGoogleEmail = null
        pendingGoogleDisplayName = null
    }

    private fun updateAccountsListWithNewAccount(newAccount: Account) {
        Log.d(
            TAG,
            "updateAccountsListWithNewAccount called for: ${newAccount.username} (${newAccount.providerType})"
        )
        externalScope.launch { // Ensure updates happen on a coroutine
            val currentGoogleAccounts = googleTokenPersistenceService.getAllPersistedAccounts()
            val currentMsAccounts = microsoftAuthManager.accounts // Get current MSAL accounts
            _accounts.value = mapToGenericAccounts(currentMsAccounts, currentGoogleAccounts)
            Log.i(
                TAG,
                "Account list updated. New count: ${_accounts.value.size}. Accounts: ${_accounts.value.joinToString { "${it.username}(${it.providerType})" }}"
            )
        }
    }


    suspend fun finalizeGoogleAccountSetupWithAppAuth(intentData: Intent) {
        Log.i(
            TAG,
            "finalizeGoogleAccountSetupWithAppAuth called with intent data. Action: ${intentData.action}"
        )
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) { /* ... (error handling as before) ... */ return
        }

        val currentAccountId = pendingGoogleAccountId
        if (currentAccountId == null) { /* ... (error handling as before) ... */ return
        }

        Log.d(
            TAG,
            "Finalizing AppAuth for account ID: $currentAccountId. Email: $pendingGoogleEmail, Name: $pendingGoogleDisplayName"
        )
        // isLoadingAccountAction should still be true

        val authResponse = appAuthHelperService.handleAuthorizationResponse(intentData)
        if (authResponse == null) {
            val appAuthError =
                appAuthHelperService.lastError.value ?: "Unknown AppAuth authorization error."
            Log.e(TAG, "AppAuth authorization response was null or error: $appAuthError")
            tryEmitMessage("Google authorization failed: $appAuthError")
            _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
            resetPendingGoogleAccountState()
            return
        }
        Log.d(
            TAG,
            "AppAuth authorization response handled. AuthCode: ${
                authResponse.authorizationCode?.take(10)
            }..."
        )

        try {
            Log.d(TAG, "Exchanging AppAuth code for tokens for $currentAccountId...")
            val tokenResponse = appAuthHelperService.performTokenRequest(authResponse)
            Log.i(
                TAG,
                "AppAuth Token exchange successful for $currentAccountId. AccessToken: ${tokenResponse.accessToken != null}, RefreshToken: ${tokenResponse.refreshToken != null}"
            )

            Log.d(
                TAG,
                "Saving AppAuth tokens via GoogleTokenPersistenceService for $currentAccountId."
            )
            val success = googleTokenPersistenceService.saveTokens(
                accountId = currentAccountId,
                tokenResponse = tokenResponse,
                email = pendingGoogleEmail, // Pass along initially fetched details
                displayName = pendingGoogleDisplayName
            )

            if (success) {
                Log.i(TAG, "Google AppAuth tokens saved successfully for $currentAccountId.")
                // Fetch the possibly updated display name/email from persistence service if it parses ID token
                val finalAccountInfo =
                    googleTokenPersistenceService.getAccountInfo(currentAccountId)
                val finalDisplayName =
                    finalAccountInfo["displayName"] ?: pendingGoogleDisplayName ?: "Google User"
                finalAccountInfo["email"] ?: pendingGoogleEmail

                val newAccount = Account(
                    id = currentAccountId,
                    username = finalDisplayName,
                    providerType = "GOOGLE"
                )
                updateAccountsListWithNewAccount(newAccount) // This will re-fetch all and update _accounts
                activeGoogleAccountHolder.setActiveAccountId(currentAccountId)
                Log.i(TAG, "Set active Google account ID for Ktor Auth plugin: $currentAccountId")
                tryEmitMessage("Google account '${newAccount.username}' successfully added and configured!")
                Log.i(TAG, "Google account setup complete with AppAuth for $currentAccountId.")
            } else {
                Log.e(TAG, "Failed to save Google AppAuth tokens for $currentAccountId.")
                tryEmitMessage("Critical error: Failed to save Google account credentials securely.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during AppAuth token exchange/saving for $currentAccountId", e)
            tryEmitMessage(
                "Error finalizing Google account setup: ${
                    errorMapper.mapAuthExceptionToUserMessage(
                        e
                    )
                }"
            )
        } finally {
            Log.d(
                TAG,
                "Finalizing Google AppAuth setup for $currentAccountId, setting isLoadingAccountAction to false."
            )
            _isLoadingAccountAction.value = false; providerTypeForLoadingAction = null
            resetPendingGoogleAccountState()
        }
    }

    private suspend fun removeGoogleAccountWithAppAuth(account: Account) {
        Log.i(
            TAG,
            "removeGoogleAccountWithAppAuth: Attempting for account: ${account.username} (ID: ${account.id})"
        )
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(
                TAG,
                "removeGoogleAccountWithAppAuth: Google Error handler not found for account ${account.id}."
            )
            tryEmitMessage("Internal error: Google Error handler not found.")
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
            return
        }
        // _isLoadingAccountAction is already true

        var message = "Google account '${account.username}' removed."
        try {
            Log.d(TAG, "TODO: Implement server-side token revocation if needed for ${account.id}")
            // Example: appAuthHelperService.revokeToken(tokenToRevoke, tokenTypeHint)

            Log.d(
                TAG,
                "Clearing local Google tokens and account entry via persistence service for ${account.id}."
            )
            val clearedLocally =
                googleTokenPersistenceService.clearTokens(account.id, removeAccount = true)
            if (clearedLocally) {
                Log.i(TAG, "Local Google tokens and account entry cleared for ${account.id}.")
            } else {
                Log.w(TAG, "Failed to clear all local Google tokens/account for ${account.id}.")
                message =
                    "Google account '${account.username}' removed, but some local data might persist."
            }

            Log.d(
                TAG,
                "Attempting to call googleAuthManager.signOut() for ${account.id}."
            )
            try {
                googleAuthManager.signOut() // This clears Credential Manager state.
                Log.i(
                    TAG,
                    "googleAuthManager.signOut() completed for ${account.id} (from coroutine's perspective)."
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Exception directly from googleAuthManager.signOut() call for ${account.id}. This might be the AndroidX Credentials NPE if it's catchable here.",
                    e
                )
                // message will be overridden by the outer catch block if this is the primary error source.
                // If the NPE is a Binder exception, it might not be caught here, leading to the coroutine potentially hanging or being killed.
            }
            Log.d(TAG, "Proceeding after googleAuthManager.signOut() attempt for ${account.id}.")


            if (activeGoogleAccountHolder.activeAccountId.value == account.id) {
                Log.d(TAG, "Clearing active Google account ID: ${account.id}")
                activeGoogleAccountHolder.setActiveAccountId(null)
            }

            Log.d(TAG, "Updating internal accounts list after removing ${account.username}.")
            // Re-fetch all accounts to update the list correctly
            val currentMsAccounts = microsoftAuthManager.accounts
            val currentGoogleAccounts =
                googleTokenPersistenceService.getAllPersistedAccounts() // Should not include the removed one
            _accounts.value = mapToGenericAccounts(currentMsAccounts, currentGoogleAccounts)
            Log.i(
                TAG,
                "Account list updated after removing ${account.username}. New count: ${_accounts.value.size}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error removing Google account ${account.id} in outer catch block.", e)
            message =
                "Error removing Google account: ${errorMapper.mapAuthExceptionToUserMessage(e)}"
        } finally {
            Log.d(
                TAG,
                "removeGoogleAccountWithAppAuth finally block for ${account.id}. Message: '$message'"
            )
            tryEmitMessage(message)
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
            Log.d(TAG, "isLoadingAccountAction set to false for ${account.id}.")
        }
    }
}
