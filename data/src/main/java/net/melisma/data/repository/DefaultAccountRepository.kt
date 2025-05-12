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
// Import for ActiveMicrosoftAccountHolder - Ensure this path is correct for your project structure
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
import net.melisma.backend_microsoft.auth.AddAccountResult
import net.melisma.backend_microsoft.auth.AuthStateListener // Make sure this interface is accessible
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
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val activeGoogleAccountHolder: net.melisma.backend_google.auth.ActiveGoogleAccountHolder,
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder // <<< STEP 1: Added Injection
) : AccountRepository, AuthStateListener {

    private val TAG = "DefaultAccountRepo"

    private val _authState = MutableStateFlow(determineInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // For now, mapToGenericAccounts will only process MSAL accounts
    // to avoid issues with googleTokenPersistenceService.getAllPersistedAccounts()
    // until that part is confirmed/fixed separately.
    private val _accounts = MutableStateFlow(mapToGenericAccounts(microsoftAuthManager.accounts))
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _isLoadingAccountAction = MutableStateFlow(false)
    override val isLoadingAccountAction: StateFlow<Boolean> = _isLoadingAccountAction.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    private val _appAuthAuthorizationIntent = MutableSharedFlow<Intent?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val appAuthAuthorizationIntent: Flow<Intent?> = _appAuthAuthorizationIntent.asSharedFlow()

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

        // For now, mapToGenericAccounts will only process MSAL accounts
        val newGenericAccounts = mapToGenericAccounts(msAccounts)
        Log.d(
            TAG,
            "onAuthStateChanged: Mapped to ${newGenericAccounts.size} generic accounts from MSAL."
        )
        _accounts.value = newGenericAccounts

        // --- START OF STEP 2 CHANGE ---
        // Update ActiveMicrosoftAccountHolder based on the new list of MS accounts
        val firstMicrosoftAccount =
            newGenericAccounts.find { it.providerType == "MS" } // Should always be MS if from msalAccounts
        if (firstMicrosoftAccount != null) {
            Log.i(
                TAG,
                "onAuthStateChanged: Setting active Microsoft account ID: ${firstMicrosoftAccount.id}"
            )
            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(firstMicrosoftAccount.id)
        } else {
            val currentActiveMsId = activeMicrosoftAccountHolder.activeMicrosoftAccountId.value
            if (currentActiveMsId != null) {
                Log.i(
                    TAG,
                    "onAuthStateChanged: No Microsoft accounts found. Clearing active Microsoft account ID."
                )
                activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
            }
        }
        // --- END OF STEP 2 CHANGE ---

        // Only set loading to false if it was true for an MS action.
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
        providerTypeForLoadingAction = providerType.uppercase() // Track which provider is loading
        _isLoadingAccountAction.value = true // Set loading true at the start

        when (providerType.uppercase()) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            "GOOGLE" -> addGoogleAccount(
                activity,
                scopes
            ) // Google flow manages its own isLoading more granularly
            else -> {
                Log.w(TAG, "Unsupported provider type for addAccount: $providerType")
                tryEmitMessage("Unsupported account provider: $providerType")
                _isLoadingAccountAction.value = false // Reset loading if unsupported
                providerTypeForLoadingAction = null
            }
        }
    }

    @Deprecated(
        "Use addAccount with providerType instead",
        ReplaceWith("addAccount(activity, scopes, \"MS\")")
    )
    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        Log.d(
            TAG,
            "addAccount (legacy overload) called, defaulting to MS provider. Scopes: $scopes"
        )
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
            "GOOGLE" -> removeGoogleAccount(account)
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
            providerTypeForLoadingAction = null
            return
        }

        // _isLoadingAccountAction is already true from public addAccount method

        Log.d(TAG, "addMicrosoftAccount: Calling MicrosoftAuthManager.addAccount...")
        microsoftAuthManager.addAccount(activity, scopes)
            .onEach { result ->
                Log.d(TAG, "addMicrosoftAccount: Result from MicrosoftAuthManager: $result")
                val message: String
                when (result) {
                    is AddAccountResult.Success -> {
                        message = "Microsoft account added: ${result.account.username}"
                        // --- START OF STEP 3 CHANGE ---
                        Log.i(
                            TAG,
                            "addMicrosoftAccount: New MS account successfully added. Setting active Microsoft account ID: ${result.account.id}"
                        )
                        activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(result.account.id)
                        // --- END OF STEP 3 CHANGE ---
                        // onAuthStateChanged will be called by MSAL to update the main accounts list
                    }

                    is AddAccountResult.Error -> {
                        message = "Error adding Microsoft account: ${
                            errorMapper.mapAuthExceptionToUserMessage(result.exception)
                        }"
                    }

                    is AddAccountResult.Cancelled -> {
                        message = "Microsoft account addition cancelled."
                    }

                    is AddAccountResult.NotInitialized -> {
                        message = "Microsoft authentication system not ready."
                    }
                }
                tryEmitMessage(message)
                // isLoadingAccountAction will be set to false by onAuthStateChanged for MS actions
            }
            .catch { e ->
                Log.e(TAG, "Exception in addMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
                _isLoadingAccountAction.value = false // Ensure loading stops on direct exception
                providerTypeForLoadingAction = null
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
            providerTypeForLoadingAction = null
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
            providerTypeForLoadingAction = null
            return
        }
        // _isLoadingAccountAction is already true

        Log.d(
            TAG,
            "removeMicrosoftAccount: Calling MicrosoftAuthManager.removeAccount for MSAL account: ${msalAccountToRemove.username}"
        )
        microsoftAuthManager.removeAccount(msalAccountToRemove)
            .onEach { result ->
                Log.d(TAG, "removeMicrosoftAccount: Result from MicrosoftAuthManager: $result")
                val message: String
                when (result) {
                    is RemoveAccountResult.Success -> {
                        message = "Microsoft account removed: ${msalAccountToRemove.username}"
                        // --- START OF STEP 4 CHANGE (Optional but good) ---
                        if (activeMicrosoftAccountHolder.activeMicrosoftAccountId.value == msalAccountToRemove.id) {
                            Log.i(
                                TAG,
                                "removeMicrosoftAccount: Removed account was the active MS account. Clearing activeMicrosoftAccountId."
                            )
                            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                        }
                        // --- END OF STEP 4 CHANGE ---
                        // onAuthStateChanged will be called by MSAL to update the main accounts list
                        // and re-confirm/clear the activeMicrosoftAccountHolder.
                    }

                    is RemoveAccountResult.Error -> {
                        message = "Error removing Microsoft account: ${
                            errorMapper.mapAuthExceptionToUserMessage(result.exception)
                        }"
                    }

                    is RemoveAccountResult.NotInitialized -> {
                        message = "Microsoft authentication system not ready."
                    }

                    is RemoveAccountResult.AccountNotFound -> {
                        message = "Microsoft account to remove not found by MS auth manager."
                    }
                }
                tryEmitMessage(message)
                // isLoadingAccountAction will be set to false by onAuthStateChanged for MS actions
            }
            .catch { e ->
                Log.e(TAG, "Exception in removeMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
                _isLoadingAccountAction.value = false // Ensure loading stops on direct exception
                providerTypeForLoadingAction = null
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
        // This will trigger onAuthStateChanged which now handles setting the active MS account
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

    // Reverted to only process MSAL accounts to avoid previous build errors with Google persistence.
    // You will need to integrate Google accounts here once GoogleTokenPersistenceService is stable.
    private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
        Log.d(TAG, "mapToGenericAccounts: Mapping ${msalAccounts.size} MSAL accounts.")
        val accounts = msalAccounts.map { msalAccount ->
            Account(
                id = msalAccount.id ?: "",
                username = msalAccount.username
                    ?: "Unknown MS User", // Corrected from "Unknown User"
                providerType = "MS"
            )
        }.toMutableList()

        // TODO: Integrate Google accounts here once GoogleTokenPersistenceService.getAllPersistedAccounts() is confirmed.
        // For example:
        // val googlePersisted = googleTokenPersistenceService.getAllPersistedAccounts()
        // googlePersisted.forEach { persisted ->
        //     accounts.add(Account(id = persisted.accountId, username = persisted.displayName ?: persisted.email ?: "Unknown Google", providerType = "GOOGLE"))
        // }
        // return accounts.distinctBy { "${it.id}-${it.providerType}" }

        return accounts.also {
            Log.d(
                TAG,
                "mapToGenericAccounts: Resulting generic accounts (MS only for now): ${it.joinToString { acc -> acc.username }}"
            )
        }
    }

    private suspend fun addGoogleAccount(activity: Activity, scopes: List<String>) {
        Log.i(TAG, "addGoogleAccount (AppAuth Flow): Initiating for scopes: $scopes")
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(TAG, "addGoogleAccount: Google Error handler not found.")
            tryEmitMessage("Internal error: Google Error handler not found.")
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
            return
        }
        // _isLoadingAccountAction is already true

        Log.d(TAG, "addGoogleAccount: Calling googleAuthManager.signInWithGoogle.")
        when (val signInResult = googleAuthManager.signInWithGoogle(activity)) {
            is GoogleSignInResult.Success -> {
                val idTokenCredential = signInResult.idTokenCredential
                Log.i(
                    TAG,
                    "Google Sign-In (CredentialManager) successful. User ID: ${idTokenCredential.id}"
                )

                pendingGoogleAccountId = idTokenCredential.id
                pendingGoogleEmail = googleAuthManager.getEmailFromCredential(idTokenCredential)
                pendingGoogleDisplayName = idTokenCredential.displayName
                Log.d(
                    TAG,
                    "Stored pending Google account details: ID=$pendingGoogleAccountId, Email=$pendingGoogleEmail, DisplayName=$pendingGoogleDisplayName"
                )

                Log.d(
                    TAG,
                    "Checking for existing AppAuth tokens for account ID: $pendingGoogleAccountId"
                )
                val existingTokens =
                    googleTokenPersistenceService.getTokens(pendingGoogleAccountId!!)
                val isTokenValid =
                    existingTokens != null && (existingTokens.expiresIn == 0L || existingTokens.expiresIn > System.currentTimeMillis())
                Log.d(
                    TAG,
                    "Existing tokens for $pendingGoogleAccountId: Present=${existingTokens != null}, Valid (non-expired)=${isTokenValid}, ExpiresIn=${existingTokens?.expiresIn}"
                )

                if (isTokenValid) {
                    Log.i(
                        TAG,
                        "Valid AppAuth tokens already exist for account ${pendingGoogleAccountId}. Finalizing account setup."
                    )
                    val account = Account(
                        id = pendingGoogleAccountId!!,
                        username = pendingGoogleDisplayName ?: pendingGoogleEmail ?: "Google User",
                        providerType = "GOOGLE"
                    )
                    updateAccountsListWithNewAccount(account)
                    activeGoogleAccountHolder.setActiveAccountId(pendingGoogleAccountId)
                    Log.i(
                        TAG,
                        "Set active Google account ID for Ktor Auth plugin: $pendingGoogleAccountId"
                    )
                    tryEmitMessage("Google account '${account.username}' is already configured.")
                    _isLoadingAccountAction.value = false
                    providerTypeForLoadingAction = null
                    resetPendingGoogleAccountState()
                    return
                }
                Log.i(
                    TAG,
                    "No valid AppAuth tokens found for ${pendingGoogleAccountId}. Proceeding with AppAuth flow."
                )

                try {
                    val androidClientId =
                        net.melisma.backend_google.BuildConfig.GOOGLE_ANDROID_CLIENT_ID
                    val redirectUri = Uri.parse("net.melisma.mail:/oauth2redirect")
                    val requiredScopesString =
                        scopes.joinToString(" ").ifBlank { AppAuthHelperService.GMAIL_SCOPES }

                    Log.d(
                        TAG,
                        "Requesting AppAuth intent for Client ID: $androidClientId, Redirect URI: $redirectUri, Scopes: $requiredScopesString"
                    )
                    val authIntent = appAuthHelperService.initiateAuthorizationRequest(
                        activity = activity,
                        clientId = androidClientId,
                        redirectUri = redirectUri,
                        scopes = requiredScopesString
                    )
                    _appAuthAuthorizationIntent.tryEmit(authIntent)
                    tryEmitMessage("Please follow the prompts to authorize your Google account.")
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
                    providerTypeForLoadingAction = null
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
                providerTypeForLoadingAction = null
                resetPendingGoogleAccountState()
            }
            is GoogleSignInResult.Cancelled -> {
                Log.d(TAG, "Google Sign-In (CredentialManager) was cancelled.")
                tryEmitMessage("Google account addition cancelled.")
                _isLoadingAccountAction.value = false
                providerTypeForLoadingAction = null
                resetPendingGoogleAccountState()
            }
            is GoogleSignInResult.NoCredentialsAvailable -> {
                Log.d(TAG, "No Google credentials available for CredentialManager sign-in.")
                tryEmitMessage("No Google accounts found on this device. Please add one via device settings.")
                _isLoadingAccountAction.value = false
                providerTypeForLoadingAction = null
                resetPendingGoogleAccountState()
            }
        }
    }

    private fun resetPendingGoogleAccountState() {
        Log.d(TAG, "resetPendingGoogleAccountState called.")
        pendingGoogleAccountId = null
        pendingGoogleEmail = null
        pendingGoogleDisplayName = null
    }

    private fun updateAccountsListWithNewAccount(newAccount: Account) {
        Log.d(
            TAG,
            "updateAccountsListWithNewAccount called for: ${newAccount.username} (${newAccount.providerType})"
        )
        val currentList = _accounts.value.toMutableList()
        currentList.removeAll { it.id == newAccount.id && it.providerType == newAccount.providerType }
        currentList.add(newAccount)
        _accounts.value = currentList.toList()
        Log.i(
            TAG,
            "Account list updated. New count: ${_accounts.value.size}. Accounts: ${_accounts.value.joinToString { "${it.username}(${it.providerType})" }}"
        )
    }


    suspend fun finalizeGoogleAccountSetupWithAppAuth(intentData: Intent) {
        Log.i(TAG, "finalizeGoogleAccountSetupWithAppAuth called with intent data.")
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(TAG, "finalizeGoogleAccountSetupWithAppAuth: Google Error handler not found.")
            tryEmitMessage("Internal error: Google Error handler not found.")
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
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
            providerTypeForLoadingAction = null
            resetPendingGoogleAccountState()
            return
        }

        Log.d(
            TAG,
            "finalizeGoogleAccountSetupWithAppAuth: Processing AppAuth redirect for account ID: $currentAccountId. Email: $pendingGoogleEmail, Name: $pendingGoogleDisplayName"
        )
        // isLoadingAccountAction should have been set to true by addGoogleAccount

        val authResponse = appAuthHelperService.handleAuthorizationResponse(intentData)
        if (authResponse == null) {
            val appAuthError =
                appAuthHelperService.lastError.value ?: "Unknown AppAuth authorization error."
            Log.e(TAG, "AppAuth authorization response was null or error: $appAuthError")
            tryEmitMessage("Google authorization failed: $appAuthError")
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
            resetPendingGoogleAccountState()
            return
        }
        Log.d(
            TAG,
            "AppAuth authorization response handled successfully. AuthCode: ${
                authResponse.authorizationCode?.take(10)
            }..."
        )

        try {
            Log.d(
                TAG,
                "Exchanging authorization code for tokens via AppAuthHelperService for $currentAccountId..."
            )
            val tokenResponse = appAuthHelperService.performTokenRequest(authResponse)
            val hasAccessToken = tokenResponse.accessToken?.isNotEmpty() == true
            Log.i(
                TAG,
                "Token exchange successful for $currentAccountId. Access token received: $hasAccessToken, RefreshToken Present: ${tokenResponse.refreshToken != null}, IDToken Present: ${tokenResponse.idToken != null}"
            )

            Log.d(TAG, "Saving tokens to GoogleTokenPersistenceService for $currentAccountId.")
            val success = googleTokenPersistenceService.saveTokens(
                accountId = currentAccountId,
                tokenResponse = tokenResponse,
                email = pendingGoogleEmail,
                displayName = pendingGoogleDisplayName
            )

            if (success) {
                Log.i(TAG, "Google tokens saved successfully for $currentAccountId.")
                val newAccount = Account(
                    id = currentAccountId,
                    username = pendingGoogleDisplayName ?: pendingGoogleEmail
                    ?: "Google User ($currentAccountId)",
                    providerType = "GOOGLE"
                )
                updateAccountsListWithNewAccount(newAccount)
                activeGoogleAccountHolder.setActiveAccountId(currentAccountId)
                Log.i(TAG, "Set active Google account ID for Ktor Auth plugin: $currentAccountId")
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
            Log.d(
                TAG,
                "Finalizing Google account setup for $currentAccountId, setting isLoadingAccountAction to false."
            )
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
            resetPendingGoogleAccountState()
        }
    }

    private suspend fun removeGoogleAccount(account: Account) {
        Log.i(
            TAG,
            "removeGoogleAccount (AppAuth Flow): Attempting for account: ${account.username} (ID: ${account.id})"
        )
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(TAG, "removeGoogleAccount: Google Error handler not found.")
            tryEmitMessage("Internal error: Google Error handler not found.")
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
            return
        }
        // _isLoadingAccountAction is already true

        var message = "Google account '${account.username}' removed."

        try {
            Log.d(TAG, "Server-side token revocation (TODO) for ${account.id}")

            Log.d(TAG, "Clearing local Google tokens and account entry for ${account.id}.")
            val clearedLocally =
                googleTokenPersistenceService.clearTokens(account.id, removeAccount = true)
            if (clearedLocally) {
                Log.i(TAG, "Local Google tokens and account entry cleared for ${account.id}.")
            } else {
                Log.w(TAG, "Failed to clear all local Google tokens/account for ${account.id}.")
                message =
                    "Google account '${account.username}' removed, but some local data might persist."
            }

            Log.d(TAG, "Calling googleAuthManager.signOut() to clear CredentialManager state.")
            googleAuthManager.signOut()
            Log.i(TAG, "CredentialManager state cleared for Google Sign-Out.")

            if (activeGoogleAccountHolder.activeAccountId.value == account.id) {
                Log.d(
                    TAG,
                    "Clearing active Google account ID as it matches the removed account: ${account.id}"
                )
                activeGoogleAccountHolder.setActiveAccountId(null)
            }

            Log.d(TAG, "Updating internal accounts list after removing ${account.username}.")
            val currentList = _accounts.value.toMutableList()
            val removed =
                currentList.removeAll { it.id == account.id && it.providerType == "GOOGLE" }
            if (removed) {
                _accounts.value = currentList.toList()
                Log.i(
                    TAG,
                    "Account ${account.username} removed from repository's list. New count: ${_accounts.value.size}"
                )
            } else {
                Log.w(
                    TAG,
                    "Account ${account.username} was not found in the repository's list to remove."
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error removing Google account ${account.id}", e)
            message =
                "Error removing Google account: ${errorMapper.mapAuthExceptionToUserMessage(e)}"
        } finally {
            tryEmitMessage(message)
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
        }
    }
}
