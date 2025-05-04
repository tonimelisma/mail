package net.melisma.backend_microsoft.repository

import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.melisma.backend_microsoft.errors.ErrorMapper
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.repository.AccountRepository
import net.melisma.feature_auth.AddAccountResult
import net.melisma.feature_auth.AuthStateListener
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.feature_auth.RemoveAccountResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Microsoft implementation of the [AccountRepository] interface.
 * Manages Microsoft user accounts and authentication state using [MicrosoftAuthManager].
 * Listens to state changes from the auth manager and translates them into generic
 * [Account] models and [AuthState] exposed via Kotlin Flows.
 */
@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    // Use the qualifier imported from core-data
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapper
) : AccountRepository, AuthStateListener {

    private val TAG = "MicrosoftAccountRepo"

    // --- State Flows reflecting Manager State ---
    private val _authState = MutableStateFlow(determineInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _accounts = MutableStateFlow(mapToGenericAccounts(microsoftAuthManager.accounts))
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    // --- State Flows for Repository Actions ---
    private val _isLoadingAccountAction = MutableStateFlow(false)
    override val isLoadingAccountAction: StateFlow<Boolean> = _isLoadingAccountAction.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    // --- Initialization ---
    init {
        Log.d(TAG, "Initializing and registering as AuthStateListener")
        microsoftAuthManager.setAuthStateListener(this)
        syncStateFromManager()
    }

    // --- AuthStateListener Implementation ---
    override fun onAuthStateChanged(
        isInitialized: Boolean,
        msalAccounts: List<IAccount>,
        error: MsalException?
    ) {
        Log.d(
            TAG,
            "Listener notified: init=$isInitialized, count=${msalAccounts.size}, err=${error != null}"
        )
        _authState.value = determineAuthState(isInitialized, error)
        _accounts.value = mapToGenericAccounts(msalAccounts)
        _isLoadingAccountAction.value = false
    }

    // --- AccountRepository Interface Implementation ---
    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        if (authState.value !is AuthState.Initialized) {
            Log.w(TAG, "addAccount called but auth state is not Initialized: ${authState.value}")
            tryEmitMessage("Authentication system not ready.")
            return
        }
        _isLoadingAccountAction.value = true
        microsoftAuthManager.addAccount(activity, scopes) { result ->
            val message = when (result) {
                is AddAccountResult.Success -> "Account added: ${result.account.username}"
                is AddAccountResult.Error -> "Error adding account: ${
                    errorMapper.mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"
                is AddAccountResult.Cancelled -> "Account addition cancelled."
                is AddAccountResult.NotInitialized -> "Authentication system not ready."
            }
            tryEmitMessage(message)
        }
    }

    override suspend fun removeAccount(account: Account) {
        if (authState.value !is AuthState.Initialized) {
            Log.w(TAG, "removeAccount called but auth state is not Initialized: ${authState.value}")
            tryEmitMessage("Authentication system not ready.")
            return
        }
        val accountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }

        if (accountToRemove == null) {
            Log.e(
                TAG,
                "Account to remove (ID: ${account.id}) not found in MicrosoftAuthManager's list."
            )
            tryEmitMessage("Account not found for removal.")
            _isLoadingAccountAction.value = false
            return
        }

        _isLoadingAccountAction.value = true
        microsoftAuthManager.removeAccount(accountToRemove) { result ->
            val message = when (result) {
                is RemoveAccountResult.Success -> "Account removed: ${accountToRemove.username}"
                is RemoveAccountResult.Error -> "Error removing account: ${
                    errorMapper.mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"
                is RemoveAccountResult.NotInitialized -> "Authentication system not ready."
                is RemoveAccountResult.AccountNotFound -> "Account to remove not found."
            }
            tryEmitMessage(message)
        }
    }

    override fun clearAccountActionMessage() {
        tryEmitMessage(null)
    }

    // --- Helper Functions ---
    private fun tryEmitMessage(message: String?) {
        externalScope.launch {
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                Log.w(
                    TAG,
                    "Failed to emit account action message (buffer full or no subscribers?): $message"
                )
            } else {
                Log.d(TAG, "Account action message emitted: '$message'")
            }
        }
    }

    private fun syncStateFromManager() {
        _authState.value = determineInitialAuthState()
        _accounts.value = mapToGenericAccounts(microsoftAuthManager.accounts)
        _isLoadingAccountAction.value = false
    }

    private fun determineInitialAuthState(): AuthState {
        return determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
    }

    private fun determineAuthState(isInitialized: Boolean, error: MsalException?): AuthState {
        return when {
            !isInitialized && error != null -> AuthState.InitializationError(error)
            !isInitialized && error == null -> AuthState.Initializing
            else -> AuthState.Initialized
        }
    }

    private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
        return msalAccounts.map { it.toGenericAccount() }
    }

    private fun IAccount.toGenericAccount(): Account {
        return Account(
            id = this.id ?: "",
            username = this.username ?: "Unknown User",
            providerType = "MS"
        )
    }
}