package net.melisma.core_data.datasource

import android.app.Activity
import net.melisma.core_data.model.Account // Updated import


/**
 * Interface for components that can provide access tokens for a given account.
 * This abstracts the specific authentication mechanism (MSAL, Google Auth, etc.).
 */
interface TokenProvider {
    /**
     * Acquires an access token for the specified account and scopes.
     * May attempt silent acquisition first and fall back to interactive flow if needed/possible.
     *
     * @param account The generic [Account] for which to get the token.
     * @param scopes The list of permission scopes required.
     * @param activity The optional Activity context, required for interactive flows.
     * @return A [Result] containing the access token string on success, or an Exception on failure.
     */
    suspend fun getAccessToken(
        account: Account,
        scopes: List<String>,
        activity: Activity? = null
    ): Result<String>
}
