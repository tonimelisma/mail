// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package net.melisma.backend_microsoft.repository

import android.app.Activity
import android.content.Intent
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import net.melisma.backend_microsoft.auth.AcquireTokenResult
import net.melisma.backend_microsoft.auth.AuthenticationResultWrapper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.SignOutResultWrapper
import net.melisma.backend_microsoft.mapper.toDomainAccount
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.ErrorDetails
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val activeMicrosoftAccountHolder: net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
) : AccountRepository {

    private val TAG = "MsAccountRepo"

    override fun getAccounts(): Flow<List<Account>> {
        Timber.tag(TAG).d("getAccounts called")
        return microsoftAuthManager.msalAccounts
            .map { iAccountList ->
                Timber.tag(TAG)
                    .d("getAccounts: Received ${iAccountList.size} IAccounts from AuthManager. Mapping to domain models.")
                iAccountList.map { it.toDomainAccount() }
            }
            .catch { e ->
                Timber.tag(TAG).e(e, "Error in getAccounts flow")
                emit(emptyList())
            }
            .flowOn(ioDispatcher)
    }

    override fun getAccountById(accountId: String): Flow<Account?> {
        Timber.tag(TAG).d("getAccountById called for: ${accountId.take(5)}...")
        return getAccounts().map { accounts ->
            accounts.find { it.id == accountId }
        }.flowOn(ioDispatcher)
    }

    override fun getActiveAccount(providerType: String): Flow<Account?> {
        Timber.tag(TAG).d("getActiveAccount called for provider: $providerType")
        if (providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            return activeMicrosoftAccountHolder.activeMicrosoftAccountId.combine(
                microsoftAuthManager.msalAccounts
            ) { activeId, msalAccounts ->
                activeId?.let { msalAccounts.find { it.id == activeId }?.toDomainAccount() }
            }
                .distinctUntilChanged()
                .catch { e ->
                    Timber.tag(TAG).e(e, "Error in getActiveAccount flow for Microsoft")
                    emit(null)
                }
                .flowOn(ioDispatcher)
        }
        return flowOf(null)
    }

    override fun signIn(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> = flow {
        Timber.tag(TAG).i("signIn called for provider: $providerType, loginHint: $loginHint")
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            Timber.tag(TAG).w("signIn: Unsupported provider type: $providerType")
            emit(
                GenericAuthResult.Error(
                    ErrorDetails(
                        message = "Unsupported provider: $providerType",
                        code = "UNSUPPORTED_PROVIDER"
                    )
                )
            )
            return@flow
        }

        emit(GenericAuthResult.Loading)

        try {
            val scopes = MicrosoftAuthManager.MICROSOFT_SCOPES
            microsoftAuthManager.signInInteractive(activity, scopes)
                .map { resultWrapper ->
                    Timber.tag(TAG).d("signIn: AuthManager emitted: $resultWrapper")
                    when (resultWrapper) {
                        is AuthenticationResultWrapper.Success -> {
                            val domainAccount =
                                resultWrapper.managedAccount.iAccount.toDomainAccount()
                            GenericAuthResult.Success(domainAccount)
                        }

                        is AuthenticationResultWrapper.Error -> {
                            val errorDetails =
                                mapMsalExceptionToErrorDetails(resultWrapper.exception)
                            GenericAuthResult.Error(errorDetails)
                        }

                        is AuthenticationResultWrapper.Cancelled -> {
                            Timber.tag(TAG).i("signIn: User cancelled sign-in flow.")
                            GenericAuthResult.Error(
                                ErrorDetails(
                                    message = "User cancelled sign-in.",
                                    code = "USER_CANCELLED"
                                )
                            )
                        }
                    }
                }
                .collect { emit(it) }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "signIn: Exception during Microsoft sign-in process")
            emit(GenericAuthResult.Error(mapMsalExceptionToErrorDetails(e)))
        }
    }.flowOn(ioDispatcher)

    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?
    ) {
        Timber.tag(TAG)
            .d("handleAuthenticationResult called for provider: $providerType, resultCode: $resultCode")
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            Timber.tag(TAG).w("handleAuthenticationResult: Unsupported provider: $providerType")
            return
        }
        microsoftAuthManager.processPotentialAuthenticationResult(resultCode, data)
    }

    override fun signOut(account: Account): Flow<GenericSignOutResult> = flow {
        Timber.tag(TAG)
            .i("signOut called for account: ${account.username} (ID: ${account.id.take(5)}...)")
        if (!account.providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            Timber.tag(TAG).w("signOut: Unsupported provider type: ${account.providerType}")
            emit(
                GenericSignOutResult.Error(
                    ErrorDetails(
                        message = "Unsupported provider: ${account.providerType}",
                        code = "UNSUPPORTED_PROVIDER"
                    )
                )
            )
            return@flow
        }

        try {
            val resultWrapper = microsoftAuthManager.signOut(account.id)
            Timber.tag(TAG).d("signOut: AuthManager returned: $resultWrapper")
            when (resultWrapper) {
                is SignOutResultWrapper.Success -> {
                    emit(GenericSignOutResult.Success)
                }

                is SignOutResultWrapper.Error -> {
                    emit(
                        GenericSignOutResult.Error(
                            ErrorDetails(
                                message = resultWrapper.exception.message ?: "MSAL Sign out error",
                                code = resultWrapper.exception.errorCode
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG)
                .e(e, "signOut: Exception during Microsoft sign-out for ${account.username}")
            emit(GenericSignOutResult.Error(ErrorDetails(message = "Sign out failed: ${e.message}")))
        }
    }.flowOn(ioDispatcher)

    override fun observeActionMessages(): Flow<String?> {
        Timber.tag(TAG).d("observeActionMessages called (MSAL - Not Implemented)")
        return flowOf(null)
    }

    override fun clearActionMessage() {
        Timber.tag(TAG).d("clearActionMessage called (MSAL - Not Implemented)")
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        Timber.tag(TAG)
            .d("markAccountForReauthentication called for $accountId, provider $providerType")
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            Timber.tag(TAG).w("markAccountForReauthentication: Unsupported provider: $providerType")
            return
        }
        Timber.tag(TAG)
            .w("markAccountForReauthentication: MicrosoftAuthManager does not have a public method to mark account for re-auth. This is a TODO.")
    }

    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Timber.tag(TAG).d("syncAccount called for $accountId (MSAL)")
        return try {
            val iAccount = microsoftAuthManager.getMsalAccount(accountId)
            if (iAccount == null) {
                Timber.tag(TAG)
                    .w("syncAccount: MSAL account $accountId not found for token refresh.")
                markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
                return Result.failure(Exception("MSAL account $accountId not found for sync."))
            }
            val scopes = MicrosoftAuthManager.MICROSOFT_SCOPES
            val tokenResult = microsoftAuthManager.acquireTokenSilent(iAccount, scopes)
            when (tokenResult) {
                is AcquireTokenResult.Success -> Result.success(Unit)
                is AcquireTokenResult.UiRequired -> {
                    Timber.tag(TAG).w("syncAccount for $accountId requires UI interaction.")
                    markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
                    Result.failure(
                        MsalUiRequiredException(
                            "UI_REQUIRED_FOR_SYNC",
                            "UI required for sync/token refresh for $accountId"
                        )
                    )
                }

                is AcquireTokenResult.Error -> {
                    Timber.tag(TAG).e(
                        tokenResult.exception,
                        "syncAccount for $accountId failed with MSAL error."
                    )
                    Result.failure(tokenResult.exception)
                }

                else -> {
                    Timber.tag(TAG)
                        .w("syncAccount for $accountId returned unexpected token result: $tokenResult")
                    Result.failure(Exception("Unexpected token result during sync for $accountId"))
                }
            }
        } catch (e: MsalUiRequiredException) {
            Timber.tag(TAG)
                .w(e, "syncAccount for $accountId requires UI interaction (caught exception).")
            markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "syncAccount for $accountId failed with generic exception.")
            Result.failure(e)
        }
    }

    override val overallApplicationAuthState: Flow<OverallApplicationAuthState> =
        microsoftAuthManager.msalAccounts
            .map { iAccounts ->
                if (iAccounts.isEmpty()) {
                    OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
                } else {
                    val anyNeedsReauth = iAccounts.any { account ->
                        false
                    }

                    if (anyNeedsReauth) {
                        if (iAccounts.all { false }) {
                            OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
                        } else {
                            OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
                        }
                    } else {
                        OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
                    }
                }
            }
            .distinctUntilChanged()
            .catch { e ->
                Timber.tag(TAG).e(e, "Error in overallApplicationAuthState flow")
                emit(OverallApplicationAuthState.UNKNOWN)
            }
            .flowOn(ioDispatcher)
            .shareIn(externalScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        scopes: List<String>?
    ): Flow<GenericAuthResult> = flow {
        Timber.tag(TAG)
            .d("getAuthenticationIntentRequest called for provider: $providerType (MSAL)")
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            emit(
                GenericAuthResult.Error(
                    ErrorDetails(
                        message = "Unsupported provider: $providerType",
                        code = "UNSUPPORTED_PROVIDER"
                    )
                )
            )
            return@flow
        }
        Timber.tag(TAG)
            .w("getAuthenticationIntentRequest for MSAL: MSAL's acquireToken handles UI internally. Use signIn().")
        emit(
            GenericAuthResult.Error(
                ErrorDetails(
                    message = "MSAL does not typically provide a separate sign-in intent. Use signIn().",
                    code = "MSAL_INTENT_NOT_APPLICABLE"
                )
            )
        )
    }.flowOn(ioDispatcher)

    private fun mapMsalExceptionToErrorDetails(exception: Throwable?): ErrorDetails {
        if (exception == null) {
            return ErrorDetails("Unknown authentication error.", "UNKNOWN_AUTH_ERROR")
        }
        Timber.tag(TAG).w(exception, "Mapping MSAL exception to ErrorDetails")
        return when (exception) {
            is MsalUiRequiredException -> ErrorDetails(
                message = "Authentication requires user interaction. Please sign in again.",
                code = exception.errorCode ?: "UI_REQUIRED"
            )

            is MsalClientException -> ErrorDetails(
                message = exception.message ?: "A client error occurred during authentication.",
                code = exception.errorCode ?: "CLIENT_ERROR"
            )

            is MsalDeclinedScopeException -> ErrorDetails(
                message = "Some requested permissions were declined. Granted: ${exception.grantedScopes.joinToString()}, Declined: ${exception.declinedScopes.joinToString()}",
                code = "SCOPES_DECLINED"
            )

            else -> ErrorDetails(
                message = exception.message
                    ?: "An unexpected error occurred during authentication.",
                code = "GENERIC_AUTH_FAILURE",
                cause = exception
            )
        }
    }
}

