package net.melisma.core_data.repository

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.melisma.core_data.model.Account

interface AccountRepository {
    fun getAccounts(): Flow<List<Account>>
    fun getActiveAccount(providerType: String): Flow<Account?> // Or a single active overall
    val overallApplicationAuthState: StateFlow<OverallApplicationAuthState>

    /**
     * Initiates an authentication request for the given provider type.
     *
     * For providers like Google (AppAuth), this Flow is expected to emit an [Intent]
     * that the caller (typically UI) should launch via `startActivityForResult`.
     * The result of this Intent should then be passed to `handleAuthenticationResult`.
     *
     * For providers like Microsoft (MSAL 6.0+), which manage their own UI (e.g., BrowserTabActivity)
     * internally, this Flow will likely emit `null`. The authentication process is managed
     * internally by the repository implementation, and its outcome (success, error, cancellation)
     * will be reflected through updates to the accounts list (`getAccounts()`) and potentially
     * action messages (`observeActionMessages()`). The provided [activity] is used by the
     * implementation to launch the provider's UI.
     *
     * @param providerType The type of account provider (e.g., Account.PROVIDER_TYPE_MS).
     * @param activity The current Activity context, crucial for providers that need to launch UI.
     * @return A Flow that may emit an Intent to be launched, or null if the provider handles UI internally.
     */
    fun getAuthenticationIntentRequest(providerType: String, activity: Activity): Flow<Intent?>
    suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int, // e.g. Activity.RESULT_OK
        data: Intent?,
        activity: Activity // For context if needed by implementation
    )

    suspend fun signOut(account: Account)

    fun observeActionMessages(): Flow<String?>
    fun clearActionMessage()

    suspend fun markAccountForReauthentication(accountId: String, providerType: String)
    // ... other necessary generic account methods
}

enum class OverallApplicationAuthState {
    UNKNOWN, // Initial state, or state cannot be determined
    NO_ACCOUNTS_CONFIGURED, // No accounts have been added to the app yet
    AT_LEAST_ONE_ACCOUNT_AUTHENTICATED, // At least one account is signed in and has valid tokens
    ALL_ACCOUNTS_NEED_REAUTHENTICATION, // All configured accounts require re-authentication
    PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION // Some accounts are fine, others need re-authentication
}
