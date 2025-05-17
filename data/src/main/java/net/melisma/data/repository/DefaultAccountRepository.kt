// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import net.melisma.backend_google.auth.AppAuthHelperService
import net.melisma.backend_google.auth.GoogleTokenPersistenceService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.TokenResponse
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import net.melisma.backend_google.BuildConfig as GoogleBuildConfig

// Companion object for constants
private const val GMAIL_API_SCOPE_BASE = "https://www.googleapis.com/auth/gmail."
private val GMAIL_SCOPES_FOR_LOGIN = listOf(
    "${GMAIL_API_SCOPE_BASE}readonly", // View your messages and settings
    "${GMAIL_API_SCOPE_BASE}modify",   // Modify but not delete messages (e.g., mark read/unread)
    "${GMAIL_API_SCOPE_BASE}labels"    // Manage your labels (folders)
    // Add other Gmail scopes as needed, e.g., send, metadata
)

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAccountRepository: MicrosoftAccountRepository,
    private val appAuthHelperService: AppAuthHelperService,
    private val googleTokenPersistenceService: GoogleTokenPersistenceService,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val activeGoogleAccountHolder: ActiveGoogleAccountHolder
) : AccountRepository /*, AuthStateListener */ {

    private val TAG = "DefaultAccountRepo_AppAuth"

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    // This Flow is an internal detail for Google AppAuth. Not part of AccountRepository interface.
    private val _googleAuthRequestIntent = MutableSharedFlow<Intent?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val appAuthAuthorizationIntent: Flow<Intent?> = _googleAuthRequestIntent.asSharedFlow()

    private val _overallApplicationAuthState =
        MutableStateFlow<OverallApplicationAuthState>(OverallApplicationAuthState.UNKNOWN)
    val overallApplicationAuthState: StateFlow<OverallApplicationAuthState> =
        _overallApplicationAuthState.asStateFlow()

    // Keep track of Google accounts internally for now
    private val _googleAccounts = MutableStateFlow<List<Account>>(emptyList())

    init {
        Timber.tag(TAG)
            .d("Initializing DefaultAccountRepository. Injected errorMappers keys: ${errorMappers.keys}")
        // microsoftAuthManager.setAuthStateListener(this) // This was for the OLD AuthStateListener. Review if MSAL part needs new listener.

        externalScope.launch {
            Timber.tag(TAG).d("Init: Fetching initial persisted Google accounts.")
            val persistedGoogleAccounts = fetchPersistedGoogleAccounts()
            _googleAccounts.value = persistedGoogleAccounts // Initialize internal Google accounts
            Timber.tag(TAG)
                .d("Init: Found ${persistedGoogleAccounts.size} persisted Google accounts.")
            // TODO: Properly initialize MS accounts and merge with Google accounts
            // val currentMsAccounts = microsoftAuthManager.accounts // How to get MS accounts for init?
            // _accounts.value = mapToGenericAccounts(currentMsAccounts, persistedGoogleAccounts) // mapToGenericAccounts needs review for MS
            _accounts.value = persistedGoogleAccounts // Simplified for now
            Timber.tag(TAG)
                .d("Init: Combined accounts list updated (currently Google only). Total: ${_accounts.value.size}")

            if (activeGoogleAccountHolder.getActiveAccountIdValue() == null && persistedGoogleAccounts.isNotEmpty()) {
                val firstGoogleAccount = persistedGoogleAccounts.first()
                Timber.tag(TAG).i(
                    "Init: Setting active Google account from persisted: ${
                        firstGoogleAccount.username.take(5)
                    }..."
                )
                activeGoogleAccountHolder.setActiveAccountId(firstGoogleAccount.id)
            }
            // Initial update for overall auth state and combined accounts list
            updateCombinedAccountsAndOverallAuthState() // Call the new function
        }
    }

    private fun mapGoogleUserToGenericAccount(
        googleUserId: String,
        email: String?,
        displayName: String?,
        // photoUrl: String? // photoUrl is not used by the current Account model, as per original comment
    ): Account {
        Timber.tag(TAG)
            .d("mapGoogleUserToGenericAccount called for Google User ID: ${googleUserId.take(5)}..., Email: $email")
        val accountUsername = displayName ?: email ?: "Google User (${googleUserId.take(6)}...)"
        return Account(
            id = googleUserId,
            username = accountUsername,
            providerType = Account.PROVIDER_TYPE_GOOGLE // Assuming Account.PROVIDER_TYPE_GOOGLE exists
        )
    }

    private suspend fun fetchPersistedGoogleAccounts(): List<Account> {
        return googleTokenPersistenceService.getAllGoogleUserInfos().mapNotNull { userInfo ->
            mapGoogleUserToGenericAccount( // Updated to use the local private function
                googleUserId = userInfo.id,
                email = userInfo.email,
                displayName = userInfo.displayName
                // photoUrl = userInfo.photoUrl // Not passed as it's not used
            )
        }
    }

    // onAuthStateChanged (from AuthStateListener - this whole method might be obsolete or need heavy rework if AuthStateListener is removed)
    // For now, I'll comment it out as its tied to the old AuthState and MSAL direct listening.
    /*
    override fun onAuthStateChanged(
        isInitialized: Boolean,
        msAccounts: List<IAccount>,
        error: MsalException?
    ) {
        Timber.tag(TAG).d(
            "onAuthStateChanged (MSAL): init=$isInitialized, msAccountCount=${msAccounts.size}, errorPresent=${error != null}"
        )
        // _internalAuthState.value = determineAuthState(isInitialized, error) // OLD state

        externalScope.launch {
            val persistedGoogleAccounts = fetchPersistedGoogleAccounts()
            // val newGenericAccounts = mapToGenericAccounts(msAccounts, persistedGoogleAccounts) // mapToGenericAccounts needs review for MS
            // Timber.tag(TAG).d(
            //     "onAuthStateChanged (MSAL): Mapped to ${newGenericAccounts.size} generic accounts (MS + existing Google)."
            // )
            // _accounts.value = newGenericAccounts

            // Logic for activeMicrosoftAccountHolder... needs review based on how MS accounts are managed now

        }

        if (_isLoadingAccountAction.value && providerTypeForLoadingAction == "MS") {
            Timber.tag(TAG).d(
                "MSAL AuthState changed, setting isLoadingAccountAction to false for MS action."
            )
            _isLoadingAccountAction.value = false
            providerTypeForLoadingAction = null
        }
        updateOverallAuthState() // Update new overall state
    }
    */

    // Old addAccount methods - these are effectively replaced by getAuthenticationIntentRequest and handleAuthenticationResult
    // I will comment them out for now. They contain logic that needs to be migrated or verified if still applicable.
    /*
    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        Timber.tag(TAG).d("OLD addAccount called. ProviderType: $providerType, Scopes: $scopes")
        // ...
    }

    @Deprecated(
        "Use addAccount with providerType instead",
        ReplaceWith("addAccount(activity, scopes, \\\"MS\\\")")
    )
    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        addAccount(activity, scopes, "MS")
    }
    */

    // Old removeAccount method - replaced by the new signOut
    /*
    override suspend fun removeAccount(account: Account) {
        Timber.tag(TAG).d(
            "OLD removeAccount called for account: ${account.username} (Provider: ${account.providerType})"
        )
        // ...
    }
    */

    // getErrorMapperForProvider (utility, likely still useful)
    private fun getErrorMapperForProvider(providerType: String): ErrorMapperService? {
        return errorMappers[providerType.uppercase()]
    }

    // addMicrosoftAccount (old internal helper for old addAccount)
    // This logic needs to be integrated into the new handleAuthenticationResult for MS or triggered differently.
    // Commenting out for now.
    /*
    private suspend fun addMicrosoftAccount(
        activity: Activity,
        scopes: List<String>
    ) {
        Timber.tag(TAG).i("OLD addMicrosoftAccount: Attempting for scopes: $scopes")
        // ...
    }
    */

    // removeMicrosoftAccount (old internal helper for old removeAccount)
    // This logic needs to be integrated into the new signOut for MS.
    // Commenting out for now.
    /*
    private suspend fun removeMicrosoftAccount(account: Account) {
        Timber.tag(TAG).i("OLD removeMicrosoftAccount: Attempting for account: ${account.username}")
        // ...
    }
    */

    // --- Google AppAuth Specific Methods (OLD internal helpers for old addAccount/handleResult) ---
    // addGoogleAccountWithAppAuth (old internal helper)
    // This is somewhat replaced by getAuthenticationIntentRequest("GOOGLE")
    // Commenting out.
    /*
    private suspend fun addGoogleAccountWithAppAuth(activity: Activity, scopes: List<String>) {
        Timber.tag(TAG).i("OLD addGoogleAccountWithAppAuth: Initiating for scopes: $scopes")
        // ...
    }
    */

    // finalizeGoogleAccountSetupWithAppAuth (old internal helper)
    // Logic from here is being moved into handleGoogleAppAuthResponse / handleAuthenticationResult("GOOGLE")
    // Commenting out.
    /*
    internal suspend fun finalizeGoogleAccountSetupWithAppAuth(
        activity: Activity, 
        authResponse: AuthorizationResponse?,
        authException: AuthorizationException?
    ) {
        Timber.tag(TAG).d("OLD finalizeGoogleAccountSetupWithAppAuth: Received AppAuth result. Response: ${authResponse != null}, Exception: ${authException != null}")
        // ...
    }
    */

    // removeGoogleAccountWithAppAuth (old internal helper for old removeAccount)
    // Logic from here is integrated into the new signOut for Google.
    // Commenting out.
    /*
    private suspend fun removeGoogleAccountWithAppAuth(account: Account) {
        Timber.tag(TAG).i("OLD removeGoogleAccountWithAppAuth: Attempting for account: ${account.username ?: account.id}")
        // ...
    }
    */

    // --- Utility Methods ---

    // determineInitialAuthState (OLD method returning old AuthState) - Renamed to _OLD, will be removed
    private fun determineInitialAuthState_OLD(): AuthState {
        Timber.tag(TAG).d("determineInitialAuthState_OLD called")
        return if (microsoftAuthManager.accounts.isNotEmpty()) { // Relies on direct MSAL check
            AuthState.SignedInWithMicrosoft(
                microsoftAuthManager.accounts.first().username ?: "Unknown MS User"
            )
        } else {
            AuthState.SignedOut
        }
    }

    // determineAuthState (OLD method returning old AuthState) - Will be removed or logic adapted for updateOverallAuthState
    // Commenting out.
    /*
    private fun determineAuthState(isInitialized: Boolean, error: MsalException?): AuthState {
        Timber.tag(TAG).d("OLD determineAuthState: MS Initialized=$isInitialized, ErrorPresent=${error != null}")
        // ...
    }
    */

    // mapToGenericAccounts (OLD method - parts of it are useful but needs careful integration with new account fetching)
    // Particularly how MS accounts are fetched and mapped.
    // Commenting out for now.
    /*
    private fun mapToGenericAccounts(
        msAccounts: List<IAccount>,
        googleAccounts: List<Account>
    ): List<Account> {
        Timber.tag(TAG).d("OLD mapToGenericAccounts: Mapping ${msAccounts.size} MS accounts and ${googleAccounts.size} Google accounts.")
        // ...
        return genericAccounts.distinctBy { it.id + it.providerType }
    }
    */

    // tryEmitMessage (utility, likely still useful)
    private fun tryEmitMessage(message: String?) {
        val result = _accountActionMessage.tryEmit(message)
        Timber.tag(TAG).d("tryEmitMessage: Emitting message '$message'. Success: $result")
        if (!result) {
            Timber.tag(TAG)
                .w("tryEmitMessage: Failed to emit message. Buffer might be full or no subscribers.")
        }
    }

    // handleGoogleAppAuthActivityResult (OLD method - logic moved to handleAuthenticationResult / handleGoogleAppAuthResponse)
    // Commenting out.
    /*
    suspend fun handleGoogleAppAuthActivityResult(
        activity: Activity,
        authResponse: AuthorizationResponse?,
        authException: AuthorizationException?
    ) {
        Timber.tag(TAG).d("OLD handleGoogleAppAuthActivityResult called in DefaultAccountRepository")
        // ...
    }
    */

    // getAccounts - Implemented for new interface
    override fun getAccounts(): Flow<List<Account>> = _accounts.asStateFlow()

    // getActiveAccount - Implemented for new interface (placeholder, needs refinement)
    override fun getActiveAccount(providerType: String): Flow<Account?> {
        // This needs more thought. For now, returning a simple flow based on current active holders
        // This is a simplified placeholder
        return if (providerType.equals("GOOGLE", ignoreCase = true)) {
            activeGoogleAccountHolder.activeAccountId.flatMapLatest { accountId ->
                if (accountId == null) flowOf(null)
                else _accounts.map { list -> list.find { it.id == accountId && it.providerType == Account.PROVIDER_TYPE_GOOGLE } }
            }
        } else if (providerType.equals("MS", ignoreCase = true)) {
            activeMicrosoftAccountHolder.activeMicrosoftAccountId.flatMapLatest { accountId ->
                if (accountId == null) flowOf(null)
                else _accounts.map { list -> list.find { it.id == accountId && it.providerType == Account.PROVIDER_TYPE_MS } }
            }
        } else {
            flowOf(null)
        }
    }

    // getAuthenticationIntentRequest - Implemented for new interface
    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        loginHint: String? // Added loginHint for consistency with MSAL
    ): Flow<Intent?> {
        Timber.tag(TAG)
            .d("getAuthenticationIntentRequest called. Provider: $providerType, LoginHint: $loginHint")
        _isLoadingAccountAction.value = true
        providerTypeForLoadingAction = providerType

        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                // MSAL handles its own UI. We trigger it via MicrosoftAccountRepository.
                // MicrosoftAccountRepository will emit on its own action messages/auth state.
                externalScope.launch {
                    microsoftAccountRepository.initiateSignIn(activity, loginHint)
                }
                flowOf(null) // Indicate no intent to launch from here directly
            }
            Account.PROVIDER_TYPE_GOOGLE -> {
                try {
                    val redirectUri =
                        Uri.parse(BuildConfig.REDIRECT_URI_APP_AUTH) // Ensure this is defined
                    val authRequest = appAuthHelperService.buildAuthorizationRequest(
                        clientId = GoogleBuildConfig.GOOGLE_ANDROID_CLIENT_ID,
                        redirectUri = redirectUri,
                        scopes = getGoogleLoginScopes(), // Use the new method
                        loginHint = loginHint
                    )
                    val authIntent =
                        appAuthHelperService.createAuthorizationRequestIntent(authRequest)
                    // Emit the intent for the UI to launch
                    // _googleAuthRequestIntent.tryEmit(authIntent)
                    // Return it directly in the flow
                    flowOf(authIntent)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error building Google auth request")
                    _accountActionMessage.tryEmit("Error preparing Google sign-in: ${e.message}")
                    _isLoadingAccountAction.value = false
                    flowOf(null)
                }
            }
            else -> {
                Timber.tag(TAG)
                    .w("Unsupported providerType for getAuthenticationIntentRequest: $providerType")
                _accountActionMessage.tryEmit("Sign-in for $providerType not supported.")
                _isLoadingAccountAction.value = false
                flowOf(null)
            }
        }.also {
            // Set loading to false if flow emits null immediately (error or MS case)
            // For Google, loading will be set to false in handleAuthenticationResult
            if (providerType.uppercase() == Account.PROVIDER_TYPE_MS) {
                // For MS, loading is managed by observing MicrosoftAccountRepository's loading state.
                // Here we just set it to false as we don't emit an intent.
                //isLoadingAccountAction might need to be managed by combining MS repo's loading state.
                // For now, assuming MS repo handles its loading UI message.
            } else {
                // If flow is null for Google (e.g. exception), then set loading to false.
                // This assumes the flowOf(null) implies immediate failure before intent emission.
                // This part is tricky as the intent itself is emitted.
                // Better to manage loading in the collection site (ViewModel) or in handleAuthResult.
            }
            if (providerType.uppercase() != Account.PROVIDER_TYPE_GOOGLE) {
                // Set loading to false for non-Google (MS handled by its repo, others are errors)
                // viewModelScope.launch { _isLoadingAccountAction.value = false } // Cannot use viewModelScope here
                // This loading state will be set to false if an error occurs or for MS case.
                // For Google, it is reset in handleAuthenticationResult.
            }
        }
    }

    // handleAuthenticationResult - Implemented for new interface
    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?,
        activity: Activity
    ) {
        Timber.tag(TAG)
            .d("handleAuthenticationResult: Provider=$providerType, ResultCode=$resultCode, DataPresent=${data != null}")

        when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_GOOGLE -> {
                handleGoogleAppAuthResponse(resultCode, data, activity)
            }

            Account.PROVIDER_TYPE_MS -> {
                microsoftAccountRepository.handleAuthenticationResult(
                    providerType,
                    resultCode,
                    data,
                    activity
                )
                Timber.tag(TAG)
                    .i("handleAuthenticationResult: MSAL handles results internally. Call forwarded if MS repo needs it.")
            }

            else -> {
                Timber.tag(TAG)
                    .w("handleAuthenticationResult: Unknown provider type: $providerType")
                _accountActionMessage.tryEmit("Received authentication result for an unknown provider.")
            }
        }
    }

    // Extracted and adapted from old finalizeGoogleAccountSetupWithAppAuth / similar logic in the large diff
    private suspend fun handleGoogleAppAuthResponse(
        resultCode: Int,
        data: Intent?,
        activity: Activity
    ) {
        Timber.tag(TAG)
            .d("handleGoogleAppAuthResponse: ResultCode=$resultCode, DataPresent=${data != null}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val authResponse = AuthorizationResponse.fromIntent(data)
            val authException = AuthorizationException.fromIntent(data)

            if (authResponse != null) {
                Timber.tag(TAG).i(
                    "Google AppAuth: Authorization successful. Code: ${
                        authResponse.authorizationCode?.take(10)
                    }..."
                )
                try {
                    // The activity parameter is not directly used by appAuthHelperService.exchangeAuthorizationCode, but good to have if a future version needs context
                    val tokenResponse: TokenResponse =
                        appAuthHelperService.exchangeAuthorizationCode(authResponse)
                    Timber.tag(TAG)
                        .i("Google AppAuth: Token exchange successful. AccessToken: ${tokenResponse.accessToken?.isNotEmpty()}, IdToken: ${tokenResponse.idToken?.isNotEmpty()}")

                    val idToken = tokenResponse.idToken
                    if (idToken == null) {
                        Timber.tag(TAG).e("Google AppAuth: ID Token is null after token exchange.")
                        _accountActionMessage.tryEmit("Google Sign-In failed: Missing ID token.")
                        return
                    }
                    val parsedIdTokenInfo = appAuthHelperService.parseIdToken(idToken)
                    if (parsedIdTokenInfo == null || parsedIdTokenInfo.userId == null) {
                        Timber.tag(TAG)
                            .e("Google AppAuth: Failed to parse ID token or user ID is missing.")
                        _accountActionMessage.tryEmit("Google Sign-In failed: Could not verify user information.")
                        return
                    }

                    // Use GoogleTokenPersistenceService to save all tokens and user info
                    val saveSuccess = googleTokenPersistenceService.saveTokens(
                        accountId = parsedIdTokenInfo.userId,
                        email = parsedIdTokenInfo.email,
                        displayName = parsedIdTokenInfo.displayName,
                        photoUrl = parsedIdTokenInfo.picture,
                        tokenResponse = tokenResponse
                    )

                    if (saveSuccess) {
                        val newAccount = mapGoogleUserToGenericAccount(
                            googleUserId = parsedIdTokenInfo.userId,
                            email = parsedIdTokenInfo.email,
                            displayName = parsedIdTokenInfo.displayName,
                            needsReAuth = false // Freshly authenticated
                        )

                        // Update internal Google accounts list
                        _googleAccounts.value =
                            _googleAccounts.value.filterNot { it.id == newAccount.id } + newAccount
                        activeGoogleAccountHolder.setActiveAccountId(newAccount.id) // Set as active
                        updateCombinedAccountsAndOverallAuthState() // Trigger overall state update

                        _accountActionMessage.tryEmit("Google account ${newAccount.username} added successfully.")
                        Timber.tag(TAG)
                            .i("Google account ${newAccount.username} processed and added.")
                    } else {
                        Timber.tag(TAG)
                            .e("Google AppAuth: Failed to save tokens to GoogleTokenPersistenceService.")
                        _accountActionMessage.tryEmit("Google Sign-In failed: Failed to save user information.")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG)
                        .e(e, "Google AppAuth: Token exchange or user info processing failed.")
                    _accountActionMessage.tryEmit("Google Sign-In failed: ${e.localizedMessage}")
                }
            } else if (authException != null) {
                Timber.tag(TAG).e(authException, "Google AppAuth: Authorization failed.")
                // Check for user cancellation specifically
                if (authException.type == AuthorizationException.TYPE_GENERAL_ERROR && authException.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) {
                    Timber.tag(TAG).i("Google AppAuth: User cancelled authorization flow.")
                    _accountActionMessage.tryEmit("Google Sign-In cancelled.")
                } else {
                    _accountActionMessage.tryEmit("Google Sign-In failed: ${authException.errorDescription ?: authException.error}")
                }
            } else {
                Timber.tag(TAG)
                    .w("Google AppAuth: Authorization response and exception both null after RESULT_OK. This typically means user cancelled.")
                _accountActionMessage.tryEmit("Google Sign-In cancelled.")
            }
        } else {
            Timber.tag(TAG)
                .w("Google AppAuth: Authorization activity result was not OK or data was null. ResultCode: $resultCode")
            if (resultCode == Activity.RESULT_CANCELED) {
                _accountActionMessage.tryEmit("Google Sign-In cancelled.")
            } else if (data == null && resultCode == Activity.RESULT_OK) {
                // This case can happen if the redirect URI is not correctly handled or intent is malformed.
                Timber.tag(TAG)
                    .e("Google AppAuth: RESULT_OK but data is null. Check redirect URI handling and intent flags.")
                _accountActionMessage.tryEmit("Google Sign-In failed: Invalid response from authentication service.")
            } else {
                _accountActionMessage.tryEmit("Google Sign-In attempt was not successful.")
            }
        }
    }

    // signOut - Implemented for new interface
    override suspend fun signOut(account: Account) {
        Timber.tag(TAG)
            .i("signOut called for account: ${account.username} (Provider: ${account.providerType})")
        // _isLoadingAccountAction.value = true // isLoadingAccountAction removed
        // providerTypeForLoadingAction = account.providerType // providerTypeForLoadingAction removed

        try {
            when (account.providerType.uppercase()) {
                Account.PROVIDER_TYPE_GOOGLE -> {
                    val accountIdToSignOut = account.id
                    val authState =
                        googleTokenPersistenceService.getAuthState(accountIdToSignOut) // Fetch AuthState
                    if (authState?.refreshToken != null) {
                        Timber.d("Google SignOut: Attempting to revoke refresh token for account: ${account.username}")
                        val revocationSuccess =
                            appAuthHelperService.revokeToken(authState.refreshToken!!)
                        if (revocationSuccess) {
                            Timber.i("Google SignOut: Refresh token revocation successful (or token was already invalid) for ${account.username}.")
                        } else {
                            Timber.w("Google SignOut: Refresh token revocation failed or indicated an issue for ${account.username}. Proceeding with local sign out.")
                        }
                    } else {
                        Timber.d("Google SignOut: No refresh token found in AuthState for ${account.username}, skipping revocation.")
                    }
                    // Proceed with clearing local tokens regardless of revocation outcome
                    val clearedLocally = googleTokenPersistenceService.clearTokens(
                        accountIdToSignOut,
                        removeAccount = true
                    )
                    if (clearedLocally) {
                        Timber.i("Google SignOut: Local tokens cleared successfully for ${account.username}.")
                    } else {
                        Timber.e("Google SignOut: Failed to clear local tokens for ${account.username}.")
                        // Consider if an error message should be propagated here if local clear fails
                    }

                    _googleAccounts.value =
                        _googleAccounts.value.filterNot { it.id == accountIdToSignOut }

                    if (activeGoogleAccountHolder.getActiveAccountIdValue() == accountIdToSignOut) {
                        activeGoogleAccountHolder.clearActiveAccountId()
                        // Try to set another Google account as active if available
                        _googleAccounts.value.firstOrNull()?.let { nextAccount ->
                            activeGoogleAccountHolder.setActiveAccountId(nextAccount.id)
                            Timber.tag(TAG)
                                .i("Google SignOut: Set next available account ${nextAccount.username} as active.")
                        } ?: Timber.tag(TAG)
                            .i("Google SignOut: No other Google accounts to set as active.")
                    }
                    _accountActionMessage.tryEmit("Google account ${account.username} signed out.")
                    Timber.tag(TAG).i("Google account ${account.username} signed out locally.")
                }

                Account.PROVIDER_TYPE_MS -> {
                    // Delegate to MicrosoftAccountRepository
                    microsoftAccountRepository.signOut(account)
                    // MicrosoftAccountRepository will handle emitting its own action messages
                    // and updating its account list, which will trigger updateCombinedAccountsAndOverallAuthState via its own collection.
                    Timber.tag(TAG)
                        .i("Delegated signOut to MicrosoftAccountRepository for MS account: ${account.username}")
                }

                else -> {
                    Timber.tag(TAG).w("signOut: Unknown provider type: ${account.providerType}")
                    _accountActionMessage.tryEmit("Cannot sign out account from unknown provider.")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "signOut: Failed for account ${account.username}")
            // Try to use specific error mapper if available
            val errorMessage = errorMappers[account.providerType.uppercase()]?.mapError(e)?.message
                ?: "Failed to sign out ${account.username}: ${e.localizedMessage}"
            _accountActionMessage.tryEmit(errorMessage)
        } finally {
            // _isLoadingAccountAction.value = false // isLoadingAccountAction removed
            // providerTypeForLoadingAction = null // providerTypeForLoadingAction removed
            updateCombinedAccountsAndOverallAuthState() // Ensure state is updated after any sign-out action
        }
    }

    // observeActionMessages - Implemented for new interface
    override fun observeActionMessages(): Flow<String?> {
        Timber.tag(TAG).d("observeActionMessages called.")
        // Combine action messages from this repository (Google) and Microsoft repository
        val googleActionMessages = _accountActionMessage.asSharedFlow()
        // Per plan: "collect and merge action messages from microsoftAccountRepository.observeActionMessages() ... potentially prefixing them for clarity (e.g., \"[MS] Signed in\")"
        val microsoftActionMessages =
            microsoftAccountRepository.observeActionMessages().map { msg ->
                if (msg != null) "[MS] $msg" else null // Prefix MS messages
            }

        // Optional: Prefix Google messages too for consistency if desired
        // val googleActionMessagesPrefixed = googleActionMessages.map { msg ->
        //     if (msg != null) "[GOOG] $msg" else null
        // }
        // return merge(googleActionMessagesPrefixed, microsoftActionMessages)

        return merge(googleActionMessages, microsoftActionMessages)
        // Using filterNotNull() could change behavior if one source emits null while the other has a message.
        // Merge alone will pass through nulls if emitted by either source, which might be fine if UI handles nulls as "no message".
        // The plan implies merging, then MainViewModel can handle the stream.
    }

    // clearActionMessage - Implemented for new interface
    override fun clearActionMessage() {
        Timber.tag(TAG).d("clearActionMessage called for DefaultAccountRepository.")
        _accountActionMessage.tryEmit(null)
        // As per plan, MicrosoftAccountRepository will have its own clearActionMessage.
        // DefaultAccountRepository should only clear its own messages.
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        val accountToMark = _accounts.value.find {
            it.id == accountId && it.providerType.equals(providerType, ignoreCase = true)
        }

        if (accountToMark != null) {
            if (!accountToMark.needsReauthentication) { // Only update if not already marked
                if (providerType.equals(Account.PROVIDER_TYPE_GOOGLE, ignoreCase = true)) {
                    val googleAccountsList = _googleAccounts.value.toMutableList()
                    val googleIndex = googleAccountsList.indexOfFirst { it.id == accountId }
                    if (googleIndex != -1) {
                        googleAccountsList[googleIndex] =
                            googleAccountsList[googleIndex].copy(needsReauthentication = true)
                        _googleAccounts.value = googleAccountsList
                        Timber.tag(TAG)
                            .i("Google Account $accountId marked for re-authentication in internal list.")
                    } else {
                        Timber.tag(TAG)
                            .w("Google Account $accountId not found in internal list for marking re-auth.")
                        // Not returning here, as updateCombinedAccountsAndOverallAuthState might still be relevant if _accounts was somehow out of sync
                    }
                } else if (providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
                    // For MS, the source of truth is MicrosoftAccountRepository.
                    // Delegate the call to MicrosoftAccountRepository to mark the account.
                    Timber.tag(TAG)
                        .d("Delegating markAccountForReauthentication to MicrosoftAccountRepository for MS account $accountId.")
                    microsoftAccountRepository.markAccountForReauthentication(accountId)
                    // The updateCombinedAccountsAndOverallAuthState will pick up the change when MicrosoftAccountRepository's getAccounts() flow emits.
                    // No direct update to _accounts.value here for MS accounts.
                }

                Timber.tag(TAG)
                    .i("Account $accountId ($providerType) marked for re-authentication processing initiated.")
                updateCombinedAccountsAndOverallAuthState() // This will re-evaluate overall state
            } else {
                Timber.tag(TAG)
                    .d("Account $accountId ($providerType) was already marked for re-authentication.")
            }
        } else {
            Timber.tag(TAG)
                .w("Attempted to mark non-existent or non-matching account for re-auth: $accountId ($providerType) in combined list _accounts.value.")
            // Call updateCombinedAccountsAndOverallAuthState anyway, in case the account exists in one of the source lists but not in _accounts.value yet.
            // This could happen if _accounts.value is stale.
            updateCombinedAccountsAndOverallAuthState()
        }
    }

    // updateOverallAuthState (utility for new interface)
    private fun updateOverallAuthState() {
        externalScope.launch {
            // This needs to accurately reflect the state of *both* Google and potential MS accounts.
            // And consider if tokens are valid (not just presence). This is a simplification.
            val currentAccounts = _accounts.value // Get the latest list
            currentAccounts.filter { it.providerType == Account.PROVIDER_TYPE_GOOGLE }
            currentAccounts.filter { it.providerType == Account.PROVIDER_TYPE_MS } // Assuming MS accounts are also in _accounts

            // TODO: Determine if any account *needs* re-authentication. This requires checking token validity.
            // For now, this logic is simplified:
            val newState = when {
                currentAccounts.isEmpty() -> OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
                // Placeholder: For now, any account present means authenticated.
                // Real implementation should check token status for ALL_ACCOUNTS_NEED_REAUTHENTICATION etc.
                else -> OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
            }

            if (_overallApplicationAuthState.value != newState) {
                _overallApplicationAuthState.value = newState
                Timber.tag(TAG).d("OverallApplicationAuthState updated to: $newState")
            }
        }
    }

    // New function as per plan
    private fun updateCombinedAccountsAndOverallAuthState() {
        externalScope.launch {
            combine(
                _googleAccounts,
                microsoftAccountRepository.getAccounts() // Assumes MicrosoftAccountRepository exposes getAccounts(): Flow<List<Account>>
            ) { googleAccs, msAccs ->
                Timber.tag(TAG)
                    .d("Combining accounts: ${googleAccs.size} Google, ${msAccs.size} MS")
                googleAccs + msAccs // Combine the lists
            }.collect { combinedAccounts ->
                _accounts.value = combinedAccounts // Update the main accounts list

                val newState = when {
                    combinedAccounts.isEmpty() -> OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
                    combinedAccounts.all { it.needsReauthentication } -> OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
                    combinedAccounts.any { it.needsReauthentication } && combinedAccounts.any { !it.needsReauthentication } -> OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
                    // Must check if not empty before checking all non-needsReauthentication
                    combinedAccounts.isNotEmpty() && combinedAccounts.all { !it.needsReauthentication } -> OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
                    else -> OverallApplicationAuthState.UNKNOWN // Default or initial state if conditions not met
                }
                Timber.tag(TAG)
                    .d("Updating OverallApplicationAuthState: $newState based on ${combinedAccounts.size} total accounts.")
                if (_overallApplicationAuthState.value != newState) {
                    _overallApplicationAuthState.value = newState
                }
            }
        }
    }

    // --- Public API for Scopes ---
    fun getGoogleLoginScopes(): List<String> {
        return GMAIL_SCOPES_FOR_LOGIN
    }
}

