// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt
package net.melisma.backend_microsoft.repository

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
import net.melisma.backend_microsoft.auth.AuthenticationResultWrapper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.SignOutResultWrapper
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.AccountRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val microsoftErrorMapper: MicrosoftErrorMapper,
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder,
    private val microsoftTokenPersistenceService: MicrosoftTokenPersistenceService
) : AccountRepository {

    private val TAG = "MicrosoftAccountRepo"

    private val _msAccounts = MutableStateFlow<List<Account>>(emptyList())

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
                            id = msalAccount.id ?: java.util.UUID.randomUUID().toString(),
                            username = msalAccount.username ?: "Unknown MS User",
                            providerType = Account.PROVIDER_TYPE_MS,
                            needsReauthentication = needsReAuth
                        )
                    )
                }
                _msAccounts.value = genericAccounts
                Timber.tag(TAG)
                    .d("Updated _msAccounts with ${genericAccounts.size} mapped accounts.")

                // Check if the currently active MS account is still present
                val activeMsId = activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue()
                if (activeMsId != null && genericAccounts.none { it.id == activeMsId }) {
                    Timber.tag(TAG)
                        .w("Active MS account $activeMsId no longer found in MSAL accounts. Clearing active holder.")
                    activeMicrosoftAccountHolder.clearActiveMicrosoftAccountId()
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

    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity
    ): Flow<Intent?> {
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
            Timber.tag(TAG)
                .w("getAuthenticationIntentRequest called for non-MS provider: $providerType")
            return flowOf(null)
        }

        Timber.tag(TAG).i("MSAL sign-in requested. MSAL handles its own UI.")
        externalScope.launch {
            microsoftAuthManager.signInInteractive(activity, MicrosoftAuthManager.MICROSOFT_SCOPES)
                .collect { result ->
                    when (result) {
                        is AuthenticationResultWrapper.Success -> {
                            Timber.tag(TAG).i("MSAL Sign-in success: ${result.account.username}")
                            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(result.account.id)
                            _accountActionMessage.tryEmit("Microsoft account signed in: ${result.account.username}")
                        }

                        is AuthenticationResultWrapper.Error -> {
                            Timber.tag(TAG).e(result.exception, "MSAL Sign-in error")
                            _accountActionMessage.tryEmit(
                                microsoftErrorMapper.mapAuthExceptionToUserMessage(
                                    result.exception
                                )
                            )
                        }

                        is AuthenticationResultWrapper.Cancelled -> {
                            Timber.tag(TAG).i("MSAL Sign-in cancelled by user.")
                            _accountActionMessage.tryEmit("Microsoft sign-in cancelled.")
                        }
                    }
                }
        }
        return flowOf(null)
    }

    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?,
        activity: Activity
    ) {
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) return
        Timber.tag(TAG)
            .i("handleAuthenticationResult called for MS provider. MSAL handles results via callbacks. No action taken here.")
    }

    override suspend fun signOut(account: Account) {
        if (account.providerType != Account.PROVIDER_TYPE_MS) {
            _accountActionMessage.tryEmit("Sign out called for non-MS account in MS repo.")
            return
        }
        Timber.tag(TAG)
            .i("Attempting to sign out Microsoft account: ${account.username} (ID: ${account.id})")

        val msalAccountToSignOut =
            microsoftAuthManager.getAccounts().firstOrNull()?.find { it.id == account.id }

        if (msalAccountToSignOut == null) {
            Timber.tag(TAG)
                .e("Could not find IAccount in MSAL for generic Account ID: ${account.id} to sign out.")
            _accountActionMessage.tryEmit("Failed to sign out ${account.username}: Account not found with MSAL.")
            if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                activeMicrosoftAccountHolder.clearActiveMicrosoftAccountId()
                Timber.tag(TAG)
                    .d("Cleared active MS account holder as the account to sign out (${account.id}) was active and not found in MSAL.")
            }
            return
        }

        microsoftAuthManager.signOut(msalAccountToSignOut)
            .collect { result ->
                when (result) {
                    is SignOutResultWrapper.Success -> {
                        Timber.tag(TAG)
                            .i("Microsoft account signed out successfully from MSAL: ${msalAccountToSignOut.username}")
                        // Clear from AccountManager after successful MSAL removal
                        val persistenceCleared =
                            microsoftTokenPersistenceService.deleteAccount(msalAccountToSignOut.id)
                        if (persistenceCleared) {
                            Timber.tag(TAG)
                                .i("Microsoft account data cleared from persistence for ${msalAccountToSignOut.username}")
                        } else {
                            Timber.tag(TAG)
                                .w("Failed to clear Microsoft account data from persistence for ${msalAccountToSignOut.username}")
                        }

                        if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == msalAccountToSignOut.id) {
                            activeMicrosoftAccountHolder.clearActiveMicrosoftAccountId()
                        }
                        _accountActionMessage.tryEmit("Microsoft account signed out: ${msalAccountToSignOut.username}")
                    }

                    is SignOutResultWrapper.Error -> {
                        Timber.tag(TAG).e(
                            result.exception,
                            "Error signing out Microsoft account from MSAL: ${msalAccountToSignOut.username}"
                        )
                        _accountActionMessage.tryEmit(
                            microsoftErrorMapper.mapAuthExceptionToUserMessage(
                                result.exception
                            )
                        )
                    }
                }
            }
    }

    override fun observeActionMessages(): Flow<String?> = _accountActionMessage.asSharedFlow()

    override fun clearActionMessage() {
        Timber.tag(TAG).d("clearActionMessage called.")
        _accountActionMessage.tryEmit(null)
    }

    suspend fun markAccountForReauthentication(accountId: String) {
        Timber.tag(TAG).i("Marking MS account $accountId for re-authentication.")
        val currentAccounts = _msAccounts.value.toMutableList()
        val accountIndex =
            currentAccounts.indexOfFirst { it.id == accountId && it.providerType == Account.PROVIDER_TYPE_MS }

        if (accountIndex != -1) {
            val accountToUpdate = currentAccounts[accountIndex]
            if (!accountToUpdate.needsReauthentication) {
                currentAccounts[accountIndex] = accountToUpdate.copy(needsReauthentication = true)
                _msAccounts.value = currentAccounts
                Timber.tag(TAG).d("Account $accountId marked for re-authentication in _msAccounts.")
                // Optionally, trigger a wider notification or persist this state if MicrosoftTokenPersistenceService supports it.
                // For now, just updating the in-memory state that feeds getAccounts()
            } else {
                Timber.tag(TAG).d("Account $accountId was already marked for re-authentication.")
            }
        } else {
            Timber.tag(TAG)
                .w("Account $accountId not found in _msAccounts to mark for re-authentication.")
        }
    }
}
