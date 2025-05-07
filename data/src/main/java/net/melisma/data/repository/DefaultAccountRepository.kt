// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

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
import net.melisma.core_common.errors.ErrorMapperService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapperService
    // TODO: Inject GoogleAuthManager when Google support is added
) : AccountRepository, AuthStateListener { // Implements AuthStateListener for MicrosoftAuthManager

    private val TAG = "DefaultAccountRepo"

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
        Log.d(TAG, "Initializing and registering as AuthStateListener for MicrosoftAuthManager")
        microsoftAuthManager.setAuthStateListener(this)
        syncStateFromManager()
    }

    override fun onAuthStateChanged(
        isInitialized: Boolean,
        msAccounts: List<IAccount>,
        error: MsalException?
    ) {
        Log.d(
            TAG,
            "MS AuthStateListener notified: init=$isInitialized, msAccountCount=${msAccounts.size}, errorPresent=${error != null}"
        )
        _authState.value = determineAuthState(isInitialized, error)
        _accounts.value = mapToGenericAccounts(msAccounts)
        _isLoadingAccountAction.value = false // Reset loading on any auth state change
    }

    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        when (providerType.uppercase()) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            "GOOGLE" -> {
                Log.w(TAG, "Google account addition not yet implemented.")
                tryEmitMessage("Google account support is coming soon.")
                // TODO: Implement Google account addition
            }
            else -> {
                Log.w(TAG, "Unsupported provider type for addAccount: $providerType")
                tryEmitMessage("Unsupported account provider: $providerType")
            }
        }
    }

    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        Log.d(TAG, "addAccount (legacy) called, defaulting to MS provider.")
        addMicrosoftAccount(activity, scopes)
    }

    override suspend fun removeAccount(account: Account) {
        when (account.providerType.uppercase()) {
            "MS" -> removeMicrosoftAccount(account)
            "GOOGLE" -> {
                Log.w(TAG, "Google account removal not yet implemented.")
                tryEmitMessage("Google account support is coming soon.")
                // TODO: Implement Google account removal
            }
            else -> {
                Log.w(
                    TAG,
                    "Cannot remove account with unsupported provider type: ${account.providerType}"
                )
                tryEmitMessage("Cannot remove account with unsupported provider type.")
            }
        }
    }

    override fun clearAccountActionMessage() {
        tryEmitMessage(null)
    }

    private suspend fun addMicrosoftAccount(activity: Activity, scopes: List<String>) {
        if (determineAuthState(
                microsoftAuthManager.isInitialized,
                microsoftAuthManager.initializationError
            ) !is AuthState.Initialized
        ) {
            Log.w(TAG, "addMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            return
        }
        _isLoadingAccountAction.value = true
        microsoftAuthManager.addAccount(activity, scopes) // This now returns Flow
            .onEach { result -> // Collect the single emission from the Flow
                val message = when (result) {
                    is AddAccountResult.Success -> "Microsoft account added: ${result.account.username}"
                    is AddAccountResult.Error -> "Error adding Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"

                    is AddAccountResult.Cancelled -> "Microsoft account addition cancelled."
                    is AddAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                    // No 'else' needed for sealed class when all subtypes are handled
                }
                tryEmitMessage(message)
                // isLoadingAccountAction is reset by onAuthStateChanged,
                // but we can also reset it here for more immediate feedback if desired.
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in addMicrosoftAccount flow", e)
                tryEmitMessage("An unexpected error occurred while adding the Microsoft account.")
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope)
    }

    private suspend fun removeMicrosoftAccount(account: Account) {
        if (determineAuthState(
                microsoftAuthManager.isInitialized,
                microsoftAuthManager.initializationError
            ) !is AuthState.Initialized
        ) {
            Log.w(TAG, "removeMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            return
        }

        val msalAccountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }
        if (msalAccountToRemove == null) {
            Log.e(TAG, "Microsoft account to remove (ID: ${account.id}) not found in MS manager.")
            tryEmitMessage("Microsoft account not found for removal.")
            return // No need to set loading if account not found
        }

        _isLoadingAccountAction.value = true
        microsoftAuthManager.removeAccount(msalAccountToRemove) // This now returns Flow
            .onEach { result -> // Collect the single emission from the Flow
                val message = when (result) {
                    is RemoveAccountResult.Success -> "Microsoft account removed: ${msalAccountToRemove.username}"
                    is RemoveAccountResult.Error -> "Error removing Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"

                    is RemoveAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                    is RemoveAccountResult.AccountNotFound -> "Microsoft account to remove not found by MS auth manager."
                    // No 'else' needed for sealed class when all subtypes are handled
                }
                tryEmitMessage(message)
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in removeMicrosoftAccount flow", e)
                tryEmitMessage("An unexpected error occurred while removing the Microsoft account.")
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope)
    }

    private fun tryEmitMessage(message: String?) {
        externalScope.launch {
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                Log.w(TAG, "Failed to emit account action message: $message")
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
