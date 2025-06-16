package net.melisma.core_data.repository

import android.app.Activity
// import android.content.Intent // No longer needed for signIn directly in the interface signature
import kotlinx.coroutines.flow.Flow
// import kotlinx.coroutines.flow.StateFlow // StateFlow is still used for overallApplicationAuthState
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.GenericAuthResult // New import
import net.melisma.core_data.model.GenericSignOutResult // New import

sealed class GenericSignOutAllResult {
    data class Success(val removedCount: Int, val failedCount: Int) : GenericSignOutAllResult()
    data class Error(val message: String, val cause: Throwable? = null) : GenericSignOutAllResult()
    object NotInitialized : GenericSignOutAllResult()
}

interface AccountRepository {
    fun getAccounts(): Flow<List<Account>>
    fun getAccountById(accountId: String): Flow<Account?>
    fun getActiveAccount(providerType: String): Flow<Account?> // Or a single active overall
    val overallApplicationAuthState: Flow<OverallApplicationAuthState>

    /**
     * Retrieves an account by its ID directly, without a Flow.
     * Useful for one-shot operations where a Flow is not needed (e.g., in a Worker).
     *
     * @param accountId The ID of the account to retrieve.
     * @return The Account object if found, or null otherwise.
     */
    suspend fun getAccountByIdSuspend(accountId: String): Account?

    /**
     * Initiates the sign-in process for the given provider.
     *
     * For MSAL: Returns a Flow that will directly emit Success, Error, or Cancelled.
     * For Google (AppAuth): Returns a Flow that may first emit UiActionRequired(intent)
     * if user interaction is needed. The caller must launch this intent.
     * The final result (Success, Error, Cancelled) will be emitted on the SAME Flow
     * after handleAuthenticationResult() is called by the Activity/Fragment.
     *
     * @param activity The current Activity, crucial for providers needing UI context.
     * @param loginHint Optional hint for the provider, e.g., email address.
     * @param providerType The type of account provider (e.g., Account.PROVIDER_TYPE_MS, Account.PROVIDER_TYPE_GOOGLE).
     * @return A Flow emitting the authentication result.
     */
    fun signIn(
        activity: Activity,
        loginHint: String? = null,
        providerType: String
    ): Flow<GenericAuthResult>

    /**
     * Handles the result from an external authentication activity (e.g., AppAuth's redirect).
     * This is crucial for the Google sign-in flow to complete the signIn Flow.
     *
     * @param providerType The provider type for which the result is being handled.
     * @param resultCode The result code from the Activity (e.g., Activity.RESULT_OK).
     * @param data The Intent data returned from the Activity.
     */
    suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: android.content.Intent?
    )
    // Removed activity from handleAuthenticationResult as DefaultAccountRepository can hold context if needed or get it passed down.

    fun signOut(account: Account): Flow<GenericSignOutResult>

    fun signOutAllMicrosoftAccounts(): Flow<GenericSignOutAllResult>

    fun observeActionMessages(): Flow<String?>
    fun clearActionMessage()

    suspend fun markAccountForReauthentication(accountId: String, providerType: String)
    // ... other necessary generic account methods

    // New method
    suspend fun syncAccount(accountId: String): Result<Unit>

    /**
     * Prepares and returns an Intent to initiate an authentication flow for the given provider.
     * This is typically used when the UI needs to launch an external activity for sign-in (e.g., AppAuth).
     * The Flow will emit UiActionRequired with the Intent, or Error if the intent cannot be created.
     *
     * @param providerType The type of account provider.
     * @param activity The current Activity, crucial for providers needing UI context.
     * @param scopes Optional list of scopes to request. If null, default scopes for the provider will be used.
     * @return A Flow emitting the result, typically UiActionRequired with an Intent or an Error.
     */
    fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        scopes: List<String>? = null
    ): Flow<GenericAuthResult>
}

enum class OverallApplicationAuthState {
    UNKNOWN, // Initial state, or state cannot be determined
    NO_ACCOUNTS_CONFIGURED, // No accounts have been added to the app yet
    AT_LEAST_ONE_ACCOUNT_AUTHENTICATED, // At least one account is signed in and has valid tokens
    ALL_ACCOUNTS_NEED_REAUTHENTICATION, // All configured accounts require re-authentication
    PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION // Some accounts are fine, others need re-authentication
}
