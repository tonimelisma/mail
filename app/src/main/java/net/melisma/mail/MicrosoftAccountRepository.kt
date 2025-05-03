// File: app/src/main/java/net/melisma/mail/MicrosoftAccountRepository.kt
// (Corrected Implementation - Adhering to existing patterns)

package net.melisma.mail

// Import necessary MSAL classes used in original code
// CoroutineScope is still needed for triggering SharedFlow emission from callbacks if needed
// Import Auth results and listener from feature_auth
// Import custom scope annotation from DI module
import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.melisma.feature_auth.AddAccountResult
import net.melisma.feature_auth.AuthStateListener
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.feature_auth.RemoveAccountResult
import net.melisma.mail.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Corrected implementation of AccountRepository for Microsoft accounts.
 * Strictly adheres to the patterns and APIs used in the original MicrosoftAuthManager
 * and MainViewModel code provided.
 */
@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope // Scope for emitting events
) : AccountRepository, AuthStateListener { // Implement the listener

    private val TAG = "MicrosoftAccountRepo"

    // --- State Flows reflecting Manager State ---
    // These flows are updated via the AuthStateListener callback

    private val _authState = MutableStateFlow(determineInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Store accounts mapped to the generic type
    private val _accounts = MutableStateFlow(mapToGenericAccounts(microsoftAuthManager.accounts))
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    // --- State Flows for Repository Actions ---

    private val _isLoadingAccountAction = MutableStateFlow(false)
    override val isLoadingAccountAction: StateFlow<Boolean> = _isLoadingAccountAction.asStateFlow()

    // SharedFlow for action result messages (toasts/snackbars)
    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    // --- Initialization ---
    init {
        // Register the repository itself as the listener
        Log.d(TAG, "Initializing and registering as AuthStateListener")
        microsoftAuthManager.setAuthStateListener(this)
        // Sync initial state immediately
        syncStateFromManager()
    }

    // --- AuthStateListener Implementation ---
    override fun onAuthStateChanged(
        isInitialized: Boolean,
        msalAccounts: List<IAccount>, // Raw MSAL accounts from manager
        error: MsalException?
    ) {
        Log.d(TAG, "Listener notified: init=$isInitialized, count=${msalAccounts.size}, err=$error")
        // Update internal StateFlows based on the callback data
        _authState.value = determineAuthState(isInitialized, error)
        _accounts.value = mapToGenericAccounts(msalAccounts)

        // Any auth state change implies a recently completed action is finished
        _isLoadingAccountAction.value = false
    }

    // --- AccountRepository Interface Implementation ---

    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        // Check internal state derived from the listener
        if (authState.value !is AuthState.Initialized) {
            Log.w(TAG, "addAccount called but auth state is not Initialized: ${authState.value}")
            tryEmitMessage("Authentication system not ready.")
            return
        }
        _isLoadingAccountAction.value = true
        // Call manager method - result handled by AuthStateListener updating flows,
        // but we also emit a message here based on the direct callback result.
        microsoftAuthManager.addAccount(activity, scopes) { result ->
            val message = when (result) {
                is AddAccountResult.Success -> "Account added: ${result.account.username}"
                is AddAccountResult.Error -> "Error adding account: ${
                    mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"

                is AddAccountResult.Cancelled -> "Account addition cancelled."
                is AddAccountResult.NotInitialized -> "Authentication system not ready."
            }
            tryEmitMessage(message)
            // Loading state will be turned off by onAuthStateChanged
        }
    }

    override suspend fun removeAccount(account: Account) {
        if (authState.value !is AuthState.Initialized) {
            Log.w(TAG, "removeAccount called but auth state is not Initialized: ${authState.value}")
            tryEmitMessage("Authentication system not ready.")
            return
        }
        // Find the original IAccount from the manager's current list
        val accountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }

        if (accountToRemove == null) {
            Log.e(TAG, "Account to remove (ID: ${account.id}) not found in MicrosoftAuthManager.")
            tryEmitMessage("Account not found for removal.")
            return
        }

        _isLoadingAccountAction.value = true
        // Call manager method - result handled by AuthStateListener updating flows,
        // but we also emit a message here based on the direct callback result.
        microsoftAuthManager.removeAccount(accountToRemove) { result ->
            val message = when (result) {
                is RemoveAccountResult.Success -> "Account removed: ${accountToRemove.username}"
                is RemoveAccountResult.Error -> "Error removing account: ${
                    mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"

                is RemoveAccountResult.NotInitialized -> "Authentication system not ready."
                is RemoveAccountResult.AccountNotFound -> "Account to remove not found."
            }
            tryEmitMessage(message)
            // Loading state will be turned off by onAuthStateChanged
        }
    }

    override fun clearAccountActionMessage() {
        tryEmitMessage(null) // Emit null to clear
    }

    // --- Helper Functions ---

    /** Safely emit message using the repository's scope */
    private fun tryEmitMessage(message: String?) {
        externalScope.launch {
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                Log.w(
                    TAG,
                    "Failed to emit account action message (buffer full or no subscribers?): $message"
                )
            } else {
                Log.d(TAG, "Account action message '$message' emitted: $emitted")
            }
        }
    }

    /** Syncs internal state based on current manager properties */
    private fun syncStateFromManager() {
        _authState.value = determineInitialAuthState()
        _accounts.value = mapToGenericAccounts(microsoftAuthManager.accounts)
    }

    /** Determines AuthState based on manager initialization properties */
    private fun determineInitialAuthState(): AuthState {
        return determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
    }

    /** Determines AuthState based on listener callback data */
    private fun determineAuthState(isInitialized: Boolean, error: MsalException?): AuthState {
        return when {
            !isInitialized && error != null -> AuthState.InitializationError(error)
            !isInitialized && error == null -> AuthState.Initializing
            else -> AuthState.Initialized // isInitialized is true
        }
    }

    /** Maps a list of IAccount to generic Account */
    private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
        return msalAccounts.map { it.toGenericAccount() }
    }

    /** Extension function to map MSAL's IAccount to our generic Account data class. */
    private fun IAccount.toGenericAccount(): Account {
        // Use only properties confirmed available/used previously: id, username
        return Account(
            id = this.id ?: "", // Use id directly, provide fallback
            username = this.username ?: "Unknown User",
            providerType = "MS"
        )
    }

    /** Maps MSAL exceptions using types previously identified */
    private fun mapAuthExceptionToUserMessage(exception: MsalException): String {
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        // Use specific exception types known from original ViewModel/MSAL Manager code
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalClientException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication client error (${exception.errorCode})"

            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error (${exception.errorCode})"

            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication failed (${exception.errorCode})" // Include error code if possible
        }
    }
}