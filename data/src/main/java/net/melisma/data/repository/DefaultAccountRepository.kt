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

/**
 * Default implementation of the [AccountRepository] interface.
 * Currently only handles Microsoft accounts using [MicrosoftAuthManager].
 * Future: Will be extended to support multiple account types (Google, etc.).
 */
@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapperService
) : AccountRepository, AuthStateListener {

    private val TAG = "DefaultAccountRepo"

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
        // Register this repository to listen for changes from the auth manager.
        microsoftAuthManager.setAuthStateListener(this)
        // Ensure initial state is synced after registration
        syncStateFromManager()
    }

    // --- AuthStateListener Implementation ---
    /**
     * Called by [MicrosoftAuthManager] when its state changes.
     * Updates the repository's internal state flows.
     */
    override fun onAuthStateChanged(
        isInitialized: Boolean,
        accounts: List<IAccount>,
        error: MsalException?
    ) {
        Log.d(
            TAG,
            "Listener notified: init=$isInitialized, count=${accounts.size}, err=${error != null}"
        )
        // Update auth state based on manager's status
        _authState.value = determineAuthState(isInitialized, error)
        // Update the generic account list based on manager's accounts
        _accounts.value = mapToGenericAccounts(accounts)
        // Assume any auth state change means a pending action (if any) is complete.
        _isLoadingAccountAction.value = false // Reset loading indicator
    }

    // --- AccountRepository Interface Implementation ---

    /**
     * Adds an account with the specified provider type.
     * Currently supports "MS" (Microsoft) provider type.
     * Future: Will support "GOOGLE" as well.
     */
    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        when (providerType) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            else -> {
                Log.w(TAG, "Unsupported provider type: $providerType")
                tryEmitMessage("Unsupported account provider: $providerType")
            }
        }
    }

    /**
     * Legacy method for backward compatibility.
     * Defaults to Microsoft account type.
     */
    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        addAccount(activity, scopes, "MS")
    }

    override suspend fun removeAccount(account: Account) {
        when (account.providerType) {
            "MS" -> removeMicrosoftAccount(account)
            else -> {
                Log.w(
                    TAG,
                    "Cannot remove account with unsupported provider type: ${account.providerType}"
                )
                tryEmitMessage("Cannot remove account with unsupported provider type: ${account.providerType}")
            }
        }
    }

    /** Clears the last emitted transient message. */
    override fun clearAccountActionMessage() {
        tryEmitMessage(null) // Request emission of null
    }

    // --- Private Implementation Methods ---

    /**
     * Microsoft-specific account addition.
     */
    private suspend fun addMicrosoftAccount(activity: Activity, scopes: List<String>) {
        if (authState.value !is AuthState.Initialized) {
            Log.w(
                TAG,
                "addMicrosoftAccount called but auth state is not Initialized: ${authState.value}"
            )
            tryEmitMessage("Authentication system not ready.")
            return
        }
        _isLoadingAccountAction.value = true // Indicate operation started
        // Delegate the action to MicrosoftAuthManager
        microsoftAuthManager.addAccount(activity, scopes) { result ->
            // Map the MSAL-specific result to a user-friendly message
            val message = when (result) {
                is AddAccountResult.Success -> "Account added: ${result.account.username}"
                is AddAccountResult.Error -> "Error adding account: ${
                    errorMapper.mapAuthExceptionToUserMessage(result.exception)
                }"

                is AddAccountResult.Cancelled -> "Account addition cancelled."
                is AddAccountResult.NotInitialized -> "Authentication system not ready."
            }
            tryEmitMessage(message)
            // Loading state is reset inside onAuthStateChanged listener for robustness
        }
    }

    /**
     * Microsoft-specific account removal.
     */
    private suspend fun removeMicrosoftAccount(account: Account) {
        if (authState.value !is AuthState.Initialized) {
            Log.w(
                TAG,
                "removeMicrosoftAccount called but auth state is not Initialized: ${authState.value}"
            )
            tryEmitMessage("Authentication system not ready.")
            return
        }
        // Find the corresponding MSAL account object using the generic account ID.
        val accountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }

        if (accountToRemove == null) {
            Log.e(
                TAG,
                "Account to remove (ID: ${account.id}) not found in MicrosoftAuthManager's list."
            )
            tryEmitMessage("Account not found for removal.")
            _isLoadingAccountAction.value = false // Reset loading as action cannot proceed
            return
        }

        _isLoadingAccountAction.value = true // Indicate operation started
        // Delegate the action to MicrosoftAuthManager
        microsoftAuthManager.removeAccount(accountToRemove) { result ->
            // Map the MSAL-specific result to a user-friendly message
            val message = when (result) {
                is RemoveAccountResult.Success -> "Account removed: ${accountToRemove.username}"
                is RemoveAccountResult.Error -> "Error removing account: ${
                    errorMapper.mapAuthExceptionToUserMessage(result.exception)
                }"

                is RemoveAccountResult.NotInitialized -> "Authentication system not ready."
                is RemoveAccountResult.AccountNotFound -> "Account to remove not found."
            }
            // State flows are updated via the listener. Emit the message.
            tryEmitMessage(message)
            // Loading state is reset inside onAuthStateChanged
        }
    }

    // --- Helper Functions ---

    /** Safely tries to emit a message on the shared flow using the external scope. */
    private fun tryEmitMessage(message: String?) {
        // Use the provided external scope (likely application scope) for emission
        // to avoid issues with ViewModel scope lifecycle if repository outlives ViewModel.
        externalScope.launch {
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                // Log if emission failed (e.g., buffer full due to rapid events without collection)
                Log.w(
                    TAG,
                    "Failed to emit account action message (buffer full or no subscribers?): $message"
                )
            } else {
                // Log successful emission for debugging
                Log.d(TAG, "Account action message emitted: '$message'")
            }
        }
    }

    /** Forces synchronization of state from the manager to the repository flows. */
    private fun syncStateFromManager() {
        _authState.value = determineInitialAuthState()
        _accounts.value = mapToGenericAccounts(microsoftAuthManager.accounts)
        _isLoadingAccountAction.value = false // Reset loading state
    }

    /** Determines the initial AuthState based on the manager's state at construction time. */
    private fun determineInitialAuthState(): AuthState {
        return determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
    }

    /** Maps the manager's initialization status and error to the generic [AuthState]. */
    private fun determineAuthState(isInitialized: Boolean, error: MsalException?): AuthState {
        return when {
            !isInitialized && error != null -> AuthState.InitializationError(error) // Init failed
            !isInitialized && error == null -> AuthState.Initializing // Still initializing
            else -> AuthState.Initialized // Successfully initialized
        }
    }

    /** Maps a list of MSAL [IAccount] objects to a list of generic [Account] models. */
    private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
        return msalAccounts.map { it.toGenericAccount() }
    }

    /** Extension function to convert an MSAL [IAccount] to the generic [Account] model. */
    private fun IAccount.toGenericAccount(): Account {
        // Perform null checks for safety, although MSAL usually provides these.
        return Account(
            id = this.id ?: "", // Use MSAL account ID as the unique ID
            username = this.username ?: "Unknown User", // Use MSAL username
            providerType = "MS" // Hardcode the provider type for this repository
        )
    }
}