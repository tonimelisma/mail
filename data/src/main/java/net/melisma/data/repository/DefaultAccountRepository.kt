// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.melisma.backend_google.auth.ActiveGoogleAccountHolder
import net.melisma.backend_google.auth.AppAuthHelperService
import net.melisma.backend_google.auth.GoogleTokenPersistenceService
import net.melisma.backend_google.auth.ParsedIdTokenInfo
import net.melisma.core_data.di.MicrosoftRepo
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.errors.MappedErrorDetails
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.GenericAuthErrorType
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import timber.log.Timber
import java.io.IOException
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
    @MicrosoftRepo private val microsoftAccountRepository: AccountRepository,
    private val appAuthHelperService: AppAuthHelperService,
    private val googleTokenPersistenceService: GoogleTokenPersistenceService,
    private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val activeGoogleAccountHolder: ActiveGoogleAccountHolder
) : AccountRepository {

    private val TAG = "DefaultAccountRepo"

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override fun observeActionMessages(): Flow<String?> = _accountActionMessage.asSharedFlow()

    // This Flow is an internal detail for Google AppAuth. Not part of AccountRepository interface.
    // The plan uses a Channel for Google Auth result, not this SharedFlow for intent.
    // private val _googleAuthRequestIntent = MutableSharedFlow<Intent?>... (This is likely superseded by UiActionRequired)

    private val _overallApplicationAuthState =
        MutableStateFlow<OverallApplicationAuthState>(OverallApplicationAuthState.UNKNOWN)
    override val overallApplicationAuthState: StateFlow<OverallApplicationAuthState> =
        _overallApplicationAuthState.asStateFlow()

    // Keep track of Google accounts internally for now
    private val _googleAccounts = MutableStateFlow<List<Account>>(emptyList())

    // New channel for Google Auth results as per plan
    private val googleAuthResultChannel = Channel<GenericAuthResult>(Channel.CONFLATED)

    private val googleAuthRedirectUri: Uri =
        Uri.parse("net.melisma.mail:/oauth2redirect") // Matches manifest placeholder

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

            // Listen to Microsoft accounts from its repository
            externalScope.launch {
                microsoftAccountRepository.getAccounts().collect {
                    // When MS accounts change, we need to update the combined list and overall state
                    updateCombinedAccountsAndOverallAuthState()
                }
            }
            // Listen to internal Google accounts changes
            externalScope.launch {
                _googleAccounts.collect {
                    // When Google accounts change, we need to update the combined list and overall state
                    updateCombinedAccountsAndOverallAuthState()
                }
            }
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

    // getAccounts() now needs to combine MS and Google accounts
    override fun getAccounts(): Flow<List<Account>> {
        // This combines the latest from MS repo and local Google accounts
        return combine(
            microsoftAccountRepository.getAccounts(), // Flow<List<Account>> from MS repo
            _googleAccounts.asStateFlow()             // StateFlow<List<Account>> for Google
        ) { msAccounts, googleAccounts ->
            msAccounts + googleAccounts
        }
    }

    // getActiveAccount needs to check providerType and delegate or use local activeGoogleAccountHolder
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getActiveAccount(providerType: String): Flow<Account?> {
        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> microsoftAccountRepository.getActiveAccount(providerType)
            Account.PROVIDER_TYPE_GOOGLE -> {
                activeGoogleAccountHolder.activeAccountId.flatMapLatest { activeId ->
                    if (activeId == null) {
                        flowOf(null)
                    } else {
                        _googleAccounts.map { accounts -> accounts.find { it.id == activeId } }
                    }
                }
            }

            else -> flowOf(null)
        }
    }

    // signIn - Implemented for new interface
    override fun signIn(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> {
        Timber.tag(TAG).d("signIn called for provider: $providerType, loginHint: $loginHint")
        _accountActionMessage.tryEmit(null) // Clear previous messages

        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> microsoftAccountRepository.signIn(
                activity,
                loginHint,
                providerType
            )
            Account.PROVIDER_TYPE_GOOGLE -> {
                externalScope.launch { // Launch in externalScope for suspend calls
                    try {
                        // Client ID might come from BuildConfig of :backend-google or be passed in
                        // For now, assuming it's accessible, e.g. via a constant or BuildConfig
                        val clientId = GoogleBuildConfig.GOOGLE_ANDROID_CLIENT_ID
                        Timber.tag(TAG)
                            .d("Google Sign-In: ClientID: $clientId, RedirectURI: $googleAuthRedirectUri")

                        val authRequest = appAuthHelperService.buildAuthorizationRequest(
                            clientId = clientId,
                            redirectUri = googleAuthRedirectUri,
                            scopes = GMAIL_SCOPES_FOR_LOGIN, // Defined at top of file
                            loginHint = loginHint
                        )
                        val authIntent =
                            appAuthHelperService.createAuthorizationRequestIntent(authRequest)

                        // Emit UiActionRequired to the channel. The caller (ViewModel) will observe this.
                        googleAuthResultChannel.send(
                            GenericAuthResult.UiActionRequired(
                                authIntent
                            )
                        )
                        Timber.tag(TAG).d("Google Sign-In: UiActionRequired emitted.")

                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Google Sign-In: Error preparing auth request intent.")
                        val googleErrorMapper =
                            errorMappers[Account.PROVIDER_TYPE_GOOGLE.uppercase()]
                        val details = googleErrorMapper?.mapExceptionToErrorDetails(e)
                            ?: MappedErrorDetails(
                                "Failed to initiate Google sign-in: ${e.message}",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                e::class.java.simpleName
                            )
                        googleAuthResultChannel.send(
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                    }
                }
                // Return the flow from the channel that will receive the eventual auth result from handleAuthenticationResult
                googleAuthResultChannel.receiveAsFlow()
                    .onEach { Timber.tag(TAG).d("Google signIn Flow emitting: $it") }
            }
            else -> {
                Timber.tag(TAG).w("Unsupported providerType for signIn: $providerType")
                flowOf(
                    GenericAuthResult.Error(
                        "Unsupported provider: $providerType",
                        GenericAuthErrorType.INVALID_REQUEST
                    )
                )
            }
        }
    }

    // handleAuthenticationResult - Implemented for new interface
    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?
    ) {
        Timber.tag(TAG)
            .d("handleAuthenticationResult called for provider: $providerType, resultCode: $resultCode, data: $data")
        _accountActionMessage.tryEmit(null)

        when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                microsoftAccountRepository.handleAuthenticationResult(
                    providerType,
                    resultCode,
                    data
                )
            }

            Account.PROVIDER_TYPE_GOOGLE -> {
                val googleErrorMapper = errorMappers[Account.PROVIDER_TYPE_GOOGLE.uppercase()]

                if (data == null) {
                    Timber.tag(TAG).w("Google Auth: Intent data is null. ResultCode: $resultCode")
                    if (resultCode == Activity.RESULT_CANCELED) {
                        googleAuthResultChannel.trySend(GenericAuthResult.Cancelled)
                    } else {
                        val details = googleErrorMapper?.mapExceptionToErrorDetails(null)
                            ?: MappedErrorDetails(
                                "Google sign-in failed: No data received",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                "NoData"
                            )
                        googleAuthResultChannel.trySend(
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                    }
                    return
                }

                val authResponse = AuthorizationResponse.fromIntent(data)
                val authException = AuthorizationException.fromIntent(data)

                if (authException != null) {
                    Timber.tag(TAG)
                        .e(authException, "Google Auth: AuthorizationException received.")
                    val details = googleErrorMapper?.mapExceptionToErrorDetails(authException)
                        ?: MappedErrorDetails(
                            "Google sign-in error: ${authException.errorDescription}",
                            GenericAuthErrorType.AUTHENTICATION_FAILED,
                            authException.error
                        )
                    googleAuthResultChannel.trySend(
                        GenericAuthResult.Error(
                            details.message,
                            details.type,
                            details.providerSpecificErrorCode
                        )
                    )
                    return
                }

                if (authResponse == null) {
                    Timber.tag(TAG)
                        .e("Google Auth: Both authResponse and authException are null from intent.")
                    val details = googleErrorMapper?.mapExceptionToErrorDetails(null)
                        ?: MappedErrorDetails(
                            "Google sign-in failed: Invalid response from auth flow",
                            GenericAuthErrorType.UNKNOWN_ERROR,
                            "InvalidResponse"
                        )
                    googleAuthResultChannel.trySend(
                        GenericAuthResult.Error(
                            details.message,
                            details.type,
                            details.providerSpecificErrorCode
                        )
                    )
                    return
                }

                Timber.tag(TAG).d("Google Auth: AuthorizationResponse received. Exchanging code...")
                try {
                    // Client secret is typically not used for Google with AppAuth PKCE
                    val tokenResponse = appAuthHelperService.exchangeAuthorizationCode(
                        authResponse,
                        clientSecret = null
                    )
                    Timber.tag(TAG)
                        .d("Google Auth: Code exchange successful. ID Token: ${tokenResponse.idToken != null}")

                    if (tokenResponse.idToken == null) {
                        Timber.tag(TAG).e("Google Auth: ID token is null after code exchange.")
                        val details =
                            googleErrorMapper?.mapExceptionToErrorDetails(IOException("ID token missing post-exchange"))
                                ?: MappedErrorDetails(
                                    "Google sign-in failed: ID token missing",
                                    GenericAuthErrorType.AUTHENTICATION_FAILED,
                                    "MissingIdToken"
                                )
                        googleAuthResultChannel.trySend(
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                        return
                    }

                    val parsedIdTokenInfo: ParsedIdTokenInfo? =
                        appAuthHelperService.parseIdToken(tokenResponse.idToken!!)
                    val currentUserId = parsedIdTokenInfo?.userId
                    if (parsedIdTokenInfo == null || currentUserId == null) {
                        Timber.tag(TAG)
                            .e("Google Auth: Failed to parse ID token or user ID is missing.")
                        val details =
                            googleErrorMapper?.mapExceptionToErrorDetails(IOException("ID token parsing failed or missing user ID"))
                                ?: MappedErrorDetails(
                                    "Google sign-in failed: Could not parse user information",
                                    GenericAuthErrorType.AUTHENTICATION_FAILED,
                                    "TokenParseError"
                                )
                        googleAuthResultChannel.trySend(
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                        return
                    }
                    Timber.tag(TAG)
                        .d("Google Auth: ID Token parsed. UserID: $currentUserId, Email: ${parsedIdTokenInfo.email}")

                    val saveSuccess = googleTokenPersistenceService.saveTokens(
                        accountId = currentUserId,
                        email = parsedIdTokenInfo.email,
                        displayName = parsedIdTokenInfo.displayName,
                        photoUrl = parsedIdTokenInfo.picture, // photoUrl might not be used by Account model
                        tokenResponse = tokenResponse
                    )

                    if (!saveSuccess) {
                        Timber.tag(TAG).e("Google Auth: Failed to save tokens and account info.")
                        val details =
                            googleErrorMapper?.mapExceptionToErrorDetails(IOException("Token persistence failed"))
                                ?: MappedErrorDetails(
                                    "Google sign-in failed: Could not save account information",
                                    GenericAuthErrorType.UNKNOWN_ERROR,
                                    "PersistenceError"
                                )
                        googleAuthResultChannel.trySend(
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                        return
                    }

                    val newAccount = mapGoogleUserToGenericAccount(
                        googleUserId = currentUserId,
                        email = parsedIdTokenInfo.email,
                        displayName = parsedIdTokenInfo.displayName
                    )
                    Timber.tag(TAG).i("Google Auth: Sign-in successful for ${newAccount.username}.")

                    // Update internal list and active account holder
                    _googleAccounts.value =
                        _googleAccounts.value.filterNot { it.id == newAccount.id } + newAccount
                    activeGoogleAccountHolder.setActiveAccountId(newAccount.id)
                    updateCombinedAccountsAndOverallAuthState() // This will trigger UI updates if observing _accounts or overallApplicationAuthState

                    googleAuthResultChannel.trySend(GenericAuthResult.Success(newAccount))

                } catch (e: Exception) {
                    Timber.tag(TAG)
                        .e(e, "Google Auth: Exception during token exchange or processing.")
                    val details = googleErrorMapper?.mapExceptionToErrorDetails(e)
                        ?: MappedErrorDetails(
                            "Google sign-in failed: ${e.message}",
                            GenericAuthErrorType.AUTHENTICATION_FAILED,
                            e::class.java.simpleName
                        )
                    googleAuthResultChannel.trySend(
                        GenericAuthResult.Error(
                            details.message,
                            details.type,
                            details.providerSpecificErrorCode
                        )
                    )
                }
            }

            else -> {
                Timber.tag(TAG)
                    .w("Unsupported providerType for handleAuthenticationResult: $providerType")
                // Optionally send an error to a generic channel if one existed, or just log.
            }
        }
    }

    // signOut - Implemented for new interface
    override fun signOut(account: Account): Flow<GenericSignOutResult> {
        Timber.tag(TAG)
            .d("signOut called for account: ${account.username}, provider: ${account.providerType}")
        _accountActionMessage.tryEmit(null)

        return when (account.providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> microsoftAccountRepository.signOut(account)
            Account.PROVIDER_TYPE_GOOGLE -> flow {
                val googleErrorMapper = errorMappers[Account.PROVIDER_TYPE_GOOGLE.uppercase()]
                try {
                    Timber.tag(TAG)
                        .i("Attempting to sign out Google account: ${account.username} (ID: ${account.id})")
                    val authState = googleTokenPersistenceService.getAuthState(account.id)
                    var revoked = true // Assume success if no refresh token to revoke
                    if (authState?.refreshToken != null) {
                        Timber.tag(TAG)
                            .d("Google Sign-Out: Found refresh token, attempting revocation.")
                        revoked = appAuthHelperService.revokeToken(authState.refreshToken!!)
                        if (revoked) {
                            Timber.tag(TAG)
                                .i("Google Sign-Out: Token revocation successful for ${account.username}.")
                        } else {
                            Timber.tag(TAG)
                                .w("Google Sign-Out: Token revocation failed for ${account.username}, proceeding with local sign out.")
                            // Not treating revocation failure as a fatal error for sign-out, local data will still be cleared.
                        }
                    } else {
                        Timber.tag(TAG)
                            .d("Google Sign-Out: No refresh token found for account ${account.username}, skipping revocation.")
                    }

                    val cleared =
                        googleTokenPersistenceService.clearTokens(account.id, removeAccount = true)
                    if (cleared) {
                        Timber.tag(TAG)
                            .i("Google Sign-Out: Successfully cleared local tokens for ${account.username}.")
                        if (activeGoogleAccountHolder.getActiveAccountIdValue() == account.id) {
                            activeGoogleAccountHolder.setActiveAccountId(null)
                        }
                        _googleAccounts.value =
                            _googleAccounts.value.filterNot { it.id == account.id }
                        updateCombinedAccountsAndOverallAuthState()
                        emit(GenericSignOutResult.Success)
                    } else {
                        Timber.tag(TAG)
                            .e("Google Sign-Out: Failed to clear local tokens for ${account.username}.")
                        val details =
                            googleErrorMapper?.mapExceptionToErrorDetails(IOException("Failed to clear local Google account data"))
                                ?: MappedErrorDetails(
                                    "Sign out failed: Could not clear local data.",
                                    GenericAuthErrorType.UNKNOWN_ERROR,
                                    "ClearTokensFailed"
                                )
                        emit(
                            GenericSignOutResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG)
                        .e(e, "Google Sign-Out: Exception during sign out for ${account.username}.")
                    val details = googleErrorMapper?.mapExceptionToErrorDetails(e)
                        ?: MappedErrorDetails(
                            "Sign out failed: ${e.message}",
                            GenericAuthErrorType.UNKNOWN_ERROR,
                            e::class.java.simpleName
                        )
                    emit(
                        GenericSignOutResult.Error(
                            details.message,
                            details.type,
                            details.providerSpecificErrorCode
                        )
                    )
                }
            }

            else -> {
                Timber.tag(TAG).w("Unsupported providerType for signOut: ${account.providerType}")
                flowOf(
                    GenericSignOutResult.Error(
                        "Unsupported provider: ${account.providerType}",
                        GenericAuthErrorType.INVALID_REQUEST
                    )
                )
            }
        }
    }

    // clearActionMessage - Implemented for new interface
    override fun clearActionMessage() {
        Timber.tag(TAG).d("clearActionMessage called.")
        _accountActionMessage.tryEmit(null)
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        Timber.tag(TAG)
            .d("markAccountForReauthentication called for $accountId, provider $providerType")
        // For Google, this might mean clearing a specific flag if you stored one, or just relying on token expiry
        // For MSAL, the MicrosoftAccountRepository should handle this if it has its own logic
        if (providerType == Account.PROVIDER_TYPE_GOOGLE) {
            val currentGoogleAccounts = _googleAccounts.value.toMutableList()
            val index = currentGoogleAccounts.indexOfFirst { it.id == accountId }
            if (index != -1) {
                currentGoogleAccounts[index] =
                    currentGoogleAccounts[index].copy(needsReauthentication = true)
                _googleAccounts.value = currentGoogleAccounts.toList()
                Timber.i("Marked Google account $accountId for re-authentication.")
            }
        } else if (providerType == Account.PROVIDER_TYPE_MS) {
            microsoftAccountRepository.markAccountForReauthentication(accountId, providerType)
        }
        updateCombinedAccountsAndOverallAuthState()
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
        externalScope.launch { // Ensure this runs in a coroutine scope
            val msAccounts = try {
                microsoftAccountRepository.getAccounts().firstOrNull() ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Failed to get MS accounts for combined state update")
                emptyList()
            }
            val googleAccountsList = _googleAccounts.value

            val combined = (msAccounts + googleAccountsList).distinctBy { it.id }
            _accounts.value = combined
            Timber.tag(TAG)
                .d("Updated combined accounts. MS: ${msAccounts.size}, Google: ${googleAccountsList.size}, Total: ${combined.size}")

            // Determine OverallApplicationAuthState
            if (combined.isEmpty()) {
                _overallApplicationAuthState.value =
                    OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
            } else {
                val needsReAuthCount = combined.count { it.needsReauthentication }
                _overallApplicationAuthState.value = when {
                    needsReAuthCount == combined.size -> OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
                    needsReAuthCount > 0 -> OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
                    else -> OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
                }
            }
            Timber.tag(TAG)
                .d("Overall auth state updated to: ${_overallApplicationAuthState.value}")
        }
    }

    // --- Public API for Scopes ---
    fun getGoogleLoginScopes(): List<String> {
        return GMAIL_SCOPES_FOR_LOGIN
    }
}


