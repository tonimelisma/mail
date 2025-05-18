// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package net.melisma.backend_microsoft.repository

import android.app.Activity
import android.content.Intent
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
import net.melisma.backend_microsoft.auth.AuthenticationResultWrapper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.SignOutResultWrapper
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.GenericAuthErrorType
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
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder
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
            microsoftAuthManager.getAccounts().collect { managedAccounts ->
                Timber.tag(TAG)
                    .d("Received ${managedAccounts.size} ManagedMicrosoftAccounts from MicrosoftAuthManager.")
                val genericAccounts = mutableListOf<Account>()
                for (managedAccount: ManagedMicrosoftAccount in managedAccounts) {
                    var needsReAuth = false
                    try {
                        val tokenResult = microsoftAuthManager.acquireTokenSilent(
                            managedAccount.iAccount,
                            MicrosoftAuthManager.MICROSOFT_SCOPES
                        ).firstOrNull()
                        if (tokenResult is AuthenticationResultWrapper.Error && tokenResult.isUiRequired) {
                            needsReAuth = true
                            Timber.tag(TAG)
                                .w("Account ${managedAccount.iAccount.username} needs re-authentication (MsalUiRequiredException).")
                        } else if (tokenResult is AuthenticationResultWrapper.Error) {
                            Timber.tag(TAG)
                                .w("Account ${managedAccount.iAccount.username} silent token failed with other error: ${tokenResult.exception.errorCode}")
                        } else if (tokenResult == null) {
                            Timber.tag(TAG)
                                .w("Account ${managedAccount.iAccount.username} silent token flow emitted null, assuming re-auth might be needed.")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG)
                            .e(
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
                Timber.tag(TAG)
                    .d("Updated _msAccounts with ${genericAccounts.size} mapped accounts.")

                updateOverallApplicationAuthState()

                val activeMsId = activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue()
                if (activeMsId != null && genericAccounts.none { it.id == activeMsId }) {
                    Timber.tag(TAG)
                        .w("Active MS account $activeMsId no longer found in MSAL accounts. Clearing active holder.")
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                }
            }
        }
    }

    override fun getAccounts(): Flow<List<Account>> = _msAccounts.asStateFlow()

    override fun getActiveAccount(providerType: String): Flow<Account?> {
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) return flowOf(null)

        return activeMicrosoftAccountHolder.activeMicrosoftAccountId.flatMapLatest { activeId ->
            if (activeId == null) {
                flowOf(null)
            } else {
                _msAccounts.map { accounts -> accounts.find { it.id == activeId } }
            }
        }
    }

    override fun signIn(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> {
        if (providerType != Account.PROVIDER_TYPE_MS) {
            return flowOf(
                GenericAuthResult.Error(
                    message = "signIn called for non-Microsoft provider in MicrosoftAccountRepository",
                    type = GenericAuthErrorType.INVALID_REQUEST
                )
            )
        }
        val scopes = MicrosoftAuthManager.MICROSOFT_SCOPES
        return microsoftAuthManager.signInInteractive(activity, scopes)
            .map { msalResult ->
                when (msalResult) {
                    is AuthenticationResultWrapper.Success -> {
                        val managedAccount = msalResult.managedAccount
                        val accountUsername =
                            managedAccount.displayName ?: managedAccount.iAccount.username
                            ?: "Unknown MS User"

                        val coreAccount = Account(
                            id = managedAccount.iAccount.id ?: UUID.randomUUID().toString(),
                            username = accountUsername,
                            providerType = Account.PROVIDER_TYPE_MS,
                            needsReauthentication = false
                        )
                        val currentAccounts =
                            _msAccounts.value.filterNot { it.id == coreAccount.id }.toMutableList()
                        currentAccounts.add(0, coreAccount)
                        _msAccounts.value = currentAccounts
                        updateOverallApplicationAuthState()

                        GenericAuthResult.Success(coreAccount)
                    }

                    is AuthenticationResultWrapper.Error -> {
                        val isUiRequired = msalResult.exception is MsalUiRequiredException
                        val mappedDetails =
                            microsoftErrorMapper.mapExceptionToErrorDetails(msalResult.exception)
                        GenericAuthResult.Error(
                            message = mappedDetails.message,
                            type = mappedDetails.type,
                            providerSpecificErrorCode = mappedDetails.providerSpecificErrorCode,
                            msalRequiresInteractiveSignIn = isUiRequired
                        )
                    }

                    is AuthenticationResultWrapper.Cancelled -> GenericAuthResult.Cancelled
                }
            }
    }

    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?
    ) {
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) return
        Timber.tag(TAG)
            .i("handleAuthenticationResult for MS provider. MSAL handles its own ActivityResults. No action taken by repository.")
    }

    override fun signOut(account: Account): Flow<GenericSignOutResult> {
        if (account.providerType != Account.PROVIDER_TYPE_MS) {
            return flowOf(
                GenericSignOutResult.Error(
                    "Sign out called for non-MS account.",
                    GenericAuthErrorType.INVALID_REQUEST
                )
            )
        }
        Timber.tag(TAG)
            .i("Attempting to sign out Microsoft account: ${account.username} (ID: ${account.id})")

        return flow {
            val managedMsalAccount = microsoftAuthManager.getAccounts().firstOrNull()
                ?.find { it.iAccount.id == account.id }

            if (managedMsalAccount == null) {
                Timber.tag(TAG)
                    .e("Could not find ManagedMicrosoftAccount in MSAL for generic Account ID: ${account.id} to sign out. Attempting to clear local state anyway.")
                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                }
                _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                updateOverallApplicationAuthState()
                emit(
                    GenericSignOutResult.Error(
                        "MS Account not found in MSAL for sign out. Local state cleared.",
                        GenericAuthErrorType.ACCOUNT_NOT_FOUND
                    )
                )
                return@flow
            }

            microsoftAuthManager.signOut(managedMsalAccount.iAccount).collect { signOutResult ->
                when (signOutResult) {
                    is SignOutResultWrapper.Success -> {
                        _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                        updateOverallApplicationAuthState()
                        emit(GenericSignOutResult.Success)
                    }

                    is SignOutResultWrapper.Error -> {
                        val mappedDetails =
                            microsoftErrorMapper.mapExceptionToErrorDetails(signOutResult.exception)
                        emit(
                            GenericSignOutResult.Error(
                                message = mappedDetails.message,
                                type = mappedDetails.type,
                                providerSpecificErrorCode = mappedDetails.providerSpecificErrorCode
                            )
                        )
                    }
                }
            }
        }
    }

    override fun observeActionMessages(): Flow<String?> = _accountActionMessage.asSharedFlow()

    override fun clearActionMessage() {
        Timber.tag(TAG).d("clearActionMessage called.")
        _accountActionMessage.tryEmit(null)
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        Timber.tag(TAG)
            .i("Marking MS account $accountId (provider: $providerType) for re-authentication.")
        val currentAccounts = _msAccounts.value.toMutableList()
        val accountIndex =
            currentAccounts.indexOfFirst { it.id == accountId && it.providerType == providerType }

        if (accountIndex != -1) {
            val accountToUpdate = currentAccounts[accountIndex]
            if (!accountToUpdate.needsReauthentication) {
                currentAccounts[accountIndex] = accountToUpdate.copy(needsReauthentication = true)
                _msAccounts.value = currentAccounts
                Timber.tag(TAG).d("Account $accountId marked for re-authentication in _msAccounts.")
                updateOverallApplicationAuthState()
            } else {
                Timber.tag(TAG).d("Account $accountId was already marked for re-authentication.")
            }
        } else {
            Timber.tag(TAG)
                .w("Account $accountId not found in _msAccounts to mark for re-authentication.")
        }
    }

    private fun updateOverallApplicationAuthState() {
        val isAuthenticated = _msAccounts.value.any { !it.needsReauthentication }
        _overallApplicationAuthState.value = if (isAuthenticated) {
            OverallApplicationAuthState.AUTHENTICATED
        } else if (_msAccounts.value.isNotEmpty()) {
            OverallApplicationAuthState.NO_AUTHENTICATED_ACCOUNT_AVAILABLE
        } else {
            OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
        }
        Timber.tag(TAG).d("Overall auth state updated to: ${_overallApplicationAuthState.value}")
    }
}
