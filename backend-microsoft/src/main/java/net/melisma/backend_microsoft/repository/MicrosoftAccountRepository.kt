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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.melisma.backend_microsoft.auth.AcquireTokenResult
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
import net.melisma.backend_microsoft.auth.AuthenticationResultWrapper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.SignOutResultWrapper
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.backend_microsoft.model.ManagedMicrosoftAccount
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.ErrorDetails
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val externalScope: CoroutineScope,
    private val microsoftErrorMapper: MicrosoftErrorMapper,
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : AccountRepository {

    private val TAG = "MicrosoftAccountRepo"

    private val _msAccounts = MutableStateFlow<List<Account>>(emptyList())

    private val _overallApplicationAuthState =
        MutableStateFlow<OverallApplicationAuthState>(OverallApplicationAuthState.UNKNOWN)
    override val overallApplicationAuthState: StateFlow<OverallApplicationAuthState> =
        _overallApplicationAuthState.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        Timber.tag(TAG).i("MicrosoftAccountRepository Initializing.")
        observeMicrosoftAuthManagerChanges()
    }

    private fun observeMicrosoftAuthManagerChanges() {
        externalScope.launch {
            microsoftAuthManager.isMsalInitialized.collectLatest { isInitialized ->
                if (isInitialized) {
                    Timber.tag(TAG).d("MSAL is initialized. Fetching and processing MS accounts.")
                    try {
                        val managedAccountsList: List<ManagedMicrosoftAccount> =
                            microsoftAuthManager.getManagedAccountsFromMSAL()

                        Timber.tag(TAG)
                            .d("Received ${managedAccountsList.size} ManagedMicrosoftAccounts from MicrosoftAuthManager.")
                        val genericAccounts = mutableListOf<Account>()

                        for (managedAccount: ManagedMicrosoftAccount in managedAccountsList) {
                            var needsReAuth = false
                            try {
                                val iAccountFromManaged = managedAccount.iAccount
                                val tokenResult: AcquireTokenResult =
                                    microsoftAuthManager.acquireTokenSilent(
                                        iAccountFromManaged,
                                        MicrosoftAuthManager.MICROSOFT_SCOPES
                                    )
                                when (tokenResult) {
                                    is AcquireTokenResult.UiRequired -> needsReAuth = true
                                    is AcquireTokenResult.Error -> {
                                        if (tokenResult.exception is MsalUiRequiredException || tokenResult.exception is MsalDeclinedScopeException) {
                                            needsReAuth = true
                                        }
                                    }

                                    else -> { /* No action for Success, Cancelled, etc. in this context */
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(
                                    e,
                                    "Exception during silent token check for ${managedAccount.iAccount.username}"
                                )
                                needsReAuth = true
                            }
                            val accountUsername =
                                managedAccount.displayName ?: managedAccount.iAccount.username
                                ?: "Unknown MS User"
                            genericAccounts.add(
                                Account(
                                    id = managedAccount.iAccount.id ?: UUID.randomUUID().toString(),
                                    username = accountUsername,
                                    providerType = Account.PROVIDER_TYPE_MS,
                                    needsReauthentication = needsReAuth
                                )
                            )
                        }
                        _msAccounts.value = genericAccounts
                        updateOverallApplicationAuthState(true) // Pass flag to check active account

                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error fetching/processing MSAL accounts.")
                        _msAccounts.value = emptyList()
                        updateOverallApplicationAuthState()
                    }
                } else {
                    Timber.tag(TAG).d("MSAL is not initialized yet. Clearing MS accounts.")
                    _msAccounts.value = emptyList()
                    updateOverallApplicationAuthState()
                }
            }
        }
    }

    override fun getAccounts(): Flow<List<Account>> = _msAccounts.asStateFlow()

    override fun getAccountById(accountId: String): Flow<Account?> {
        return _msAccounts.map { accounts -> accounts.find { it.id == accountId } }
    }

    override fun getActiveAccount(providerType: String): Flow<Account?> {
        if (providerType != Account.PROVIDER_TYPE_MS) return flowOf(null)
        return activeMicrosoftAccountHolder.activeMicrosoftAccountId.flatMapLatest { activeId ->
            if (activeId == null) flowOf(null)
            else getAccountById(activeId)
        }
    }

    override fun signIn(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> = flow {
        if (providerType != Account.PROVIDER_TYPE_MS) {
            emit(
                GenericAuthResult.Error(
                    ErrorDetails(
                        "signIn called for non-Microsoft provider",
                        "invalid_provider"
                    )
                )
            )
            return@flow
        }
        emit(GenericAuthResult.Loading)
        try {
            // signInInteractive expects scopes. loginHint is not directly used by this specific MSAL call structure.
            // We'll use the standard MICROSOFT_SCOPES.
            microsoftAuthManager.signInInteractive(activity, MicrosoftAuthManager.MICROSOFT_SCOPES)
                .collect { authResultWrapper ->
                    when (authResultWrapper) {
                    is AuthenticationResultWrapper.Success -> {
                        val msalResult = authResultWrapper.authenticationResult
                        val iAccount =
                            msalResult.account // Directly use the account from the result
                        Timber.tag(TAG)
                            .i("MSAL Sign-in successful. Account: ${iAccount.username}, ID: ${iAccount.id}")
                        val account = Account(
                            id = iAccount.id ?: UUID.randomUUID().toString(),
                            username = iAccount.claims?.get("name") as? String ?: iAccount.username
                            ?: "Unknown MS User",
                            providerType = Account.PROVIDER_TYPE_MS,
                            needsReauthentication = false
                        )
                        _msAccounts.value =
                            _msAccounts.value.filterNot { it.id == account.id } + account
                        activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(account.id)
                        updateOverallApplicationAuthState(checkActiveAccount = true)
                        emit(GenericAuthResult.Success(account))
                    }
                    is AuthenticationResultWrapper.Error -> {
                        Timber.tag(TAG)
                            .w(authResultWrapper.exception, "MSAL Sign-in failed with exception.")
                        val errorDetails =
                            microsoftErrorMapper.mapExceptionToErrorDetails(authResultWrapper.exception)

                        // If MsalUiRequiredException occurs during an interactive sign-in, 
                        // it means the interactive process itself needs UI or has failed in a way that requires it.
                        // For MSAL v2.2.3, MsalUiRequiredException does not directly provide the IAccount.
                        // So, we don't attempt to mark a specific account from this exception here.
                        // The re-authentication marking for existing accounts is handled during silent token acquisition checks.
                        val msalAuthException = authResultWrapper.exception
                        if (msalAuthException is MsalUiRequiredException) {
                            Timber.tag(TAG)
                                .w("Interactive sign-in resulted in MsalUiRequiredException: ${msalAuthException.errorCode}")
                            // No account to mark directly from this exception in MSAL v2.2.3
                        }
                        emit(GenericAuthResult.Error(errorDetails))
                    }
                    is AuthenticationResultWrapper.Cancelled -> {
                        Timber.tag(TAG).i("MSAL Sign-in cancelled by user.")
                        emit(
                            GenericAuthResult.Error(
                                microsoftErrorMapper.mapExceptionToErrorDetails(
                                    MsalClientException(
                                        "msal_user_cancelled",
                                        "Sign-in was cancelled by the user."
                                    )
                                )
                            )
                        )
                    }
                    }
                }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception during MSAL sign-in attempt.")
            emit(GenericAuthResult.Error(microsoftErrorMapper.mapExceptionToErrorDetails(e)))
        }
    }.flowOn(ioDispatcher)

    override fun signOut(account: Account): Flow<GenericSignOutResult> = flow {
        if (account.providerType != Account.PROVIDER_TYPE_MS) {
            emit(
                GenericSignOutResult.Error(
                    ErrorDetails(
                        "signOut called for non-MS account",
                        "invalid_provider"
                    )
                )
            )
            return@flow
        }
        emit(GenericSignOutResult.Loading)
        try {
            val iAccount = microsoftAuthManager.getAccount(account.id) // Fetch IAccount
            if (iAccount == null) {
                Timber.tag(TAG)
                    .w("MSAL IAccount not found for ID ${account.id} during sign-out. Clearing local state only.")
                _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                }
                updateOverallApplicationAuthState(checkActiveAccount = true)
                emit(GenericSignOutResult.Success)
                return@flow
            }

            microsoftAuthManager.signOut(iAccount).collect { signOutResultWrapper ->
                when (signOutResultWrapper) {
                    is SignOutResultWrapper.Success -> {
                        Timber.tag(TAG).i("MSAL Sign-out successful for account ID: ${account.id}")
                        _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                        if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                        }
                        updateOverallApplicationAuthState(checkActiveAccount = true)
                        emit(GenericSignOutResult.Success)
                    }

                    is SignOutResultWrapper.Error -> {
                        Timber.tag(TAG).w(
                            signOutResultWrapper.exception,
                            "MSAL Sign-out failed for account ID: ${account.id}"
                        )
                        emit(
                            GenericSignOutResult.Error(
                                microsoftErrorMapper.mapExceptionToErrorDetails(
                                    signOutResultWrapper.exception
                                )
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception during MSAL sign-out for account ID ${account.id}")
            emit(GenericSignOutResult.Error(microsoftErrorMapper.mapExceptionToErrorDetails(e)))
        }
    }.flowOn(ioDispatcher)

    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?
    ) {
        if (providerType == Account.PROVIDER_TYPE_MS) {
            Timber.tag(TAG)
                .d("handleAuthenticationResult called for MS. MSAL handles this internally.")
        } else {
            Timber.tag(TAG)
                .w("handleAuthenticationResult called for unexpected provider: $providerType")
        }
    }

    override fun observeActionMessages(): Flow<String?> = _accountActionMessage.asSharedFlow()

    override fun clearActionMessage() {
        _accountActionMessage.tryEmit(null)
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        if (providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            _msAccounts.value = _msAccounts.value.map { acc ->
                if (acc.id == accountId) acc.copy(needsReauthentication = true) else acc
            }
            Timber.tag(TAG).i("Marked MS account $accountId for re-authentication.")
            updateOverallApplicationAuthState()
        } else {
            Timber.tag(TAG)
                .w("markAccountForReauthentication called for non-MS account: $accountId, provider: $providerType")
        }
    }

    private fun updateOverallApplicationAuthState(checkActiveAccount: Boolean = false) {
        val currentMsAccounts = _msAccounts.value
        val newState = when {
            currentMsAccounts.isEmpty() -> OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
            currentMsAccounts.all { it.needsReauthentication } -> OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
            // Check if activeMicrosoftAccountHolder.activeMicrosoftAccountId is null AND there's at least one account that doesn't need reauth
            // This can happen if an account was signed out, and we need to determine if we can auto-select another.
            currentMsAccounts.any { it.needsReauthentication } -> OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
            else -> OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
        }
        if (_overallApplicationAuthState.value != newState) {
            _overallApplicationAuthState.value = newState
        }
        Timber.tag(TAG).d("Updated OverallApplicationAuthState to: $newState")

        // Logic to manage active account
        val activeMsId = activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue()
        if (checkActiveAccount) { // Only run this more complex logic if explicitly asked
            if (activeMsId == null && currentMsAccounts.isNotEmpty()) {
                // If no active account, try to set one if possible
                currentMsAccounts.firstOrNull { !it.needsReauthentication }
                    ?.let { newActiveAccount ->
                        Timber.tag(TAG)
                            .i("Setting active MS account to ${newActiveAccount.id} as no active account was set and one is available.")
                        activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(newActiveAccount.id)
                    }
            } else if (activeMsId != null) {
                // If there is an active account, ensure it's still valid (exists and not needing re-auth)
                val activeAccountStillValid =
                    currentMsAccounts.any { it.id == activeMsId && !it.needsReauthentication }
                if (!activeAccountStillValid) {
                    Timber.tag(TAG)
                        .i("Active MS account $activeMsId is no longer valid (removed or needs re-auth). Clearing active account.")
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                    // After clearing, try to set a new one if possible
                    currentMsAccounts.firstOrNull { !it.needsReauthentication }
                        ?.let { newActiveAccount ->
                            Timber.tag(TAG)
                                .i("Setting new active MS account to ${newActiveAccount.id} after previous one became invalid.")
                            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(
                                newActiveAccount.id
                            )
                        }
                }
            }
        } else { // Simpler check if not full checkActiveAccount
            if (activeMsId != null && currentMsAccounts.none { it.id == activeMsId }) {
                Timber.tag(TAG)
                    .i("Active MS account $activeMsId was removed. Clearing active account.")
                activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
            }
        }
    }

    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Timber.tag(TAG)
            .d("syncAccount called for MS account $accountId. Relies on MSAL observation.")
        return Result.success(Unit)
    }

    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        scopes: List<String>?
    ): Flow<GenericAuthResult> {
        if (providerType != Account.PROVIDER_TYPE_MS) {
            return flowOf(
                GenericAuthResult.Error(
                    ErrorDetails(
                        "getAuthenticationIntentRequest for non-MS provider",
                        "invalid_provider"
                    )
                )
            )
        }
        Timber.tag(TAG)
            .i("getAuthenticationIntentRequest called for MS. MSAL manages its own UI. Use signIn().")
        return flowOf(
            GenericAuthResult.Error(
                ErrorDetails(
                    "MSAL manages its own UI. Use signIn() instead.",
                    "msal_ui_managed_internally"
                )
            )
        )
    }
}

