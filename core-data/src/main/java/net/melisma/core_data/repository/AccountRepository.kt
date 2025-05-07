package net.melisma.core_data.repository

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.melisma.core_data.model.Account // Updated import
import net.melisma.core_data.model.AuthState // Updated import

/**
 * Interface defining the contract for managing user accounts and the overall authentication state.
 * This abstracts the underlying authentication mechanism (e.g., MSAL, Google Sign-In)
 * and provides a consistent API for the ViewModel layer.
 */
interface AccountRepository {

    /**
     * A [StateFlow] emitting the current overall authentication system state
     * ([AuthState]: Initializing, Initialized, Error). Useful for showing global loading/error states.
     */
    val authState: StateFlow<AuthState>

    /**
     * A [StateFlow] emitting the current list of authenticated user accounts ([Account]).
     * The list updates automatically when accounts are added or removed.
     */
    val accounts: StateFlow<List<Account>>

    /**
     * A [StateFlow] indicating whether an account operation (add/remove) is currently in progress.
     * Useful for disabling buttons or showing loading indicators in the UI during these operations.
     */
    val isLoadingAccountAction: StateFlow<Boolean>

    /**
     * A [Flow] emitting single-event messages related to account operations
     * (e.g., success/error/cancellation messages for add/remove actions).
     * Designed to be collected for showing transient UI feedback like Toasts or Snackbars.
     * Emits null when no message is pending.
     */
    val accountActionMessage: Flow<String?>

    /**
     * Initiates the flow to add a new account interactively via the underlying auth provider.
     * The result (success, error, cancellation) will be communicated via [accountActionMessage],
     * and the account list ([accounts]) and auth state ([authState]) will update via their respective flows.
     *
     * @param activity The current [Activity] context, required by most auth SDKs to start the interactive flow.
     * @param scopes The list of permission scopes required for the account being added (e.g., "Mail.Read").
     */
    suspend fun addAccount(activity: Activity, scopes: List<String>)

    /**
     * Initiates the flow to add a new account of the specified provider type interactively.
     * The result (success, error, cancellation) will be communicated via [accountActionMessage],
     * and the account list ([accounts]) and auth state ([authState]) will update via their respective flows.
     *
     * @param activity The current [Activity] context, required by most auth SDKs to start the interactive flow.
     * @param scopes The list of permission scopes required for the account being added (e.g., "Mail.Read").
     * @param providerType The type of provider (e.g., "MS" for Microsoft, "GOOGLE" for Google).
     */
    suspend fun addAccount(activity: Activity, scopes: List<String>, providerType: String)

    /**
     * Initiates the removal of the specified account from the application and the underlying auth provider.
     * The result (success, error) will be communicated via [accountActionMessage],
     * and the account list ([accounts]) will update via its flow.
     *
     * @param account The [Account] object representing the account to remove.
     */
    suspend fun removeAccount(account: Account)

    /**
     * Clears any pending message that might have been emitted by [accountActionMessage].
     * This should typically be called by the UI layer after displaying the message to the user,
     * preventing the same message from being shown again on configuration changes.
     */
    fun clearAccountActionMessage()
}
