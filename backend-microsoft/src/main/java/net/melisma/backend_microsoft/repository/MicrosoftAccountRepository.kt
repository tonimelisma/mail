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
            microsoftAuthManager.getAccounts().collect { msalAccounts ->
                Timber.tag(TAG)
                    .d("Received ${msalAccounts.size} IAccounts from MicrosoftAuthManager.")
                val genericAccounts = mutableListOf<Account>()
                for (msalAccount in msalAccounts) {
                    var needsReAuth = false
                    try {
                        val tokenResult = microsoftAuthManager.acquireTokenSilent(
                            msalAccount,
                            MicrosoftAuthManager.MICROSOFT_SCOPES
                        ).firstOrNull()
                        if (tokenResult is AuthenticationResultWrapper.Error && tokenResult.isUiRequired) {
                            needsReAuth = true
                            Timber.tag(TAG)
                                .w("Account ${msalAccount.username} needs re-authentication (MsalUiRequiredException).")
                        } else if (tokenResult is AuthenticationResultWrapper.Error) {
                            Timber.tag(TAG)
                                .w("Account ${msalAccount.username} silent token failed with other error: ${tokenResult.exception.errorCode}")
                        } else if (tokenResult == null) {
                            Timber.tag(TAG)
                                .w("Account ${msalAccount.username} silent token flow emitted null, assuming re-auth might be needed.")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG)
                            .e(e, "Exception during silent token check for ${msalAccount.username}")
                    }

                    genericAccounts.add(
                        Account(
                            id = msalAccount.id ?: UUID.randomUUID().toString(),
                            username = msalAccount.username ?: "Unknown MS User",
                            providerType = Account.PROVIDER_TYPE_MS,
                            needsReauthentication = needsReAuth
                        )
                    )
                }
                _msAccounts.value = genericAccounts
                Timber.tag(TAG)
                    .d("Updated _msAccounts with ${genericAccounts.size} mapped accounts.")

                // Update overall auth state
                updateOverallApplicationAuthState()

                // Check if the currently active MS account is still present
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
        // loginHint is not used by MicrosoftAuthManager.signInInteractive, so it's omitted from the call.
        // If loginHint is critical for MSAL, MicrosoftAuthManager.signInInteractive would need to be updated.
        return microsoftAuthManager.signInInteractive(activity, scopes)
            .map { msalResult ->
                when (msalResult) {
                    is AuthenticationResultWrapper.Success -> {
                        val coreAccount = Account(
                            id = msalResult.account.id ?: UUID.randomUUID().toString(),
                            username = msalResult.account.username ?: "Unknown MS User",
                            providerType = Account.PROVIDER_TYPE_MS,
                            needsReauthentication = false
                        )
                        activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(coreAccount.id)
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
        // activity: Activity - Removed as per build error and interface alignment
    ) {
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) return
        Timber.tag(TAG)
            .i("handleAuthenticationResult called for MS provider. MSAL handles results via its own callbacks/activity results. Current signIn flow should cover this.")
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
            val msalAccount =
                microsoftAuthManager.getAccounts().firstOrNull()?.find { it.id == account.id }
            if (msalAccount == null) {
                Timber.tag(TAG)
                    .e("Could not find IAccount in MSAL for generic Account ID: ${account.id} to sign out.")
                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                }
                _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                updateOverallApplicationAuthState()
                emit(
                    GenericSignOutResult.Error(
                        "MS Account not found for sign out.",
                        GenericAuthErrorType.ACCOUNT_NOT_FOUND
                    )
                )
                return@flow
            }

            microsoftAuthManager.signOut(msalAccount)
                .map { signOutResult ->
                    when (signOutResult) {
                        is SignOutResultWrapper.Success -> {
                            if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                                activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                            }
                            _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                            updateOverallApplicationAuthState()
                            GenericSignOutResult.Success
                        }

                        is SignOutResultWrapper.Error -> {
                            val mappedDetails =
                                microsoftErrorMapper.mapExceptionToErrorDetails(signOutResult.exception)
                            GenericSignOutResult.Error(
                                message = mappedDetails.message,
                                type = mappedDetails.type,
                                providerSpecificErrorCode = mappedDetails.providerSpecificErrorCode
                            )
                        }
                    }
                }.collect { emit(it) }
        }
    }

    override fun observeActionMessages(): Flow<String?> = _accountActionMessage.asSharedFlow()

    override fun clearActionMessage() {
        Timber.tag(TAG).d("clearActionMessage called.")
        _accountActionMessage.tryEmit(null)
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        // This repository is for Microsoft accounts.
        // We could add a check: if (providerType != Account.PROVIDER_TYPE_MS) return
        // However, the DefaultAccountRepository might call this without that specific check,
        // relying on this repo to handle its own type.
        // The existing logic implicitly handles MS type due to how _msAccounts is populated.
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
                // Update overall auth state after marking an account
                updateOverallApplicationAuthState()
                // Optionally, trigger a wider notification or persist this state if MicrosoftTokenPersistenceService supports it.
            } else {
                Timber.tag(TAG).d("Account $accountId was already marked for re-authentication.")
            }
        } else {
            Timber.tag(TAG)
                .w("Account $accountId not found in _msAccounts to mark for re-authentication.")
        }
    }

    private fun updateOverallApplicationAuthState() {
        val accounts = _msAccounts.value
        val newState = when {
            accounts.isEmpty() -> OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
            accounts.all { it.needsReauthentication } -> OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
            accounts.any { it.needsReauthentication } -> OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
            else -> OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
        }
        if (_overallApplicationAuthState.value != newState) {
            Timber.tag(TAG)
                .d("OverallApplicationAuthState changing from ${_overallApplicationAuthState.value} to $newState")
            _overallApplicationAuthState.value = newState
        }
    }
}
