// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package net.melisma.backend_microsoft.repository

import android.app.Activity
import android.content.Intent
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
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
import net.melisma.core_db.dao.AccountDao
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val activeMicrosoftAccountHolder: net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder,
    private val accountDao: AccountDao
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
                            // Ensure needsReauthentication is false on successful sign-in for this account
                            try {
                                accountDao.getAccountById(domainAccount.id).firstOrNull()
                                    ?.let { entity ->
                                        if (entity.needsReauthentication) {
                                            accountDao.insertOrUpdateAccount(
                                                entity.copy(
                                                    needsReauthentication = false
                                                )
                                            )
                                            Timber.tag(TAG)
                                                .d("signIn: Cleared needsReauthentication for account ${domainAccount.id}")
                                        }
                                    }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(
                                    e,
                                    "signIn: DAO error clearing needsReauthentication for ${domainAccount.id}"
                                )
                            }
                            GenericAuthResult.Success(domainAccount)
                        }

                        is AuthenticationResultWrapper.Error -> {
                            val errorDetails =
                                mapMsalExceptionToErrorDetails(resultWrapper.exception)
                            // If UI is required, try to extract account ID and mark for re-authentication
                            if (resultWrapper.exception is MsalUiRequiredException) {
                                // Attempt to find the account involved, if possible.
                                // This is tricky as MsalUiRequiredException might not directly give account ID
                                // We might need to infer it if a loginHint was used or if there's a single MS account
                                // For now, we can't reliably get the account ID here.
                                // The markAccountForReauthentication will be more reliably called during syncAccount or silent refresh failures.
                                Timber.tag(TAG)
                                    .w("signIn: MsalUiRequiredException occurred. User needs to re-authenticate. Error: ${resultWrapper.exception.message}")
                            }
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

        val accountEntity = accountDao.getAccountById(accountId).flowOn(ioDispatcher).firstOrNull()
        if (accountEntity != null) {
            if (!accountEntity.needsReauthentication) {
                val updatedEntity = accountEntity.copy(needsReauthentication = true)
                try {
                    accountDao.insertOrUpdateAccount(updatedEntity)
                    Timber.tag(TAG)
                        .i("Account $accountId successfully marked for re-authentication in DB by MicrosoftAccountRepository.")
                } catch (e: Exception) {
                    Timber.tag(TAG)
                        .e(
                            e,
                            "DAO error in MicrosoftAccountRepository marking account $accountId for re-authentication."
                        )
                }
            } else {
                Timber.tag(TAG)
                    .i("Account $accountId was already marked for re-authentication.")
            }
        } else {
            Timber.tag(TAG)
                .w("Account $accountId not found in DB. Cannot mark for re-authentication by MicrosoftAccountRepository.")
        }
    }

    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Timber.tag(TAG).d("syncAccount called for $accountId (MSAL)")
        val accountEntity = accountDao.getAccountById(accountId).flowOn(ioDispatcher).firstOrNull()
        if (accountEntity == null) {
            Timber.tag(TAG).w("syncAccount: Account $accountId not found in DB.")
            return Result.failure(Exception("Account $accountId not found"))
        }

        if (accountEntity.providerType != Account.PROVIDER_TYPE_MS) {
            Timber.tag(TAG).w("syncAccount: Account $accountId is not a Microsoft account.")
            return Result.failure(Exception("Account $accountId is not a Microsoft account"))
        }

        val iAccount = try {
            microsoftAuthManager.msalAccounts.value.find { it.id == accountId }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching IAccount $accountId from MicrosoftAuthManager")
            null
        }

        if (iAccount == null) {
            Timber.tag(TAG)
                .w("syncAccount: IAccount $accountId not found via MicrosoftAuthManager. Marking for re-authentication.")
            markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
            return Result.failure(
                MsalUiRequiredException(
                    MsalUiRequiredException.NO_ACCOUNT_FOUND,
                    "MSAL account $accountId not found in MSAL cache"
                )
            )
        }

        return try {
            Timber.tag(TAG).d("syncAccount: Attempting silent token acquisition for $accountId")
            val acquireTokenResult = microsoftAuthManager.acquireTokenSilent(
                scopes = MicrosoftAuthManager.MICROSOFT_SCOPES,
                account = iAccount
            )

            when (acquireTokenResult) {
                is AcquireTokenResult.Success -> {
                    Timber.tag(TAG)
                        .i("syncAccount: Silent token acquisition successful for $accountId.")
                    if (accountEntity.needsReauthentication) {
                        try {
                            accountDao.insertOrUpdateAccount(
                                accountEntity.copy(
                                    needsReauthentication = false
                                )
                            )
                            Timber.tag(TAG)
                                .i("syncAccount: Cleared needsReauthentication flag for $accountId in DB.")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(
                                e,
                                "syncAccount: DAO error clearing needsReauthentication flag for $accountId."
                            )
                        }
                    }
                    Result.success(Unit)
                }
                is AcquireTokenResult.UiRequired -> {
                    Timber.tag(TAG)
                        .w("syncAccount: UI required for token acquisition for $accountId. Marking for re-authentication.")
                    markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
                    Result.failure(
                        MsalUiRequiredException(
                            MsalUiRequiredException.NO_TOKENS_FOUND,
                            "UI Required for silent token refresh for $accountId"
                        )
                    )
                }
                is AcquireTokenResult.Error -> {
                    Timber.tag(TAG).e(
                        acquireTokenResult.exception,
                        "syncAccount: Error acquiring token silently for $accountId. Code: ${acquireTokenResult.exception.errorCode}"
                    )
                    if (acquireTokenResult.exception is MsalUiRequiredException) {
                        markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
                    } else {
                        markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
                        Timber.tag(TAG)
                            .w("syncAccount: MsalException (${acquireTokenResult.exception.errorCode}) occurred for $accountId. Marking for re-authentication.")
                    }
                    Result.failure(acquireTokenResult.exception)
                }

                is AcquireTokenResult.Cancelled -> {
                    Timber.tag(TAG)
                        .w("syncAccount: Silent token acquisition cancelled for $accountId. This is unexpected.")
                    Result.failure(
                        MsalClientException(
                            MsalClientException.UNKNOWN_ERROR,
                            "Silent token acquisition cancelled for $accountId"
                        )
                    )
                }

                is AcquireTokenResult.NotInitialized -> {
                    Timber.tag(TAG)
                        .w("syncAccount: MSAL not initialized when trying to sync $accountId.")
                    Result.failure(
                        MsalClientException(
                            "sdk_not_initialized",
                            "MSAL not initialized for $accountId"
                        )
                    )
                }

                is AcquireTokenResult.NoAccountProvided -> {
                    Timber.tag(TAG)
                        .w("syncAccount: No account provided for silent token acquisition for $accountId. This is unexpected.")
                    Result.failure(
                        MsalUiRequiredException(
                            MsalUiRequiredException.NO_ACCOUNT_FOUND,
                            "No account object provided for silent token for $accountId"
                        )
                    )
                }
            }
        } catch (e: MsalUiRequiredException) {
            Timber.tag(TAG).w(
                e,
                "syncAccount: MsalUiRequiredException caught directly for $accountId. Marking for re-authentication."
            )
            markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
            Result.failure(e)
        } catch (e: MsalException) {
            Timber.tag(TAG).e(
                e,
                "syncAccount: MsalException during silent token acquisition for $accountId. Error code: ${e.errorCode}"
            )
            markAccountForReauthentication(accountId, Account.PROVIDER_TYPE_MS)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e,
                "syncAccount: Generic exception during silent token acquisition for $accountId."
            )
            Result.failure(e)
        }
    }

    override val overallApplicationAuthState: Flow<OverallApplicationAuthState> =
        combine(
            microsoftAuthManager.msalAccounts,
            accountDao.getAllAccounts()
        ) { msalAccounts, dbEntities ->
            if (msalAccounts.isEmpty()) {
                // If MSAL knows of no accounts, but DB might (e.g. after app restart, before MSAL fully loads),
                // check if any of those DB accounts are MS type.
                val dbMicrosoftAccounts =
                    dbEntities.filter { it.providerType == Account.PROVIDER_TYPE_MS }
                if (dbMicrosoftAccounts.isEmpty()) {
                    OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED // Or specific like NO_MS_ACCOUNTS_CONFIGURED
                } else {
                    // We have MS accounts in DB, check their reauth state
                    if (dbMicrosoftAccounts.all { it.needsReauthentication }) {
                        OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
                    } else if (dbMicrosoftAccounts.any { it.needsReauthentication }) {
                        OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
                    } else {
                        OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
                    }
                }
            } else {
                // MSAL has accounts, so we primarily care about their state as reflected in the DB
                val msalAccountIds = msalAccounts.map { it.id }.toSet()
                val relevantDbEntities =
                    dbEntities.filter { it.id in msalAccountIds && it.providerType == Account.PROVIDER_TYPE_MS }

                if (relevantDbEntities.isEmpty() && msalAccountIds.isNotEmpty()) {
                    // MSAL has accounts but none are in our DB yet, or not marked as MS type.
                    // This state is a bit ambiguous. Could be new accounts not yet fully processed.
                    // Or, if we consider only DB as SSoT for reauth flag, and they are not there, 
                    // it might be that they are fresh and don't need reauth. 
                    // For simplicity, if MSAL has them but DB doesn't reflect reauth state, assume auth okay for now.
                    // This path should ideally not be hit if DB is SSoT and kept in sync.
                    Timber.tag(TAG)
                        .w("overallApplicationAuthState: MSAL accounts present but not found or not MS type in DB for re-auth check.")
                    OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
                } else if (relevantDbEntities.isEmpty() && msalAccountIds.isEmpty()) {
                    // This case is covered by the outer if (msalAccounts.isEmpty())
                    OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
                } else {
                    if (relevantDbEntities.all { it.needsReauthentication }) {
                        OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
                    } else if (relevantDbEntities.any { it.needsReauthentication }) {
                        OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
                    } else {
                        OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
                    }
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

