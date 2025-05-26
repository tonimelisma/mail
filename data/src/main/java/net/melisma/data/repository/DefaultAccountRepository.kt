// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent
import android.util.Log
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
import kotlinx.coroutines.flow.first
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
import net.melisma.backend_google.common.GooglePersistenceErrorType
import net.melisma.backend_google.model.ManagedGoogleAccount
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
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

                if (activeGoogleAccountHolder.activeAccountId.value == null) {
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

    override fun signIn(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> {
        Timber.tag(TAG).d("signIn called for provider: $providerType, loginHint: $loginHint")
        _accountActionMessage.tryEmit(null)

        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> microsoftAccountRepository.signIn(
                activity,
                loginHint,
                providerType
            )
            Account.PROVIDER_TYPE_GOOGLE -> {
                externalScope.launch {
                    try {
                        Timber.tag(TAG)
                            .d("Google Sign-In: Calling googleAuthManager.signInInteractive.")
                        val authIntent = googleAuthManager.signInInteractive(activity, loginHint)
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
                                "Failed to initiate Google sign-in: ${e.message ?: "Unknown cause"}",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                e.javaClass.simpleName
                            )
                        googleAuthResultChannel.trySend(
                            GenericAuthResult.Error(
                                message = details.message,
                                type = details.type,
                                providerSpecificErrorCode = details.providerSpecificErrorCode
                            )
                        )
                    }
                }
                googleAuthResultChannel.receiveAsFlow()
                    .onEach { Timber.tag(TAG).d("Google signIn Flow emitting: $it") }
            }
            else -> {
                Timber.tag(TAG).w("Unsupported providerType for signIn: $providerType")
                flowOf(
                    GenericAuthResult.Error(
                        message = "Unsupported provider: $providerType",
                        type = GenericAuthErrorType.INVALID_REQUEST
                    )
                )
            }
        }
    }

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
                                externalScope.launch(ioDispatcher) {
                                    Timber.tag(TAG)
                                        .d("Google Auth Success: Saving account ${account.username} to DAO.")
                                    accountDao.insertOrUpdateAccount(account.toEntity())
                                    activeGoogleAccountHolder.setActiveAccountId(account.id)
                                }
                                GenericAuthResult.Success(account)
                            }
                            is GoogleSignInResult.Error -> {
                                val details =
                                    getErrorMapperForProvider(Account.PROVIDER_TYPE_GOOGLE)
                                        ?.mapExceptionToErrorDetails(result.exception)
                                        ?: MappedErrorDetails(
                                            result.errorMessage,
                                            GenericAuthErrorType.AUTHENTICATION_FAILED,
                                            result.exception?.javaClass?.simpleName
                                        )
                                GenericAuthResult.Error(
                                    message = details.message,
                                    type = details.type,
                                    providerSpecificErrorCode = details.providerSpecificErrorCode
                                )
                            }
                            is GoogleSignInResult.Cancelled -> GenericAuthResult.Cancelled
                            else -> {
                                Timber.tag(TAG).e("Unexpected GoogleSignInResult type: $result")
                                GenericAuthResult.Error(
                                    message = "Unexpected Google sign-in result: ${result::class.java.simpleName}",
                                    type = GenericAuthErrorType.UNKNOWN_ERROR,
                                    providerSpecificErrorCode = "UNEXPECTED_TYPE"
                                )
                            }
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
                                "Google sign-in processing error: ${e.message ?: "Unknown cause"}",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                e.javaClass.simpleName
                            )
                        googleAuthResultChannel.trySend(
                            GenericAuthResult.Error(
                                message = details.message,
                                type = details.type,
                                providerSpecificErrorCode = details.providerSpecificErrorCode
                            )
                        )
                    }
                    .launchIn(externalScope)
            }
        }
    }

    override fun signOut(account: Account): Flow<GenericSignOutResult> {
        Timber.tag(TAG)
            .i("signOut called for account: ${account.username} (Provider: ${account.providerType})")
        return flow {
            val providerTypeUpper = account.providerType.uppercase()
            try {
                val result: GenericSignOutResult = when (providerTypeUpper) {
                    Account.PROVIDER_TYPE_MS -> {
                        microsoftAccountRepository.signOut(account).firstOrNull()?.also {
                            if (it is GenericSignOutResult.Success) {
                                externalScope.launch(ioDispatcher) {
                                    Timber.tag(TAG)
                                        .d("MS Sign-Out Success for ${account.username}: Deleting from DAO.")
                                    accountDao.deleteAccount(account.id)
                                }
                            }
                        } ?: GenericSignOutResult.Error(
                            message = "MS sign out failed or flow empty",
                            type = GenericAuthErrorType.AUTHENTICATION_FAILED
                        )
                    }
                    Account.PROVIDER_TYPE_GOOGLE -> {
                        // Collect the single result from the Flow returned by googleAuthManager.signOut
                        val actualSignOutResult = googleAuthManager.signOut(account.id).first()
                        when (actualSignOutResult) {
                            is GoogleSignOutResult.Success -> {
                                externalScope.launch(ioDispatcher) {
                                    Timber.tag(TAG)
                                        .d("Google Sign-Out Success for ${account.username}: Deleting from DAO.")
                                    accountDao.deleteAccount(account.id)
                                }
                                if (activeGoogleAccountHolder.activeAccountId.value == account.id) {
                                    activeGoogleAccountHolder.setActiveAccountId(null)
                                }
                                GenericSignOutResult.Success
                            }
                            is GoogleSignOutResult.Error -> {
                                Timber.tag(TAG)
                                    .w("GoogleAuthManager sign out failed for ${account.username}: ${actualSignOutResult.errorMessage}. DAO state not changed.")
                                GenericSignOutResult.Error(
                                    message = "Google sign out failed: ${actualSignOutResult.errorMessage}",
                                    type = GenericAuthErrorType.AUTHENTICATION_FAILED
                                )
                            }

                            else -> {
                                Timber.tag(TAG)
                                    .e("Unexpected GoogleSignOutResult type: $actualSignOutResult")
                                GenericSignOutResult.Error(
                                    message = "Unexpected Google sign-out result: ${actualSignOutResult::class.simpleName}",
                                    type = GenericAuthErrorType.UNKNOWN_ERROR,
                                    providerSpecificErrorCode = "UNEXPECTED_SIGNOUT_TYPE"
                                )
                            }
                        }
                    }

                    else -> GenericSignOutResult.Error(
                        message = "Unsupported provider for sign out: ${account.providerType}",
                        type = GenericAuthErrorType.INVALID_REQUEST
                    )
                }
                emit(result)
                if (result is GenericSignOutResult.Success) {
                    tryEmitMessage("Signed out ${account.username}")
                } else if (result is GenericSignOutResult.Error) {
                    tryEmitMessage("Sign out error for ${account.username}: ${result.message}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Exception during signOut for ${account.username}")
                val mappedDetailsFromErrorMapper: MappedErrorDetails? =
                    getErrorMapperForProvider(providerTypeUpper)?.mapExceptionToErrorDetails(e)

                val finalErrorDetails: MappedErrorDetails =
                    mappedDetailsFromErrorMapper ?: MappedErrorDetails(
                        message = "Sign out failed: ${e.message ?: "Unknown cause"}",
                        type = GenericAuthErrorType.UNKNOWN_ERROR
                        )
                emit(
                    GenericSignOutResult.Error(
                        message = finalErrorDetails.message,
                        type = finalErrorDetails.type,
                        providerSpecificErrorCode = finalErrorDetails.providerSpecificErrorCode
                    )
                )
                tryEmitMessage("Sign out error for ${account.username}: ${finalErrorDetails.message}")
            }
        }
    }

    override fun clearActionMessage() {
        Timber.tag(TAG).d("clearActionMessage called.")
        _accountActionMessage.tryEmit(null)
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        Timber.tag(TAG)
            .d("Marking account for re-authentication: ID $accountId, Provider: $providerType")
        if (providerType == Account.PROVIDER_TYPE_GOOGLE) {
            val authManagerResult = googleAuthManager.requestReauthentication(accountId)
            val accountEntity = accountDao.getAccountById(accountId).firstOrNull()

            if (accountEntity != null) {
                Timber.tag(TAG)
                    .i("GoogleAuthManager.requestReauthentication for ${accountEntity.username} (ID: $accountId) completed. Result: $authManagerResult")

                val updatedEntity = accountEntity.copy(needsReauthentication = true)
                accountDao.insertOrUpdateAccount(updatedEntity)
                Timber.tag(TAG)
                    .d("Account $accountId in DAO marked for needsReauthentication = true")

                if (authManagerResult is PersistenceResult.Failure<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val failure =
                        authManagerResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                    Timber.tag(TAG).w(
                        "GoogleAuthManager.requestReauthentication call returned failure for $accountId: ${failure.errorType}.",
                        failure.cause
                    )
                    _accountActionMessage.tryEmit("Could not fully process re-auth request with Google: ${failure.message}")
                } else {
                    _accountActionMessage.tryEmit("Account ${accountEntity.username} marked for re-authentication.")
                }
            } else {
                Timber.tag(TAG)
                    .w("Could not find Google account with ID $accountId in DAO to mark for re-authentication.")
                _accountActionMessage.tryEmit("Account not found to mark for re-authentication.")
            }
        } else if (providerType == Account.PROVIDER_TYPE_MS) {
            Timber.tag(TAG)
                .d("Marking Microsoft account $accountId for re-authentication. Delegating to MicrosoftAccountRepository.")
            microsoftAccountRepository.markAccountForReauthentication(accountId, providerType)
        } else {
            Timber.tag(TAG).w("Unknown provider type for marking re-authentication: $providerType")
        }
    }

    fun getGoogleLoginScopes(): List<String> {
        return GMAIL_SCOPES_FOR_LOGIN
    }

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

    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Log.d(TAG, "syncAccount called for accountId: $accountId")
        Timber.tag(TAG).w("syncAccount for $accountId not fully implemented. Placeholder.")
        return Result.failure(NotImplementedError("syncAccount not implemented for $accountId"))
    }

    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        scopes: List<String>?
    ): Flow<GenericAuthResult> {
        Timber.tag(TAG).d("getAuthenticationIntentRequest called for provider: $providerType")
        _accountActionMessage.tryEmit(null)

        return when (providerType.uppercase()) {
            Account.PROVIDER_TYPE_MS -> {
                (microsoftAccountRepository as? AccountRepository)?.getAuthenticationIntentRequest(
                    providerType,
                    activity,
                    scopes
                ) ?: flowOf(
                    GenericAuthResult.Error(
                        message = "Microsoft provider does not support getAuthenticationIntentRequest or is not of expected type.",
                        type = GenericAuthErrorType.INVALID_REQUEST
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
                                "Failed to create Google sign-in intent: ${e.message ?: "Unknown cause"}",
                                GenericAuthErrorType.UNKNOWN_ERROR,
                                e.javaClass.simpleName
                            )
                        emit(
                            GenericAuthResult.Error(
                                message = details.message,
                                type = details.type,
                                providerSpecificErrorCode = details.providerSpecificErrorCode
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
                        message = "Unsupported provider: $providerType",
                        type = GenericAuthErrorType.INVALID_REQUEST
                    )
                )
            }
        }
    }
}


