// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.melisma.backend_microsoft.auth.AddAccountResult
import net.melisma.backend_microsoft.auth.AuthStateListener
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.RemoveAccountResult
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapperService
) : AccountRepository, AuthStateListener {

    private val TAG = "MicrosoftAccountRepo"

    private val _authState = MutableStateFlow(determineInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _accounts = MutableStateFlow(mapToGenericAccounts(microsoftAuthManager.accounts))
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _isLoadingAccountAction = MutableStateFlow(false)
    override val isLoadingAccountAction: StateFlow<Boolean> = _isLoadingAccountAction.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    init {
        Log.d(TAG, "Initializing and registering as AuthStateListener")
        microsoftAuthManager.setAuthStateListener(this)
        syncStateFromManager()
    }

    override fun onAuthStateChanged(
        isInitialized: Boolean,
        msalAccounts: List<IAccount>, // Renamed for clarity
        error: MsalException?
    ) {
        Log.d(
            TAG,
            "AuthStateListener notified: init=$isInitialized, msalAccountCount=${msalAccounts.size}, errorPresent=${error != null}"
        )
        _authState.value = determineAuthState(isInitialized, error)
        _accounts.value = mapToGenericAccounts(msalAccounts)
        // If an auth state change occurs, it might imply a previous action completed.
        // However, specific loading states should ideally be managed per action.
        // For simplicity, we can reset a general loading indicator here, but it's broad.
        _isLoadingAccountAction.value = false
    }

    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        if (authState.value !is AuthState.Initialized) {
            Log.w(TAG, "addAccount called but auth state is not Initialized: ${authState.value}")
            tryEmitMessage("Authentication system not ready.")
            return
        }
        _isLoadingAccountAction.value = true

        // Collect the Flow from MicrosoftAuthManager
        microsoftAuthManager.addAccount(activity, scopes)
            .onEach { result -> // Process the single emitted result
                val message = when (result) {
                    is AddAccountResult.Success -> "Account added: ${result.account.username}"
                    is AddAccountResult.Error -> "Error adding account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"

                    is AddAccountResult.Cancelled -> "Account addition cancelled."
                    is AddAccountResult.NotInitialized -> "Authentication system not ready."
                    // No 'else' needed for sealed class if all direct subtypes are handled.
                    // If compiler insists, it might be due to older Kotlin/Lint versions or a bug.
                    // In such cases, an `else -> {}` or `else -> error("Unhandled AddAccountResult: $result")` can be added.
                }
                tryEmitMessage(message)
                // The AuthStateListener (onAuthStateChanged) will handle updating accounts list
                // and resetting isLoadingAccountAction more broadly.
                // If more granular control is needed, reset _isLoadingAccountAction here.
                _isLoadingAccountAction.value = false // Reset after processing the result
            }
            .catch { e -> // Catch any exceptions from the flow itself (should be rare if MSAL handles errors)
                Log.e(TAG, "Exception in addAccount flow", e)
                tryEmitMessage("An unexpected error occurred while adding the account.")
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope) // Launch in the external scope
    }

    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        if (providerType.equals("MS", ignoreCase = true)) {
            addAccount(activity, scopes)
        } else {
            Log.w(
                TAG,
                "addAccount called with unsupported provider type: $providerType for MicrosoftAccountRepository"
            )
            tryEmitMessage("Provider type '$providerType' not supported by Microsoft accounts.")
            // Optionally set _isLoadingAccountAction.value = false if it was set before this check
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
            return // No need to set loading true if account not found
        }

        _isLoadingAccountAction.value = true

        // Collect the Flow from MicrosoftAuthManager
        microsoftAuthManager.removeAccount(accountToRemove)
            .onEach { result -> // Process the single emitted result
                val message = when (result) {
                    is RemoveAccountResult.Success -> "Account removed: ${accountToRemove.username}"
                    is RemoveAccountResult.Error -> "Error removing account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"

                    is RemoveAccountResult.NotInitialized -> "Authentication system not ready."
                    is RemoveAccountResult.AccountNotFound -> "Account to remove not found by authentication manager."
                    // No 'else' needed for sealed class if all direct subtypes are handled.
                }
                tryEmitMessage(message)
                // AuthStateListener will update account list.
                _isLoadingAccountAction.value = false // Reset after processing the result
            }
            .catch { e ->
                Log.e(TAG, "Exception in removeAccount flow", e)
                tryEmitMessage("An unexpected error occurred while removing the account.")
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope) // Launch in the external scope
    }

    override fun clearAccountActionMessage() {
        tryEmitMessage(null)
    }

    private fun tryEmitMessage(message: String?) {
        externalScope.launch {
            // Using tryEmit for SharedFlow with buffer
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                Log.w(
                    TAG,
                    "Failed to emit account action message (buffer full or no active collectors?): $message"
                )
            } else if (message != null) { // Avoid logging for null (clear) messages if not desired
                Log.d(TAG, "Account action message emitted: '$message'")
            }
        }
    }

    private fun syncStateFromManager() {
        onAuthStateChanged(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.accounts,
            microsoftAuthManager.initializationError
        )
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
        return msalAccounts.map { msalAccount ->
            Account(
                id = msalAccount.id ?: "",
                username = msalAccount.username ?: "Unknown User",
                providerType = "MS"
            )
        }
    }
}
