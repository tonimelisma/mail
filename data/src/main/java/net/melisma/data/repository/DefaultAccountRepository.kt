// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.CoroutineDispatcher
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
import net.melisma.backend_google.auth.ActiveGoogleAccountHolder
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleGetTokenResult
import net.melisma.backend_google.auth.GoogleSignInResult
import net.melisma.backend_google.auth.GoogleSignOutResult
import net.melisma.backend_google.common.GooglePersistenceErrorType
import net.melisma.backend_google.model.ManagedGoogleAccount
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.di.MicrosoftRepo
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.ErrorDetails
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.melisma.core_db.dao.AccountDao
import net.melisma.data.mapper.toDomainAccount
import net.melisma.data.mapper.toEntity
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

    private val _overallApplicationAuthState =
        MutableStateFlow<OverallApplicationAuthState>(OverallApplicationAuthState.UNKNOWN)
    override val overallApplicationAuthState: StateFlow<OverallApplicationAuthState> =
        _overallApplicationAuthState.asStateFlow()

    private val googleAuthResultChannel = Channel<GenericAuthResult>(Channel.CONFLATED)

    init {
        Timber.tag(TAG).d("Initializing DefaultAccountRepository. DAO is SSoT for accounts.")

        accountDao.getAllAccounts() // Observe DAO directly
            .map { entities -> entities.map { it.toDomainAccount() } } // Map to domain models
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

                if (activeGoogleAccountHolder.getActiveAccountIdValue() == null) {
                    accounts.firstOrNull { it.providerType == Account.PROVIDER_TYPE_GOOGLE && !it.needsReauthentication }
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
                    OverallApplicationAuthState.UNKNOWN
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
            needsReauthentication = false // New accounts from successful sign-in are not pending re-auth
        )
    }

    private fun getErrorMapperForProvider(providerType: String): ErrorMapperService? {
        return errorMappers[providerType.uppercase()]
    }

    private fun tryEmitMessage(message: String?) {
        val result = _accountActionMessage.tryEmit(message)
        Timber.tag(TAG).d("tryEmitMessage: Emitting message '$message'. Success: $result")
        if (!result) {
            Timber.tag(TAG)
                .w("tryEmitMessage: Failed to emit message. Buffer might be full or no subscribers.")
        }
    }

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
                            entity?.toDomainAccount()?.takeIf { !it.needsReauthentication }
                        }
                    }
                }
            }
            else -> flowOf(null)
        }
    }

    private fun mapGooglePersistenceFailureToAuthError(
        failure: PersistenceResult.Failure<GooglePersistenceErrorType>
    ): GenericAuthResult.Error {
        Timber.tag(TAG).w(failure.cause, "Google Persistence Failure: ${failure.errorType}")
        val message = failure.message ?: "Failed to save Google account details."
        val code = failure.errorType.name // Using enum name as code
        return GenericAuthResult.Error(
            ErrorDetails(
                message = message,
                code = code,
                cause = failure.cause
            )
        )
    }

    override fun signIn(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> {
        Timber.tag(TAG).i("signIn called for provider: $providerType, loginHint: $loginHint")
        _accountActionMessage.tryEmit(null) // Clear previous messages

        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                microsoftAccountRepository.signIn(activity, loginHint, providerType)
            }

            Account.PROVIDER_TYPE_GOOGLE -> flow {
                emit(GenericAuthResult.Loading)
                // For Google, we first get the intent, emit UiActionRequired,
                // then the actual result comes via handleAuthenticationResult -> googleAuthResultChannel
                try {
                    // Use the existing getAuthenticationIntentRequest structure
                    // GMAIL_SCOPES_FOR_LOGIN is passed but GoogleAuthManager.signInInteractive may not use it.
                    getAuthenticationIntentRequest(providerType, activity, GMAIL_SCOPES_FOR_LOGIN)
                        .collect { intentResult ->
                            // We expect UiActionRequired or an immediate Error from intent creation
                            emit(intentResult)
                            if (intentResult is GenericAuthResult.UiActionRequired) {
                                // Once intent is emitted, further results will come via the channel
                                googleAuthResultChannel.receiveAsFlow().collect { channelResult ->
                                    emit(channelResult)
                                }
                            }
                        }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Exception during Google signIn (intent request phase)")
                    val errorMapper = getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                    val errorDetails = errorMapper?.mapExceptionToErrorDetails(e)
                        ?: ErrorDetails(
                            message = e.message ?: "Failed to initiate Google sign-in.",
                            code = "GoogleSignInSetupException"
                        )
                    emit(GenericAuthResult.Error(errorDetails))
                }
            }

            else -> flowOf(
                GenericAuthResult.Error(
                    ErrorDetails(
                        message = "Unsupported provider type: $providerType",
                        code = "UnsupportedProvider"
                    )
                )
            )
        }
    }

    override fun signOut(account: Account): Flow<GenericSignOutResult> {
        Timber.tag(TAG)
            .i("signOut called for account: ${account.username} (Provider: ${account.providerType})")
        _accountActionMessage.tryEmit(null)
        return when (account.providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                microsoftAccountRepository.signOut(account)
            }

            Account.PROVIDER_TYPE_GOOGLE -> flow {
                emit(GenericSignOutResult.Loading)
                try {
                    Timber.tag(TAG).d("Attempting Google sign-out for account ID: ${account.id}")
                    googleAuthManager.signOut(account.id).collect { signOutResult ->
                        when (signOutResult) {
                            is GoogleSignOutResult.Success -> {
                                Timber.tag(TAG)
                                    .i("Google sign-out successful for account: ${account.username}")
                                accountDao.deleteAccount(account.id)
                                Timber.tag(TAG).i("Account ${account.username} removed from DB.")
                                if (activeGoogleAccountHolder.getActiveAccountIdValue() == account.id) {
                                    activeGoogleAccountHolder.setActiveAccountId(null) // Clear active account
                                    Timber.tag(TAG).d("Cleared active Google account ID.")
                                }
                                emit(GenericSignOutResult.Success)
                                _accountActionMessage.tryEmit("Signed out ${account.username}")
                            }

                            is GoogleSignOutResult.Error -> {
                                val errorMapper =
                                    getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                                // signOutResult.exception might be null if it's a persistenceFailure
                                val finalException = signOutResult.exception
                                    ?: signOutResult.persistenceFailure?.cause
                                val errorDetails = errorMapper?.mapExceptionToErrorDetails(
                                    finalException ?: Throwable(signOutResult.errorMessage)
                                )
                                    ?: ErrorDetails(
                                        message = signOutResult.errorMessage,
                                        code = signOutResult.persistenceFailure?.errorType?.name
                                            ?: "GoogleSignOutError"
                                    )

                                Timber.tag(TAG).w(
                                    finalException,
                                    "Google Sign-Out error: ${errorDetails.message}"
                                )
                                accountDao.deleteAccount(account.id) // Still attempt local cleanup
                                Timber.tag(TAG)
                                    .w("Account ${account.username} removed from DB despite sign-out error.")
                                if (activeGoogleAccountHolder.getActiveAccountIdValue() == account.id) {
                                    activeGoogleAccountHolder.setActiveAccountId(null)
                                }
                                emit(GenericSignOutResult.Error(errorDetails))
                                _accountActionMessage.tryEmit("Sign out error for ${account.username}: ${errorDetails.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG)
                        .e(e, "Exception during Google sign-out flow for ${account.username}")
                    val errorMapper = getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                    val errorDetails = errorMapper?.mapExceptionToErrorDetails(e)
                        ?: ErrorDetails(
                            message = e.message ?: "Unexpected error during sign-out.",
                            code = "GoogleSignOutException"
                        )
                    accountDao.deleteAccount(account.id) // Local cleanup on unexpected error
                    if (activeGoogleAccountHolder.getActiveAccountIdValue() == account.id) {
                        activeGoogleAccountHolder.setActiveAccountId(null)
                    }
                    emit(GenericSignOutResult.Error(errorDetails))
                    _accountActionMessage.tryEmit("Sign out error: ${errorDetails.message}")
                }
            }

            else -> flowOf(
                GenericSignOutResult.Error(
                    // Use account.providerType as providerType from argument might be out of scope or incorrect
                    ErrorDetails(
                        message = "Unsupported provider type: ${account.providerType}",
                        code = "UnsupportedProvider"
                    )
                )
            )
        }
    }

    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int, // Unused for new Google flow, AppAuth handles it. Kept for interface.
        data: Intent?
    ) {
        Timber.tag(TAG)
            .d("handleAuthenticationResult called for provider: $providerType, resultCode: $resultCode")
        _accountActionMessage.tryEmit(null)
        if (providerType.equals(Account.PROVIDER_TYPE_GOOGLE, ignoreCase = true)) {
            if (data == null) {
                Timber.tag(TAG)
                    .w("Google Auth: Null data intent received in handleAuthenticationResult.")
                googleAuthResultChannel.send(
                    GenericAuthResult.Error(
                        ErrorDetails(
                            message = "Authentication failed: No data received.",
                            code = "NullAuthIntentData"
                        )
                    )
                )
                return
            }

            val authResponse = AuthorizationResponse.fromIntent(data)
            val authException = AuthorizationException.fromIntent(data)

            googleAuthManager.handleAuthorizationResponse(authResponse, authException)
                .collect { googleResult ->
                    when (googleResult) {
                        is GoogleSignInResult.Success -> {
                            val genericAccount =
                                mapManagedGoogleAccountToGenericAccount(googleResult.managedAccount)
                            Timber.tag(TAG)
                                .i("Google Auth (handleResult) successful for: ${genericAccount.username}, saving to DB.")
                            val saveOpResult = saveAndSetAccountActive(
                                genericAccount,
                                Account.PROVIDER_TYPE_GOOGLE
                            )
                            googleAuthResultChannel.send(saveOpResult)
                            if (saveOpResult is GenericAuthResult.Success) {
                                _accountActionMessage.tryEmit("Signed in as ${saveOpResult.account.username}")
                            } else if (saveOpResult is GenericAuthResult.Error) {
                                _accountActionMessage.tryEmit("Google sign-in error: ${saveOpResult.details.message}")
                            }
                        }

                        is GoogleSignInResult.Error -> {
                            val errorMapper =
                                getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                            val errorDetails = errorMapper?.mapExceptionToErrorDetails(
                                googleResult.exception ?: Throwable(googleResult.errorMessage)
                            )
                                ?: ErrorDetails(
                                    message = googleResult.errorMessage,
                                    code = googleResult.persistenceFailure?.errorType?.name
                                        ?: "GoogleAuthError"
                                )
                            Timber.tag(TAG).w(
                                googleResult.exception,
                                "Google Auth (handleResult) error: ${errorDetails.message}"
                            )
                            googleAuthResultChannel.send(GenericAuthResult.Error(errorDetails))
                            _accountActionMessage.tryEmit("Google sign-in error: ${errorDetails.message}")
                        }

                        is GoogleSignInResult.Cancelled -> {
                            Timber.tag(TAG).i("Google Auth (handleResult) cancelled by user.")
                            val errorDetails = ErrorDetails(
                                message = "Sign-in cancelled by user.",
                                code = "UserCancellation"
                            )
                            googleAuthResultChannel.send(GenericAuthResult.Error(errorDetails))
                            _accountActionMessage.tryEmit("Sign-in cancelled.")
                        }
                    }
                }
        } else {
            microsoftAccountRepository.handleAuthenticationResult(providerType, resultCode, data)
        }
    }

    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        scopes: List<String>? // Scopes for Google are now typically managed by GoogleAuthManager or are standard
    ): Flow<GenericAuthResult> {
        _accountActionMessage.tryEmit(null)
        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_GOOGLE -> flow {
                emit(GenericAuthResult.Loading)
                try {
                    // loginHint is not directly passed to signInInteractive in this revised flow,
                    // but could be retrieved from a user input field if needed.
                    // For now, passing null as loginHint.
                    val authRequestIntent = googleAuthManager.signInInteractive(
                        activity,
                        null
                    ) // loginHint can be passed if available
                    emit(GenericAuthResult.UiActionRequired(authRequestIntent))
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to get Google authentication intent")
                    val errorMapper = getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                    val errorDetails = errorMapper?.mapExceptionToErrorDetails(e)
                        ?: ErrorDetails(
                            message = e.message ?: "Could not prepare Google authentication.",
                            code = "GoogleIntentPrepError"
                        )
                    emit(GenericAuthResult.Error(errorDetails))
                }
            }

            Account.PROVIDER_TYPE_MS -> {
                microsoftAccountRepository.getAuthenticationIntentRequest(
                    providerType,
                    activity,
                    scopes
                )
            }

            else -> flowOf(
                GenericAuthResult.Error(
                    ErrorDetails(
                        message = "Unsupported provider: $providerType for auth intent.",
                        code = "UnsupportedProviderIntent"
                    )
                )
            )
        }
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        Timber.tag(TAG)
            .i("Marking account $accountId (Provider: $providerType) for re-authentication.")
        _accountActionMessage.tryEmit(null)
        val accountEntity = accountDao.getAccountById(accountId).firstOrNull()
        if (accountEntity != null) {
            val updatedEntity = accountEntity.copy(needsReauthentication = true)
            val result: PersistenceResult<Unit> =
                try {
                    accountDao.insertOrUpdateAccount(updatedEntity)
                    PersistenceResult.Success(Unit)
                } catch (e: Exception) {
                    Timber.tag(TAG)
                        .e(e, "DAO error marking account $accountId for re-authentication.")
                    PersistenceResult.Failure(
                        errorType = GooglePersistenceErrorType.STORAGE_FAILED, // Or a more generic DB error type if available
                        message = e.message ?: "Failed to update account in DB.",
                        cause = e
                    )
                }

            if (result is PersistenceResult.Failure<*>) { // Use wildcard for the type if not specific
                Timber.tag(TAG).e(
                    result.cause,
                    "Failed to mark account $accountId for re-authentication in DB: ${result.message}"
                )
                _accountActionMessage.tryEmit("Error updating account state for re-authentication.")
            } else {
                Timber.tag(TAG)
                    .i("Account $accountId successfully marked for re-authentication in DB.")
                _accountActionMessage.tryEmit("Account ${accountEntity.toDomainAccount().username} marked for re-authentication.")
            }

        } else {
            Timber.tag(TAG)
                .w("Account $accountId not found in DB. Cannot mark for re-authentication.")
            _accountActionMessage.tryEmit("Could not find account to mark for re-authentication.")
        }
    }

    override fun clearActionMessage() {
        Timber.tag(TAG).d("Clearing action message.")
        _accountActionMessage.tryEmit(null)
    }

    // Centralized function for handling account saving and active account setting
    private suspend fun saveAndSetAccountActive(
        account: Account,
        providerType: String
    ): GenericAuthResult {
        // Ensure PersistenceResult type is correctly handled
        val saveResult: PersistenceResult<Unit> =
            try {
                accountDao.insertOrUpdateAccount(account.toEntity())
                PersistenceResult.Success(Unit)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "DAO error saving account ${account.username}.")
                PersistenceResult.Failure(
                    errorType = GooglePersistenceErrorType.STORAGE_FAILED, // Or a more generic DB error type
                    message = e.message ?: "Failed to save account ${account.username} to DB.",
                    cause = e
                )
            }

        return if (saveResult is PersistenceResult.Success<Unit>) { // Specify type for Success
            Timber.tag(TAG)
                .i("Account ${account.username} ($providerType) saved to DB successfully.")
            if (providerType == Account.PROVIDER_TYPE_GOOGLE) {
                activeGoogleAccountHolder.setActiveAccountId(account.id)
            }
            GenericAuthResult.Success(account)
        } else {
            // Assuming saveResult must be Failure if not Success
            val failure = saveResult as PersistenceResult.Failure<*> // Use wildcard
            Timber.tag(TAG).e(
                failure.cause,
                "Failed to save $providerType account ${account.username} to DB: ${failure.message}"
            )

            val errorCode = if (failure.errorType is Enum<*>) {
                (failure.errorType as Enum<*>).name
            } else {
                failure.errorType?.toString() ?: "DB_SAVE_ERROR"
            }

            val errorDetails = ErrorDetails(
                message = failure.message ?: "Failed to save account.",
                code = errorCode,
                cause = failure.cause
            )
            GenericAuthResult.Error(errorDetails)
        }
    }

    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Timber.tag(TAG).i("syncAccount called for accountId: $accountId")
        _accountActionMessage.tryEmit(null)
        val account = accountDao.getAccountById(accountId).firstOrNull()?.toDomainAccount()

        return if (account == null) {
            Timber.tag(TAG).w("Account $accountId not found in DB for sync.")
            _accountActionMessage.tryEmit("Account not found for sync.")
            Result.failure(Exception("Account $accountId not found for sync."))
        } else {
            when (account.providerType.uppercase()) {
                Account.PROVIDER_TYPE_MS -> microsoftAccountRepository.syncAccount(accountId)
                Account.PROVIDER_TYPE_GOOGLE -> {
                    Timber.tag(TAG)
                        .d("Google account sync for ${account.username}: checking token status.")
                    try {
                        when (val tokenResult = googleAuthManager.getFreshAccessToken(account.id)) {
                            is GoogleGetTokenResult.Success -> {
                                Timber.tag(TAG)
                                    .i("Google token refresh successful for ${account.username}.")
                                if (account.needsReauthentication) {
                                    Timber.tag(TAG)
                                        .i("Google account ${account.username} no longer requires re-authentication. Updating DB.")
                                    val updatedEntity =
                                        account.toEntity().copy(needsReauthentication = false)
                                    accountDao.insertOrUpdateAccount(updatedEntity)
                                    _accountActionMessage.tryEmit("${account.username} is now re-authenticated.")
                                }
                                Result.success(Unit)
                            }

                            is GoogleGetTokenResult.NeedsReauthentication -> {
                                Timber.tag(TAG)
                                    .w("Google account ${account.username} requires re-authentication (explicit). Marking in DB.")
                                if (!account.needsReauthentication) {
                                    markAccountForReauthentication(
                                        account.id,
                                        Account.PROVIDER_TYPE_GOOGLE
                                    ) // This will also emit a message
                                } else {
                                    _accountActionMessage.tryEmit("${account.username} still requires re-authentication.")
                                }
                                // For sync, this is still a "successful" sync in that we determined state.
                                // The re-auth is a state of the account, not necessarily a sync failure.
                                // However, the calling UI might want to know this. For now, treat as success.
                                Result.success(Unit) // Or Result.failure if strict "synced" means "token valid"
                            }

                            is GoogleGetTokenResult.Error -> {
                                Timber.tag(TAG).w(
                                    tokenResult.exception,
                                    "Google token refresh error for ${account.username}: ${tokenResult.errorMessage}"
                                )
                                val needsReAuthError =
                                    tokenResult.errorType == GooglePersistenceErrorType.TOKEN_REFRESH_INVALID_GRANT ||
                                            tokenResult.errorMessage.contains(
                                                "invalid_grant",
                                                ignoreCase = true
                                            )

                                if (needsReAuthError && !account.needsReauthentication) {
                                    Timber.tag(TAG)
                                        .i("Google account ${account.username} needs re-auth due to token error. Marking.")
                                    markAccountForReauthentication(
                                        account.id,
                                        Account.PROVIDER_TYPE_GOOGLE
                                    )
                                } else if (needsReAuthError) {
                                    _accountActionMessage.tryEmit("${account.username} still requires re-authentication.")
                                } else {
                                    _accountActionMessage.tryEmit("Error syncing ${account.username}: ${tokenResult.errorMessage}")
                                }
                                // Depending on severity, could be success (state determined) or failure
                                Result.failure(
                                    tokenResult.exception
                                        ?: Exception("Token refresh failed: ${tokenResult.errorMessage}")
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(
                            e,
                            "Exception during Google account sync check for ${account.username}"
                        )
                        _accountActionMessage.tryEmit("Error syncing ${account.username}: ${e.message}")
                        Result.failure(e)
                    }
                }

                else -> {
                    Timber.tag(TAG).w("Unsupported provider type for sync: ${account.providerType}")
                    _accountActionMessage.tryEmit("Cannot sync account: Unsupported provider.")
                    Result.failure(Exception("Unsupported provider type for sync: ${account.providerType}"))
                }
            }
        }
    }
}


