// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.backend_google.auth.ActiveGoogleAccountHolder
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleSignInResult
import net.melisma.backend_google.auth.GoogleSignOutResult
import net.melisma.backend_google.common.GooglePersistenceErrorType
import net.melisma.backend_google.model.ManagedGoogleAccount
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.di.MicrosoftRepo
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.errors.MappedErrorDetails
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.GenericAuthErrorType
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.melisma.core_db.dao.AccountDao
import net.melisma.data.mapper.toDomainAccount
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
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val activeGoogleAccountHolder: ActiveGoogleAccountHolder,
    private val accountDao: AccountDao
) : AccountRepository {

    private val TAG = "DefaultAccountRepo"

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

    // New channel for Google Auth results as per plan
    private val googleAuthResultChannel = Channel<GenericAuthResult>(Channel.CONFLATED)

    private val googleAuthRedirectUri: Uri =
        Uri.parse("net.melisma.mail:/oauth2redirect") // Matches manifest placeholder

    init {
        Timber.tag(TAG).d("Initializing DefaultAccountRepository. DAO is SSoT for accounts.")

        getAccounts() // This now reads from DAO
            .onEach { accounts ->
                Timber.tag(TAG).d("DAO emitted ${accounts.size} accounts. Updating auth state.")
                val newAuthState = if (accounts.isEmpty()) {
                    OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
                } else {
                    if (accounts.all { it.needsReauthentication }) {
                        OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
                    } else if (accounts.any { it.needsReauthentication }) {
                        OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
                    } else {
                        OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
                    }
                }
                if (_overallApplicationAuthState.value != newAuthState) {
                    Timber.tag(TAG)
                        .d("OverallApplicationAuthState changing from ${_overallApplicationAuthState.value} to $newAuthState")
                    _overallApplicationAuthState.value = newAuthState
                }

                // Auto-set active Google account if needed (from DAO-sourced accounts)
                if (activeGoogleAccountHolder.activeAccountId.value == null) {
                    accounts.firstOrNull { it.providerType == Account.PROVIDER_TYPE_GOOGLE }
                        ?.let { googleAccount ->
                            Timber.tag(TAG)
                                .i("Init: Setting active Google account from DAO: ${googleAccount.username} (ID: ${googleAccount.id})")
                            activeGoogleAccountHolder.setActiveAccountId(googleAccount.id)
                    }
                }
            }
            .catch { e ->
                Timber.tag(TAG).e(e, "Error observing accounts from DAO for auth state.")
                _overallApplicationAuthState.value =
                    OverallApplicationAuthState.UNKNOWN // Fallback on error
            }
            .launchIn(externalScope)
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
        Timber.tag(TAG).d("getAccounts() called, fetching from AccountDao.")
        return accountDao.getAllAccounts().map { entities ->
            entities.map { it.toDomainAccount() }
        }
    }

    override fun getAccountById(accountId: String): Flow<Account?> {
        return accountDao.getAccountById(accountId).map { entity ->
            entity?.toDomainAccount()
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
                        accountDao.getAccountById(activeId).map { entity ->
                            entity?.toDomainAccount()
                        }
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
            .d("handleAuthenticationResult for provider: $providerType, resultCode: $resultCode")
        _accountActionMessage.tryEmit(null)

        when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                // Delegate, assuming microsoftAccountRepository updates DAO or its success is caught in signIn
                microsoftAccountRepository.handleAuthenticationResult(
                    providerType,
                    resultCode,
                    data
                )
            }
            Account.PROVIDER_TYPE_GOOGLE -> {
                val authResponse = data?.let { AuthorizationResponse.fromIntent(it) }
                val authException = data?.let { AuthorizationException.fromIntent(it) }

                googleAuthManager.handleAuthorizationResponse(authResponse, authException)
                    .onEach { result ->
                        Timber.tag(TAG).d("Google handleAuthorizationResponse result: $result")
                        val genericResult: GenericAuthResult = when (result) {
                            is GoogleSignInResult.Success -> {
                                val account =
                                    mapManagedGoogleAccountToGenericAccount(result.managedAccount)
                                externalScope.launch(ioDispatcher) { // Persist to DAO
                                    Timber.tag(TAG)
                                        .d("Google Auth Success: Saving account ${account.username} to DAO.")
                                    accountDao.insertOrUpdateAccount(account.toEntity()) // Correct DAO usage
                                    activeGoogleAccountHolder.setActiveAccountId(account.id) // Set active
                                }
                                GenericAuthResult.Success(account)
                            }

                            is GoogleSignInResult.Error -> {
                                val details =
                                    getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                                        ?.mapExceptionToErrorDetails(result.exception)
                                        ?: MappedErrorDetails(
                                            result.message,
                                            GenericAuthErrorType.AUTHENTICATION_FAILED
                                        )
                                GenericAuthResult.Error(
                                    details.message,
                                    details.type,
                                    details.providerSpecificErrorCode
                                )
                            }

                            is GoogleSignInResult.Cancelled -> GenericAuthResult.Cancelled
                        }
                        googleAuthResultChannel.trySend(genericResult)
                        if (genericResult is GenericAuthResult.Success) {
                            _accountActionMessage.tryEmit("Signed in as ${genericResult.account.username}")
                        } else if (genericResult is GenericAuthResult.Error) {
                            _accountActionMessage.tryEmit("Google sign-in error: ${genericResult.message}")
                        }
                    }
                    .catch { e ->
                        Timber.tag(TAG).e(e, "Error in Google handleAuthorizationResponse flow.")
                        val details = getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                            ?.mapExceptionToErrorDetails(e)
                            ?: MappedErrorDetails(
                                "Google sign-in processing error: ${e.message}",
                                GenericAuthErrorType.UNKNOWN_ERROR
                            )
                        googleAuthResultChannel.trySend(
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                    }
                    .launchIn(externalScope) // Collect the flow
            }
        }
    }

    // signOut - Implemented for new interface
    override fun signOut(account: Account): Flow<GenericSignOutResult> {
        Timber.tag(TAG)
            .i("signOut called for account: ${account.username} (Provider: ${account.providerType})")
        return flow {
            emit(GenericSignOutResult.Loading) // Indicate loading state
            val providerTypeUpper = account.providerType.uppercase()
            try {
                val result: GenericSignOutResult = when (providerTypeUpper) {
                    Account.PROVIDER_TYPE_MS -> {
                        // Delegate to MS repo, then ensure DAO is updated if this repo is SSoT for MS
                        microsoftAccountRepository.signOut(account).firstOrNull()?.also {
                            if (it is GenericSignOutResult.Success) {
                                externalScope.launch(ioDispatcher) {
                                    Timber.tag(TAG)
                                        .d("MS Sign-Out Success for ${account.username}: Deleting from DAO.")
                                    accountDao.deleteAccount(account.id) // Correct DAO usage
                                }
                            }
                        } ?: GenericSignOutResult.Error("MS sign out failed or flow empty")
                    }

                    Account.PROVIDER_TYPE_GOOGLE -> {
                        when (googleAuthManager.signOut(account.id)) {
                            GoogleSignOutResult.Success -> {
                                externalScope.launch(ioDispatcher) {
                                    Timber.tag(TAG)
                                        .d("Google Sign-Out Success for ${account.username}: Deleting from DAO.")
                                    accountDao.deleteAccount(account.id) // Correct DAO usage
                                }
                                if (activeGoogleAccountHolder.activeAccountId.value == account.id) {
                                    activeGoogleAccountHolder.clearActiveAccountId()
                                }
                                GenericSignOutResult.Success
                            }
                            is GoogleSignOutResult.Error -> {
                                // Even if Google manager fails to sign out, if we have it in DAO, try to remove local representation.
                                // However, this might leave the Google account signed in on device in a broader sense.
                                // For robust handling, if manager fails, we might not want to delete from DAO to reflect sync state.
                                // For now, let's assume manager failure means we don't touch DAO for Google.
                                Timber.tag(TAG)
                                    .w("GoogleAuthManager sign out failed for ${account.username}. DAO state not changed.")
                                GenericSignOutResult.Error("Google sign out failed with auth manager.")
                            }
                        }
                    }

                    else -> GenericSignOutResult.Error("Unsupported provider for sign out: ${account.providerType}")
                }
                emit(result)
                if (result is GenericSignOutResult.Success) {
                    tryEmitMessage("Signed out ${account.username}")
                } else if (result is GenericSignOutResult.Error) {
                    tryEmitMessage("Sign out error for ${account.username}: ${result.message}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Exception during signOut for ${account.username}")
                val errorDetails =
                    getErrorMapperForProvider(providerTypeUpper)?.mapExceptionToErrorDetails(e)
                        ?: MappedErrorDetails(
                            "Sign out failed: ${e.message}",
                            GenericAuthErrorType.UNKNOWN_ERROR
                        )
                emit(GenericSignOutResult.Error(errorDetails.message))
                tryEmitMessage("Sign out error for ${account.username}: ${errorDetails.message}")
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
                } else if (result is PersistenceResult.Failure<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val failure = result as PersistenceResult.Failure<GooglePersistenceErrorType>
                    Timber.tag(TAG).w(
                        "GoogleAuthManager.requestReauthentication failed for $accountId: ${failure.errorType}. Still marking for re-auth locally.",
                        failure.cause
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
        } else if (providerType == Account.PROVIDER_TYPE_MS) {
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
     * Extension function to map [GooglePersistenceErrorType] to a more generic [GenericAuthErrorType].
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

            GooglePersistenceErrorType.TOKEN_REFRESH_INVALID_GRANT -> GenericAuthErrorType.AUTHENTICATION_FAILED
            GooglePersistenceErrorType.UNKNOWN_ERROR,
            GooglePersistenceErrorType.AUTH_STATE_SERIALIZATION_FAILED -> GenericAuthErrorType.UNKNOWN_ERROR
        }
    }

    // Stub implementation for new method
    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Log.d(TAG, "syncAccount called for accountId: $accountId")
        // val account = _accounts.value.find { it.id == accountId }
        // val apiService = account?.providerType?.uppercase()?.let { mailApiServices[it] } // This repo doesn't have direct apiService access
        // This would likely delegate to the specific provider's sync mechanism if available,
        // or trigger a series of refreshes (folders, etc.)
        Timber.tag(TAG).w("syncAccount for $accountId not fully implemented. Placeholder.")
        return Result.failure(NotImplementedError("syncAccount not implemented"))
    }

    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        scopes: List<String>?
    ): Flow<GenericAuthResult> {
        Timber.tag(TAG).d("getAuthenticationIntentRequest called for provider: $providerType")
        _accountActionMessage.tryEmit(null) // Clear previous messages

        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                // Assuming microsoftAccountRepository conforms to the updated AccountRepository interface
                (microsoftAccountRepository as? AccountRepository)?.getAuthenticationIntentRequest(
                    providerType,
                    activity,
                    scopes
                ) ?: flowOf(
                    GenericAuthResult.Error(
                        "Microsoft provider does not support getAuthenticationIntentRequest or is not of expected type.",
                        GenericAuthErrorType.INVALID_REQUEST
                    )
                )
            }

            Account.PROVIDER_TYPE_GOOGLE -> {
                flow {
                    try {
                        Timber.tag(TAG)
                            .d("Google getAuthenticationIntentRequest: Calling googleAuthManager.signInInteractive.")
                        val authIntent = googleAuthManager.signInInteractive(activity, null)
                        emit(GenericAuthResult.UiActionRequired(authIntent))
                        Timber.tag(TAG)
                            .d("Google getAuthenticationIntentRequest: UiActionRequired emitted.")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(
                            e,
                            "Google getAuthenticationIntentRequest: Error during signInInteractive."
                        )
                        val googleErrorMapper =
                            getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                        val details = googleErrorMapper?.mapExceptionToErrorDetails(e)
                            ?: MappedErrorDetails(
                                "Failed to create Google sign-in intent: ${e.message}",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                e::class.java.simpleName
                            )
                        emit(
                            GenericAuthResult.Error(
                                details.message,
                                details.type,
                                details.providerSpecificErrorCode
                            )
                        )
                    }
                }
            }

            else -> {
                Timber.tag(TAG)
                    .w("Unsupported providerType for getAuthenticationIntentRequest: $providerType")
                flowOf(
                    GenericAuthResult.Error(
                        "Unsupported provider: $providerType",
                        GenericAuthErrorType.INVALID_REQUEST
                    )
                )
            }
        }
    }
}


