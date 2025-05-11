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
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    // private val googleAuthManager: GoogleAuthManager, // TODO: Uncomment when GoogleAuthManager is ready
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : AccountRepository, AuthStateListener {

    private val TAG = "DefaultAccountRepo" // Logging TAG

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
        Log.d(
            TAG,
            "Initializing DefaultAccountRepository. Injected errorMappers keys: ${errorMappers.keys}"
        )
        Log.d(TAG, "Registering as AuthStateListener for MicrosoftAuthManager")
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
            "onAuthStateChanged (MSAL): init=$isInitialized, msAccountCount=${msAccounts.size}, errorPresent=${error != null}"
        )
        _authState.value = determineAuthState(isInitialized, error)
        val newGenericAccounts = mapToGenericAccounts(msAccounts)
        Log.d(TAG, "onAuthStateChanged: Mapped to ${newGenericAccounts.size} generic accounts.")
        _accounts.value = newGenericAccounts
        _isLoadingAccountAction.value = false
    }

    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        Log.d(TAG, "addAccount called. ProviderType: $providerType, Scopes: $scopes")
        when (providerType.uppercase()) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            "GOOGLE" -> {
                Log.i(TAG, "Attempting to add Google account. (Not yet implemented)")
                tryEmitMessage("Google account addition coming soon.")
            }
            else -> {
                Log.w(TAG, "Unsupported provider type for addAccount: $providerType")
                tryEmitMessage("Unsupported account provider: $providerType")
            }
        }
    }

    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        Log.d(
            TAG,
            "addAccount (legacy overload) called, defaulting to MS provider. Scopes: $scopes"
        )
        addMicrosoftAccount(activity, scopes)
    }

    override suspend fun removeAccount(account: Account) {
        Log.d(
            TAG,
            "removeAccount called for account: ${account.username} (Provider: ${account.providerType})"
        )
        _isLoadingAccountAction.value = true
        when (account.providerType.uppercase()) {
            "MS" -> removeMicrosoftAccount(account)
            "GOOGLE" -> {
                Log.i(
                    TAG,
                    "Attempting to remove Google account: ${account.username}. (Not yet implemented)"
                )
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
        Log.d(TAG, "clearAccountActionMessage called.")
        tryEmitMessage(null)
    }

    private fun getErrorMapperForProvider(providerType: String): ErrorMapperService? {
        val providerKey = providerType.uppercase()
        Log.d(
            TAG,
            "getErrorMapperForProvider: Attempting to get mapper for key '$providerKey'. Available keys: ${errorMappers.keys}"
        )
        val mapper = errorMappers[providerKey]
        if (mapper == null) {
            Log.e(TAG, "No ErrorMapperService found for provider type: $providerKey")
        }
        return mapper
    }

    private suspend fun addMicrosoftAccount(activity: Activity, scopes: List<String>) {
        Log.d(TAG, "addMicrosoftAccount: Attempting for scopes: $scopes")
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            Log.e(TAG, "addMicrosoftAccount: MS Error handler not found.")
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        val currentAuthState = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(TAG, "addMicrosoftAccount: Current MS Auth State before adding: $currentAuthState")
        if (currentAuthState !is AuthState.Initialized) {
            Log.w(TAG, "addMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }
        _isLoadingAccountAction.value = true
        Log.d(TAG, "addMicrosoftAccount: Calling MicrosoftAuthManager.addAccount...")
        microsoftAuthManager.addAccount(activity, scopes)
            .onEach { result ->
                Log.d(TAG, "addMicrosoftAccount: Result from MicrosoftAuthManager: $result")
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
        Log.d(TAG, "removeMicrosoftAccount: Attempting for account: ${account.username}")
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            Log.e(TAG, "removeMicrosoftAccount: MS Error handler not found.")
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        val currentAuthState = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(
            TAG,
            "removeMicrosoftAccount: Current MS Auth State before removing: $currentAuthState"
        )
        if (currentAuthState !is AuthState.Initialized) {
            Log.w(TAG, "removeMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }

        val msalAccountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }
        if (msalAccountToRemove == null) {
            Log.e(
                TAG,
                "Microsoft account to remove (ID: ${account.id}, Username: ${account.username}) not found in MS manager."
            )
            tryEmitMessage("Microsoft account not found for removal.")
            _isLoadingAccountAction.value = false
            return
        }

        Log.d(
            TAG,
            "removeMicrosoftAccount: Calling MicrosoftAuthManager.removeAccount for MSAL account: ${msalAccountToRemove.username}"
        )
        microsoftAuthManager.removeAccount(msalAccountToRemove)
            .onEach { result ->
                Log.d(TAG, "removeMicrosoftAccount: Result from MicrosoftAuthManager: $result")
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
        Log.d(TAG, "tryEmitMessage: '$message'")
        externalScope.launch {
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                Log.w(TAG, "Failed to emit account action message (Buffer full?): $message")
            }
        }
    }

    private fun syncStateFromManager() {
        Log.d(TAG, "syncStateFromManager called.")
        onAuthStateChanged(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.accounts,
            microsoftAuthManager.initializationError
        )
    }

    private fun determineInitialAuthState(): AuthState {
        val state = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(TAG, "determineInitialAuthState: $state")
        return state
    }

    private fun determineAuthState(isInitialized: Boolean, error: MsalException?): AuthState {
        val state = when {
            !isInitialized && error != null -> AuthState.InitializationError(error)
            !isInitialized && error == null -> AuthState.Initializing
            else -> AuthState.Initialized
        }
        Log.v(
            TAG,
            "determineAuthState: isInitialized=$isInitialized, errorPresent=${error != null} -> $state"
        )
        return state
    }

    private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
        Log.d(TAG, "mapToGenericAccounts: Mapping ${msalAccounts.size} MSAL accounts.")
        return msalAccounts.map { msalAccount ->
            Account(
                id = msalAccount.id ?: "",
                username = msalAccount.username ?: "Unknown User",
                providerType = "MS"
            )
        }.also {
            Log.d(
                TAG,
                "mapToGenericAccounts: Resulting generic accounts: ${it.joinToString { acc -> acc.username }}"
            )
        }
    }
}