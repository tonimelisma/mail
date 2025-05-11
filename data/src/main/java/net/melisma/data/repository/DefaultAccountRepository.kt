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
// TODO: Import GoogleAuthManager related classes when fully implementing Google sign-in
// import net.melisma.backend_google.auth.GoogleAuthManager
// import net.melisma.backend_google.auth.GoogleSignInResult
// import net.melisma.backend_google.auth.GoogleScopeAuthResult
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account // Assuming Account.kt is in this package
import net.melisma.core_data.model.AuthState // Assuming AuthState.kt is in this package
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    // private val googleAuthManager: GoogleAuthManager, // TODO: Uncomment and inject when GoogleAuthManager is ready
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : AccountRepository, AuthStateListener {

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
        _isLoadingAccountAction.value = false
    }

    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        when (providerType.uppercase()) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            "GOOGLE" -> {
                Log.i(TAG, "Attempting to add Google account.")
                tryEmitMessage("Google account addition coming soon.")
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
        _isLoadingAccountAction.value = true
        when (account.providerType.uppercase()) {
            "MS" -> removeMicrosoftAccount(account)
            "GOOGLE" -> {
                Log.i(
                    TAG,
                    "Attempting to remove Google account: ${account.username}"
                ) // CORRECTED: Using account.username
                tryEmitMessage("Google account removal coming soon.")
                _isLoadingAccountAction.value = false
            }
            else -> {
                Log.w(
                    TAG,
                    "Cannot remove account with unsupported provider type: ${account.providerType}"
                )
                tryEmitMessage("Cannot remove account with unsupported provider type.")
                _isLoadingAccountAction.value = false
            }
        }
    }

    override fun clearAccountActionMessage() {
        tryEmitMessage(null)
    }

    private fun getErrorMapperForProvider(providerType: String): ErrorMapperService? {
        val mapper = errorMappers[providerType.uppercase()]
        if (mapper == null) {
            Log.e(
                TAG,
                "No ErrorMapperService found for provider type: $providerType. Available mappers: ${errorMappers.keys}"
            )
        }
        return mapper
    }

    private suspend fun addMicrosoftAccount(activity: Activity, scopes: List<String>) {
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        if (determineAuthState(
                microsoftAuthManager.isInitialized,
                microsoftAuthManager.initializationError
            ) !is AuthState.Initialized
        ) {
            Log.w(TAG, "addMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }
        _isLoadingAccountAction.value = true
        microsoftAuthManager.addAccount(activity, scopes)
            .onEach { result ->
                val message = when (result) {
                    is AddAccountResult.Success -> "Microsoft account added: ${result.account.username}"
                    is AddAccountResult.Error -> "Error adding Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"
                    is AddAccountResult.Cancelled -> "Microsoft account addition cancelled."
                    is AddAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                }
                tryEmitMessage(message)
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in addMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope)
    }

    private suspend fun removeMicrosoftAccount(account: Account) {
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        if (determineAuthState(
                microsoftAuthManager.isInitialized,
                microsoftAuthManager.initializationError
            ) !is AuthState.Initialized
        ) {
            Log.w(TAG, "removeMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }

        val msalAccountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }
        if (msalAccountToRemove == null) {
            Log.e(TAG, "Microsoft account to remove (ID: ${account.id}) not found in MS manager.")
            tryEmitMessage("Microsoft account not found for removal.")
            _isLoadingAccountAction.value = false
            return
        }

        microsoftAuthManager.removeAccount(msalAccountToRemove)
            .onEach { result ->
                val message = when (result) {
                    is RemoveAccountResult.Success -> "Microsoft account removed: ${msalAccountToRemove.username}"
                    is RemoveAccountResult.Error -> "Error removing Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"
                    is RemoveAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                    is RemoveAccountResult.AccountNotFound -> "Microsoft account to remove not found by MS auth manager."
                }
                tryEmitMessage(message)
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in removeMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
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
                // CORRECTED: Removed 'email = msalAccount.username' as Account constructor doesn't have it
                username = msalAccount.username ?: "Unknown User",
                providerType = "MS"
            )
        }
    }
}