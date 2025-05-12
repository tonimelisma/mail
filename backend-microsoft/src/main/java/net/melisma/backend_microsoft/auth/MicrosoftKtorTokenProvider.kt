package net.melisma.backend_microsoft.auth

import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftKtorTokenProvider @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val activeAccountHolder: ActiveMicrosoftAccountHolder
) {
    private val TAG = "MicrosoftKtorTokenProv"
    private val refreshMutex = Mutex()

    // Define default scopes needed for Graph API calls by GraphApiHelper
    private val defaultGraphApiScopes =
        listOf("https://graph.microsoft.com/.default") // Or more specific like "User.Read", "Mail.ReadWrite" etc.

    suspend fun loadBearerTokens(): BearerTokens? {
        val accountId = activeAccountHolder.getActiveMicrosoftAccountIdValue()
        if (accountId == null) {
            Log.w(TAG, "Ktor: No active Microsoft account ID for loading tokens.")
            return null
        }

        val msalAccount = findMsalAccount(accountId)
        if (msalAccount == null) {
            Log.e(TAG, "Ktor: MSAL IAccount not found for active ID: $accountId")
            return null
        }

        Log.d(
            TAG,
            "Ktor: Attempting to load token silently for MS account: ${msalAccount.username}"
        )
        return acquireTokenAndConvertToBearer(msalAccount, isRefreshAttempt = false)
    }

    suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens? {
        val accountId = activeAccountHolder.getActiveMicrosoftAccountIdValue()
        if (accountId == null) {
            Log.e(TAG, "Ktor Refresh: No active Microsoft account ID.")
            return null
        }

        val msalAccount = findMsalAccount(accountId)
        if (msalAccount == null) {
            Log.e(
                TAG,
                "Ktor Refresh: MSAL IAccount not found for active ID: $accountId during refresh."
            )
            return null
        }

        Log.d(TAG, "Ktor Refresh: Attempting for MS account: ${msalAccount.username}")
        // Use mutex to prevent multiple concurrent silent refresh calls for the same account
        return refreshMutex.withLock {
            // Optional: Double-check if token is still invalid or was refreshed by another call
            // This is complex with MSAL as it manages its own cache. A simple re-fetch is often easiest.
            acquireTokenAndConvertToBearer(msalAccount, isRefreshAttempt = true)
        }
    }

    private suspend fun acquireTokenAndConvertToBearer(
        msalAccount: IAccount,
        isRefreshAttempt: Boolean
    ): BearerTokens? {
        return try {
            val acquireTokenResult =
                microsoftAuthManager.acquireTokenSilent(msalAccount, defaultGraphApiScopes).first()
            when (acquireTokenResult) {
                is AcquireTokenResult.Success -> {
                    Log.i(
                        TAG,
                        "Ktor: MSAL Token acquired successfully for ${msalAccount.username}. Expires: ${acquireTokenResult.result.expiresOn}"
                    )
                    // The 'refreshToken' field in BearerTokens is not directly used by MSAL for its own refresh.
                    // MSAL uses its internal refresh token when acquireTokenSilent is called.
                    // We can put the account ID or a placeholder if needed by Ktor, or an empty string.
                    BearerTokens(
                        acquireTokenResult.result.accessToken,
                        msalAccount.id ?: "ms_account_id_placeholder"
                    )
                }

                is AcquireTokenResult.UiRequired -> {
                    Log.w(
                        TAG,
                        "Ktor: MSAL UI required for ${msalAccount.username}. Cannot silently acquire/refresh token."
                    )
                    // Signal Ktor that refresh failed and interaction is needed.
                    // App should have a mechanism to catch this and trigger interactive sign-in.
                    null
                }

                is AcquireTokenResult.Error -> {
                    Log.e(
                        TAG,
                        "Ktor: MSAL Error acquiring token for ${msalAccount.username}",
                        acquireTokenResult.exception
                    )
                    null
                }

                else -> { // Cancelled, NotInitialized, NoAccountProvided (shouldn't happen if msalAccount is valid)
                    Log.w(
                        TAG,
                        "Ktor: Unexpected MSAL result during token acquisition for ${msalAccount.username}: $acquireTokenResult"
                    )
                    null
                }
            }
        } catch (e: MsalUiRequiredException) { // Catch specifically if flow.first() rethrows it
            Log.w(
                TAG,
                "Ktor: MSAL UI required (caught MsalUiRequiredException) for ${msalAccount.username}. Cannot silently acquire/refresh token.",
                e
            )
            null
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Ktor: Generic exception during MSAL token acquisition for ${msalAccount.username}",
                e
            )
            null
        }
    }

    private fun findMsalAccount(accountId: String): IAccount? {
        return microsoftAuthManager.accounts.find { it.id == accountId }
    }
}