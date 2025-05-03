package net.melisma.mail

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the contract for managing user accounts and authentication state.
 * This abstracts the underlying authentication mechanism (e.g., MSAL, Google Sign-In).
 * ViewModels will interact with this interface, not directly with specific auth managers.
 */
interface AccountRepository {

    /**
     * A Flow emitting the current overall authentication state of the system
     * (Initializing, Initialized, Error).
     */
    val authState: StateFlow<AuthState>

    /**
     * A Flow emitting the current list of authenticated user accounts ([Account]).
     * The list will update automatically when accounts are added or removed.
     */
    val accounts: StateFlow<List<Account>>

    /**
     * A Flow indicating whether an account operation (add/remove) is currently in progress.
     * Useful for showing loading indicators in the UI during these operations.
     */
    val isLoadingAccountAction: StateFlow<Boolean>

    /**
     * A Flow emitting single-event messages related to account operations
     * (e.g., success/error/cancellation messages for add/remove actions).
     * Designed to be collected for showing Toasts or Snackbars. Emits null when no message pending.
     */
    val accountActionMessage: Flow<String?> // Renamed from toastMessage for clarity

    /**
     * Initiates the flow to add a new account interactively.
     * The result (success, error, cancellation) will be communicated via [accountActionMessage]
     * and the account list/auth state will update via the respective flows.
     *
     * @param activity The current Activity context, required to start the interactive flow.
     * @param scopes The permission scopes required for the account being added.
     */
    suspend fun addAccount(activity: Activity, scopes: List<String>) // Made suspend

    /**
     * Initiates the removal of the specified account.
     * The result (success, error) will be communicated via [accountActionMessage]
     * and the account list/auth state will update via the respective flows.
     *
     * @param account The [Account] object to remove.
     */
    suspend fun removeAccount(account: Account) // Made suspend, takes generic Account

    /**
     * Clears any pending message emitted by [accountActionMessage].
     * Should be called by the collector after displaying the message to the user.
     */
    fun clearAccountActionMessage() // Renamed for clarity
}