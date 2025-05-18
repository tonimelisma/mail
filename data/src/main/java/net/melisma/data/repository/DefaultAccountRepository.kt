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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.melisma.backend_google.auth.ActiveGoogleAccountHolder
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleSignInResult
import net.melisma.backend_google.auth.GoogleSignOutResult
import net.melisma.backend_google.model.ManagedGoogleAccount
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
import javax.inject.Inject
import javax.inject.Singleton

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
    private val googleAuthManager: GoogleAuthManager,
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
            Timber.tag(TAG)
                .d("Init: Fetching initial persisted Google accounts via GoogleAuthManager.")
            // Step 3.1.5: fetchPersistedGoogleAccounts equivalent
            googleAuthManager.getAccounts() // This returns Flow<List<ManagedGoogleAccount>>
                .map { managedAccounts ->
                    managedAccounts.map { mapManagedGoogleAccountToGenericAccount(it) }
                }
                .onEach { accounts ->
                    _googleAccounts.value = accounts
                    Timber.tag(TAG)
                        .d("Init: Updated _googleAccounts with ${accounts.size} accounts from GoogleAuthManager.")

                    // Set active Google account if none is active and accounts exist
                    if (activeGoogleAccountHolder.activeAccountId.value == null && accounts.isNotEmpty()) {
                        val firstGoogleAccount = accounts.first()
                        Timber.tag(TAG).i(
                            "Init: Setting active Google account from persisted: ${firstGoogleAccount.username} (ID: ${firstGoogleAccount.id})"
                        )
                        activeGoogleAccountHolder.setActiveAccount(firstGoogleAccount.id)
                    }
                    updateCombinedAccountsAndOverallAuthState() // Update combined list and overall state
                }
                .catch { e ->
                    Timber.tag(TAG).e(
                        e,
                        "Init: Error fetching initial Google accounts from GoogleAuthManager."
                    )
                    _googleAccounts.value = emptyList() // Set to empty on error
                    updateCombinedAccountsAndOverallAuthState()
                }
                .launchIn(externalScope)

            // Listen to Microsoft accounts from its repository
            externalScope.launch {
                microsoftAccountRepository.getAccounts().collect {
                    updateCombinedAccountsAndOverallAuthState()
                }
            }
            // Listen to internal Google accounts changes (now driven by GoogleAuthManager's initial fetch and subsequent operations)
            externalScope.launch {
                _googleAccounts.collect {
                    // This will be triggered when _googleAccounts is updated by GoogleAuthManager interactions
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

    // Mapper for ManagedGoogleAccount to Account
    private fun mapManagedGoogleAccountToGenericAccount(managedAccount: ManagedGoogleAccount): Account {
        Timber.tag(TAG)
            .d("Mapping ManagedGoogleAccount to Account: ID ${managedAccount.accountId}, Email ${managedAccount.email}")
        return Account(
            id = managedAccount.accountId,
            username = managedAccount.displayName ?: managedAccount.email
            ?: "Google User (${managedAccount.accountId.take(6)}...)",
            providerType = Account.PROVIDER_TYPE_GOOGLE,
            // Assuming ManagedGoogleAccount might have a field like 'needsReauth', or handled separately
            // needsReauthentication = managedAccount.needsReauth // Example
        )
    }

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

    // getAccounts - combines MS (from its repo) and Google accounts (from local _googleAccounts, fed by GoogleAuthManager)
    override fun getAccounts(): Flow<List<Account>> {
        return combine(
            microsoftAccountRepository.getAccounts(), // Flow<List<Account>> from MS repo
            _googleAccounts // This is StateFlow<List<Account>>, updated by GoogleAuthManager interactions
        ) { msAccounts, googleAccounts ->
            Timber.tag(TAG)
                .d("Combining accounts: MS(${msAccounts.size}), Google(${googleAccounts.size})")
            msAccounts + googleAccounts
        }.onEach { combinedAccounts ->
            // This updates the main _accounts StateFlow that external collectors observe for combined list.
            _accounts.value = combinedAccounts
            // updateOverallAuthState() is now called within the collectors of _googleAccounts and MS accounts
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
                        Timber.tag(TAG)
                            .d("Google Sign-In: Calling googleAuthManager.signInInteractive.")
                        // Step 3.1.2 from MTPS.md Plan:
                        // Call googleAuthManager.signInInteractive(activity, loginHint) (suspend function returning Intent).
                        val authIntent = googleAuthManager.signInInteractive(activity, loginHint)

                        // Send GenericAuthResult.UiActionRequired(intent) to the existing googleAuthResultChannel.
                        googleAuthResultChannel.send(
                            GenericAuthResult.UiActionRequired(authIntent)
                        )
                        Timber.tag(TAG).d("Google Sign-In: UiActionRequired emitted to channel.")

                    } catch (e: Exception) {
                        Timber.tag(TAG).e(
                            e,
                            "Google Sign-In: Error during signInInteractive or sending to channel."
                        )
                        val googleErrorMapper =
                            getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                        val details = googleErrorMapper?.mapExceptionToErrorDetails(e)
                            ?: MappedErrorDetails(
                                "Failed to initiate Google sign-in: ${e.message}",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                e::class.java.simpleName
                            )
                        googleAuthResultChannel.trySend( // trySend as a fallback if send suspends indefinitely on error
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                    }
                }
                // Continue to return googleAuthResultChannel.receiveAsFlow().
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
                // Step 3.1.3 from MTPS.md Plan:
                // Call googleAuthManager.handleAuthorizationResponse(authResponse, authException) (which returns Flow<GoogleSignInResult>).
                // Collect the GoogleSignInResult.
                // Map the GoogleSignInResult (Success, Error, Cancelled) to the appropriate GenericAuthResult.
                // trySend the mapped GenericAuthResult to googleAuthResultChannel.
                // On GoogleSignInResult.Success, update the local _googleAccounts list (after mapping ManagedGoogleAccount to Account).

                val authResponse = data?.let { AuthorizationResponse.fromIntent(it) }
                val authException = data?.let { AuthorizationException.fromIntent(it) }

                Timber.tag(TAG)
                    .d("Google handleAuthenticationResult: authResponse present: ${authResponse != null}, authException present: ${authException != null}")

                externalScope.launch {
                    googleAuthManager.handleAuthorizationResponse(authResponse, authException)
                        .map { googleResult ->
                            Timber.tag(TAG)
                                .d("Google handleAuthenticationResult: Received from manager: $googleResult")
                            when (googleResult) {
                                is GoogleSignInResult.Success -> {
                                    val newAccount =
                                        mapManagedGoogleAccountToGenericAccount(googleResult.managedAccount)
                                    // GoogleAuthManager already updates ActiveGoogleAccountHolder
                                    // Update local _googleAccounts list
                                    _googleAccounts.update { currentAccounts ->
                                        val existingAccount =
                                            currentAccounts.find { it.id == newAccount.id }
                                        if (existingAccount != null) {
                                            currentAccounts.map { if (it.id == newAccount.id) newAccount else it }
                                        } else {
                                            currentAccounts + newAccount
                                        }
                                    }
                                    // updateCombinedAccountsAndOverallAuthState() is triggered by _googleAccounts.update
                                    Timber.tag(TAG)
                                        .i("Google Sign-In successful (via manager) for ${newAccount.username}.")
                                    GenericAuthResult.Success(newAccount)
                                }

                                is GoogleSignInResult.Error -> {
                                    Timber.tag(TAG).e(
                                        googleResult.exception,
                                        "Google Sign-In error (via manager): ${googleResult.errorMessage}"
                                    )
                                    val persistenceErrorType =
                                        googleResult.persistenceFailure?.errorType
                                    val genericErrorType =
                                        persistenceErrorType?.toGenericAuthErrorType()
                                            ?: GenericAuthErrorType.AUTHENTICATION_FAILED

                                    val errorMapper =
                                        getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                                    val details = errorMapper?.mapExceptionToErrorDetails(
                                        googleResult.exception
                                            ?: Throwable(googleResult.errorMessage)
                                    )
                                        ?: MappedErrorDetails(
                                            googleResult.errorMessage,
                                            genericErrorType,
                                            googleResult.exception?.javaClass?.simpleName
                                                ?: persistenceErrorType?.name
                                        )
                                    GenericAuthResult.Error(
                                        details.message,
                                        details.type,
                                        details.providerSpecificErrorCode
                                    )
                                }

                                is GoogleSignInResult.Cancelled -> {
                                    Timber.tag(TAG).i("Google Sign-In cancelled (via manager).")
                                    GenericAuthResult.Cancelled
                                }
                            }
                        }
                        .catch { e ->
                            // Catch unexpected errors from the flow itself
                            Timber.tag(TAG)
                                .e(e, "Google handleAuthenticationResult: Flow collection error")
                            val errorMapper =
                                getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                            val details = errorMapper?.mapExceptionToErrorDetails(e)
                                ?: MappedErrorDetails(
                                    "Google sign-in failed unexpectedly: ${e.message}",
                                    GenericAuthErrorType.UNKNOWN_ERROR,
                                    e.javaClass.simpleName
                                )
                            googleAuthResultChannel.trySend(
                                GenericAuthResult.Error(
                                    details.message,
                                    details.type,
                                    details.providerSpecificErrorCode
                                )
                            )
                        }
                        .collect { mappedResult ->
                            googleAuthResultChannel.trySend(mappedResult)
                            Timber.tag(TAG)
                                .d("Google handleAuthenticationResult: Sent to channel: $mappedResult")
                        }
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
                // Step 3.1.4 from MTPS.md Plan:
                // Retrieve the ManagedGoogleAccount for the given Account (e.g., by calling googleAuthManager.getAccount(account.id).firstOrNull()).
                // Call googleAuthManager.signOut(retrievedManagedGoogleAccount).
                // Collect GoogleSignOutResult, map to GenericSignOutResult, and emit.
                // Update local _googleAccounts list on success.

                Timber.tag(TAG)
                    .i("Google Sign-Out: Attempting for account: ${account.username} (ID: ${account.id})")
                val managedAccount = googleAuthManager.getAccount(account.id).firstOrNull()

                if (managedAccount == null) {
                    Timber.tag(TAG)
                        .w("Google Sign-Out: ManagedGoogleAccount not found for ID: ${account.id}. Cannot proceed with manager-based sign out. Clearing locally if possible.")
                    // Fallback: If account isn't in manager (perhaps already removed or inconsistent state),
                    // try to clear from local _googleAccounts if it exists there.
                    val isLocallyKnown = _googleAccounts.value.any { it.id == account.id }
                    if (isLocallyKnown) {
                        _googleAccounts.update { it.filterNot { acc -> acc.id == account.id } }
                        if (activeGoogleAccountHolder.activeAccountId.value == account.id) {
                            activeGoogleAccountHolder.clearActiveAccount()
                        }
                        // updateCombinedAccountsAndOverallAuthState() is triggered by _googleAccounts.update
                        emit(GenericSignOutResult.Success) // Consider if this should be an error or a silent success.
                    } else {
                        emit(
                            GenericSignOutResult.Error(
                                "Account not found for sign-out.",
                                GenericAuthErrorType.ACCOUNT_NOT_FOUND_OR_INVALID
                            )
                        )
                    }
                    return@flow
                }

                googleAuthManager.signOut(managedAccount)
                    .map { googleSignOutResult ->
                        Timber.tag(TAG)
                            .d("Google Sign-Out: Received from manager: $googleSignOutResult")
                        when (googleSignOutResult) {
                            is GoogleSignOutResult.Success -> {
                                _googleAccounts.update { currentAccounts ->
                                    currentAccounts.filterNot { it.id == account.id }
                                }
                                // GoogleAuthManager handles ActiveGoogleAccountHolder update internally.
                                // updateCombinedAccountsAndOverallAuthState() is triggered by _googleAccounts.update
                                Timber.tag(TAG)
                                    .i("Google Sign-Out successful (via manager) for ${account.username}.")
                                GenericSignOutResult.Success
                            }

                            is GoogleSignOutResult.Error -> {
                                Timber.tag(TAG).e(
                                    googleSignOutResult.exception,
                                    "Google Sign-Out error (via manager): ${googleSignOutResult.errorMessage}"
                                )
                                val persistenceErrorType =
                                    googleSignOutResult.persistenceFailure?.errorType
                                val genericErrorType =
                                    persistenceErrorType?.toGenericAuthErrorType()
                                        ?: GenericAuthErrorType.AUTHENTICATION_FAILED

                                val errorMapper =
                                    getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                                val details = errorMapper?.mapExceptionToErrorDetails(
                                    googleSignOutResult.exception
                                        ?: Throwable(googleSignOutResult.errorMessage)
                                )
                                    ?: MappedErrorDetails(
                                        googleSignOutResult.errorMessage,
                                        genericErrorType,
                                        googleSignOutResult.exception?.javaClass?.simpleName
                                            ?: persistenceErrorType?.name
                                    )
                                GenericSignOutResult.Error(
                                    details.message,
                                    details.type,
                                    details.providerSpecificErrorCode
                                )
                            }
                        }
                    }
                    .catch { e ->
                        // Catch unexpected errors from the flow itself
                        Timber.tag(TAG)
                            .e(e, "Google Sign-Out: Flow collection error for ${account.username}")
                        val errorMapper = getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                        val details = errorMapper?.mapExceptionToErrorDetails(e)
                            ?: MappedErrorDetails(
                                "Google sign-out failed unexpectedly: ${e.message}",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                e.javaClass.simpleName
                            )
                        emit(
                            GenericSignOutResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                    }
                    .collect { emit(it) }
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
            .d("Marking account for re-authentication: ID $accountId, Provider: $providerType")
        if (providerType == Account.PROVIDER_TYPE_GOOGLE) {
            val accountToUpdate = _googleAccounts.value.find { it.id == accountId }
            if (accountToUpdate != null) {
                Timber.tag(TAG)
                    .i("Requesting re-authentication via GoogleAuthManager for account: ${accountToUpdate.username} (ID: $accountId)")
                // Call the new method on GoogleAuthManager
                val result = googleAuthManager.requestReauthentication(accountId)
                if (result is PersistenceResult.Success) {
                    Timber.tag(TAG)
                        .d("GoogleAuthManager.requestReauthentication successful for $accountId. Updating local state.")
                } else if (result is PersistenceResult.Failure) {
                    Timber.tag(TAG).w(
                        "GoogleAuthManager.requestReauthentication failed for $accountId: ${result.errorType}. Still marking for re-auth locally.",
                        result.cause
                    )
                }

                // Update the local list to reflect the needsReauthentication state
                _googleAccounts.update { currentAccounts ->
                    currentAccounts.map {
                        if (it.id == accountId) {
                            Timber.tag(TAG)
                                .d("Updating account $accountId in _googleAccounts to needsReauthentication = true")
                            it.copy(needsReauthentication = true)
                        } else {
                            it
                        }
                    }
                }
                // updateCombinedAccountsAndOverallAuthState() will be triggered by _googleAccounts.update
            } else {
                Timber.tag(TAG)
                    .w("Could not find Google account with ID $accountId to mark for re-authentication.")
            }
        } else if (providerType == Account.PROVIDER_TYPE_MICROSOFT) {
            // Delegate to MicrosoftAccountRepository, assuming it has a similar method
            // This part of the method needs to be implemented or adjusted based on MicrosoftAccountRepository's capabilities.
            // For now, just logging. If MicrosoftAccountRepository updates its list internally and DefaultAccountRepository observes it,
            // the change might propagate automatically.
            Timber.tag(TAG)
                .d("Marking Microsoft account $accountId for re-authentication. Delegating to MicrosoftAccountRepository.")
            microsoftAccountRepository.markAccountForReauthentication(accountId, providerType)
            // updateCombinedAccountsAndOverallAuthState() should be triggered if microsoftAccountRepository.getAccounts() emits.
        } else {
            Timber.tag(TAG).w("Unknown provider type for marking re-authentication: $providerType")
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

    /**
     * Helper extension function to map GooglePersistenceErrorType to GenericAuthErrorType.
     * This can be expanded as more specific mappings are needed.
     */
    fun GooglePersistenceErrorType.toGenericAuthErrorType(): GenericAuthErrorType {
        return when (this) {
            GooglePersistenceErrorType.ACCOUNT_NOT_FOUND,
            GooglePersistenceErrorType.MISSING_AUTH_STATE_JSON,
            GooglePersistenceErrorType.AUTH_STATE_DESERIALIZATION_FAILED -> GenericAuthErrorType.ACCOUNT_NOT_FOUND_OR_INVALID

            GooglePersistenceErrorType.ENCRYPTION_FAILED,
            GooglePersistenceErrorType.DECRYPTION_FAILED -> GenericAuthErrorType.SECURITY_ERROR

            GooglePersistenceErrorType.STORAGE_FAILED,
            GooglePersistenceErrorType.TOKEN_UPDATE_FAILED -> GenericAuthErrorType.STORAGE_ERROR

            GooglePersistenceErrorType.NETWORK_ERROR -> GenericAuthErrorType.NETWORK_ERROR // Assuming GooglePersistenceErrorType has NETWORK_ERROR
            GooglePersistenceErrorType.UNKNOWN_ERROR -> GenericAuthErrorType.UNKNOWN_ERROR
            // Add more specific mappings if GooglePersistenceErrorType grows
            else -> GenericAuthErrorType.UNKNOWN_ERROR
        }
    }
}


