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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import net.melisma.backend_google.auth.ActiveGoogleAccountHolder
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleGetTokenResult
import net.melisma.backend_google.auth.GoogleSignInResult
import net.melisma.backend_google.auth.GoogleSignOutResult
import net.melisma.backend_google.common.GooglePersistenceErrorType
import net.melisma.backend_google.model.ManagedGoogleAccount
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
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
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder,
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
            .distinctUntilChanged() // Only proceed if the list of accounts has actually changed
            .onEach { accounts ->
                Timber.tag(TAG)
                    .d("DAO emitted DISTINCT accounts list (count: ${accounts.size}). Usernames: ${accounts.joinToString { it.username }}. Current _overallApplicationAuthState: ${_overallApplicationAuthState.value}. About to update auth state.")
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

                // Sync ActiveGoogleAccountHolder
                if (activeGoogleAccountHolder.getActiveAccountIdValue() == null) {
                    accounts.firstOrNull { it.providerType == Account.PROVIDER_TYPE_GOOGLE && !it.needsReauthentication }
                        ?.let { googleAccount ->
                            Timber.tag(TAG)
                                .i("Init: Setting active Google account from DAO: ${googleAccount.username} (ID: ${googleAccount.id})")
                            activeGoogleAccountHolder.setActiveAccountId(googleAccount.id)
                        }
                } else {
                    // Verify existing active Google account
                    val currentActiveGoogleId = activeGoogleAccountHolder.getActiveAccountIdValue()
                    val isActiveGoogleAccountStillValid = accounts.any {
                        it.id == currentActiveGoogleId && it.providerType == Account.PROVIDER_TYPE_GOOGLE && !it.needsReauthentication
                    }
                    if (!isActiveGoogleAccountStillValid) {
                        Timber.tag(TAG)
                            .w("Init: Active Google account $currentActiveGoogleId is no longer valid or needs re-auth. Clearing and attempting to set a new one.")
                        activeGoogleAccountHolder.setActiveAccountId(null)
                        accounts.firstOrNull { it.providerType == Account.PROVIDER_TYPE_GOOGLE && !it.needsReauthentication }
                            ?.let {
                                Timber.tag(TAG)
                                    .i("Init: Setting new active Google account from DAO: ${it.username} (ID: ${it.id})")
                                activeGoogleAccountHolder.setActiveAccountId(it.id)
                            }
                    }
                }

                // Sync ActiveMicrosoftAccountHolder (New Logic)
                val currentActiveMicrosoftId =
                    activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue()
                if (currentActiveMicrosoftId == null) {
                    accounts.firstOrNull { it.providerType == Account.PROVIDER_TYPE_MS && !it.needsReauthentication }
                        ?.let { msAccount ->
                            Timber.tag(TAG)
                                .i("Init: Setting active Microsoft account from DAO: ${msAccount.username} (ID: ${msAccount.id})")
                            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(msAccount.id)
                        }
                } else {
                    val isActiveMicrosoftAccountStillValid = accounts.any {
                        it.id == currentActiveMicrosoftId && it.providerType == Account.PROVIDER_TYPE_MS && !it.needsReauthentication
                    }
                    if (!isActiveMicrosoftAccountStillValid) {
                        Timber.tag(TAG)
                            .w("Init: Active Microsoft account $currentActiveMicrosoftId is no longer valid or needs re-auth. Clearing and attempting to set a new one.")
                        activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                        accounts.firstOrNull { it.providerType == Account.PROVIDER_TYPE_MS && !it.needsReauthentication }
                            ?.let {
                                Timber.tag(TAG)
                                    .i("Init: Setting new active Microsoft account from DAO: ${it.username} (ID: ${it.id})")
                                activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(it.id)
                            }
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
        }.distinctUntilChanged()
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
                    .transform { result -> // Changed from direct return to transform
                        if (result is GenericAuthResult.Success) {
                            Timber.tag(TAG)
                                .i("Microsoft sign-in successful for: ${result.account.username}, saving to DB.")
                            val saveOpResult = saveAndSetAccountActive(
                                result.account, // The account from Microsoft
                                Account.PROVIDER_TYPE_MS
                            )
                            if (saveOpResult is GenericAuthResult.Success) {
                                _accountActionMessage.tryEmit("Signed in as ${saveOpResult.account.username}")
                                emit(saveOpResult) // Emit the success with potentially updated account from DB
                            } else if (saveOpResult is GenericAuthResult.Error) {
                                _accountActionMessage.tryEmit("Microsoft sign-in error during DB save: ${saveOpResult.details.message}")
                                emit(saveOpResult) // Emit the DB save error
                            }
                        } else {
                            // Emit other results (Loading, Error, UiActionRequired) directly
                            emit(result)
                        }
                    }
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
                    .transform { result ->
                        if (result is GenericSignOutResult.Success) {
                            Timber.tag(TAG)
                                .i("Microsoft sign-out successful for account: ${account.username}. Removing from DB.")
                            try {
                                accountDao.deleteAccount(account.id)
                                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                                    Timber.tag(TAG)
                                        .d("Cleared active Microsoft account ID after sign-out and DB delete.")
                                }
                                _accountActionMessage.tryEmit("Signed out ${account.username}")
                                emit(GenericSignOutResult.Success)
                            } catch (e: Exception) {
                                Timber.tag(TAG)
                                    .e(
                                        e,
                                        "DAO error deleting Microsoft account ${account.username} after sign-out."
                                    )
                                val errorDetails = ErrorDetails(
                                    message = "Failed to remove account from local database: ${e.message}",
                                    code = "DAO_DELETE_ERROR",
                                    cause = e
                                )
                                // Still emit success for the MSAL part, but signal local error maybe?
                                // For now, if MSAL succeeded, we try local, if local fails, it's a bit of a mixed state.
                                // Let's emit the original success from MSAL, but log the DAO error.
                                // Or, we could emit an error that wraps this.
                                // Deciding to emit an error if DAO operation fails to make it visible.
                                _accountActionMessage.tryEmit("Signed out ${account.username}, but failed to clear all local data.")
                                emit(GenericSignOutResult.Error(errorDetails)) // Emit error if DAO fails
                            }
                        } else {
                            // Emit other results (Loading, Error) directly
                            emit(result)
                        }
                    }
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
            .d("markAccountForReauthentication called for $accountId, provider $providerType")
        val accountEntity = accountDao.getAccountById(accountId).firstOrNull()
        if (accountEntity != null) {
            if (accountEntity.providerType.equals(providerType, ignoreCase = true)) {
                if (!accountEntity.needsReauthentication) {
                    val updatedEntity = accountEntity.copy(needsReauthentication = true)
                    try {
                        accountDao.insertOrUpdateAccount(updatedEntity)
                        Timber.tag(TAG)
                            .i("Account $accountId successfully marked for re-authentication in DB by DefaultAccountRepository.")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(
                            e,
                            "DAO error in DefaultAccountRepository marking $accountId for re-authentication."
                        )
                    }
                } else {
                    Timber.tag(TAG)
                        .i("Account $accountId was already marked for re-authentication.")
                }
            } else {
                Timber.tag(TAG)
                    .w("Provider type mismatch. Account $accountId is ${accountEntity.providerType}, requested $providerType")
            }
        } else {
            Timber.tag(TAG)
                .w("Account $accountId not found in DB. Cannot mark for re-authentication.")
        }
    }

    override fun clearActionMessage() {
        tryEmitMessage(null)
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
            val failure =
                saveResult as PersistenceResult.Failure<*> // Use wildcard. Cast is needed for type inference of properties.
            Timber.tag(TAG).e(
                failure.cause,
                "Failed to save $providerType account ${account.username} to DB: ${failure.message}"
            )

            val errorTypeVal = failure.errorType // Explicitly store to help linter

            val errorCode = (errorTypeVal as Enum<*>).name

            val errorDetails = ErrorDetails(
                message = failure.message ?: "Failed to save account.",
                code = errorCode,
                cause = failure.cause
            )
            GenericAuthResult.Error(errorDetails)
        }
    }

    // Helper function to sync a Google account
    private suspend fun syncGoogleAccount(accountId: String): Result<Unit> {
        Timber.tag(TAG).d("syncGoogleAccount called for $accountId")
        val accountEntity = accountDao.getAccountById(accountId).firstOrNull()
        if (accountEntity == null) {
            Timber.tag(TAG).w("syncGoogleAccount: Account $accountId not found in DB.")
            return Result.failure(Exception("Account $accountId not found for Google sync"))
        }

        if (accountEntity.providerType != Account.PROVIDER_TYPE_GOOGLE) {
            Timber.tag(TAG).w("syncGoogleAccount: Account $accountId is not a Google account.")
            return Result.failure(Exception("Account $accountId is not Google for sync"))
        }

        Timber.tag(TAG).d("syncGoogleAccount: Attempting to get token for $accountId")
        return try {
            // Call the correct suspend function to get the token result directly
            val tokenResult = googleAuthManager.getFreshAccessToken(accountId)

            when (tokenResult) {
                is GoogleGetTokenResult.Success -> {
                    Timber.tag(TAG).i("syncGoogleAccount: Token refresh successful for $accountId.")
                    if (accountEntity.needsReauthentication) {
                        try {
                            accountDao.insertOrUpdateAccount(
                                accountEntity.copy(
                                    needsReauthentication = false
                                )
                            )
                            Timber.tag(TAG)
                                .i("syncGoogleAccount: Cleared needsReauthentication flag for $accountId in DB.")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(
                                e,
                                "syncGoogleAccount: DAO error clearing needsReauthentication for $accountId."
                            )
                        }
                    }
                    Result.success(Unit)
                }

                is GoogleGetTokenResult.NeedsReauthentication -> {
                    Timber.tag(TAG)
                        .w("syncGoogleAccount: Token refresh for $accountId requires re-authentication (reported by AuthManager).")
                    if (!accountEntity.needsReauthentication) {
                        try {
                            accountDao.insertOrUpdateAccount(
                                accountEntity.copy(
                                    needsReauthentication = true
                                )
                            )
                            Timber.tag(TAG)
                                .i("syncGoogleAccount: Set needsReauthentication flag for $accountId in DB.")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(
                                e,
                                "syncGoogleAccount: DAO error setting needsReauthentication for $accountId."
                            )
                        }
                    }
                    Result.failure(Exception("Google account $accountId needs re-authentication as per AuthManager"))
                }

                is GoogleGetTokenResult.Error -> {
                    Timber.tag(TAG).e(
                        tokenResult.exception,
                        "syncGoogleAccount: Error refreshing token for $accountId. Message: ${tokenResult.errorMessage}"
                    )
                    val authException = tokenResult.exception as? AuthorizationException
                    val isInvalidGrantError =
                        authException?.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code &&
                                authException.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR

                    // Check for invalid grant specifically, or a specific persistence error type indicating invalid grant
                    if (isInvalidGrantError || tokenResult.errorType == GooglePersistenceErrorType.TOKEN_REFRESH_INVALID_GRANT) {
                        Timber.tag(TAG)
                            .w("syncGoogleAccount: Error for $accountId (${tokenResult.errorMessage}) implies re-authentication (isInvalidGrant: $isInvalidGrantError, errorType: ${tokenResult.errorType}).")
                        if (!accountEntity.needsReauthentication) {
                            try {
                                accountDao.insertOrUpdateAccount(
                                    accountEntity.copy(
                                        needsReauthentication = true
                                    )
                                )
                                Timber.tag(TAG)
                                    .i("syncGoogleAccount: Set needsReauthentication flag for $accountId due to error.")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(
                                    e,
                                    "syncGoogleAccount: DAO error setting needsReauthentication for $accountId on error."
                                )
                            }
                        }
                    } else {
                        Timber.tag(TAG)
                            .d("syncGoogleAccount: Error for $accountId (${tokenResult.errorMessage}) does not seem to require re-auth immediately (isInvalidGrant: $isInvalidGrantError, errorType: ${tokenResult.errorType}).")
                    }
                    Result.failure(tokenResult.exception ?: Exception(tokenResult.errorMessage))
                }
                // No 'null' case needed here as getFreshAccessToken directly returns GoogleGetTokenResult, not a Flow that could be empty.
                // The 'when' should be exhaustive for the sealed class GoogleGetTokenResult.
            }
        } catch (e: Exception) {
            Timber.tag(TAG)
                .e(e, "syncGoogleAccount: Generic exception during token refresh for $accountId.")
            Result.failure(e)
        }
    }

    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Timber.tag(TAG)
            .d("syncAccount called for accountId: $accountId in DefaultAccountRepository")
        val account = accountDao.getAccountById(accountId).firstOrNull()?.toDomainAccount()
            ?: return Result.failure(Exception("Account not found: $accountId"))

        return when (account.providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                Timber.tag(TAG)
                    .d("syncAccount: Delegating to MicrosoftAccountRepository for $accountId")
                microsoftAccountRepository.syncAccount(accountId)
            }

            Account.PROVIDER_TYPE_GOOGLE -> {
                Timber.tag(TAG).d("syncAccount: Handling Google account $accountId sync directly.")
                syncGoogleAccount(accountId)
            }

            else -> {
                Timber.tag(TAG)
                    .w("syncAccount: Unsupported provider type ${account.providerType} for account $accountId")
                Result.failure(Exception("Unsupported provider type: ${account.providerType}"))
            }
        }
    }

    override suspend fun getAccountByIdSuspend(accountId: String): Account? {
        Timber.tag(TAG).d("getAccountByIdSuspend called for accountId: $accountId")
        val entity =
            accountDao.getAccountByIdSuspend(accountId)
        return entity?.toDomainAccount()
    }
}


