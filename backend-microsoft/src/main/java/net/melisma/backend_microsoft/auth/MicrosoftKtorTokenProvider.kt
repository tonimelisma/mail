package net.melisma.backend_microsoft.auth

import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftKtorTokenProvider @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val activeAccountHolder: ActiveMicrosoftAccountHolder,
    private val accountRepository: AccountRepository
) {
    private val TAG = "MicrosoftKtorTokenProv"
    private val refreshMutex = Mutex()

    // Define default scopes needed for Graph API calls by GraphApiHelper
    private val defaultGraphApiScopes =
        listOf("https://graph.microsoft.com/.default") // Or more specific like "User.Read", "Mail.ReadWrite" etc.

    suspend fun loadBearerTokens(): BearerTokens? {
        Log.d(TAG, "MicrosoftKtorTokenProvider: loadBearerTokens() called")
        val accountId = activeAccountHolder.getActiveMicrosoftAccountIdValue()
        if (accountId == null) {
            Log.w(
                TAG,
                "MicrosoftKtorTokenProvider: No active Microsoft account ID for loading tokens"
            )
            return null
        }
        Log.d(TAG, "MicrosoftKtorTokenProvider: Active account ID: $accountId")

        Log.d(TAG, "MicrosoftKtorTokenProvider: Finding MSAL account for ID: $accountId")
        val msalAccount = findMsalAccount(accountId)
        if (msalAccount == null) {
            Log.e(
                TAG,
                "MicrosoftKtorTokenProvider: MSAL IAccount not found for active ID: $accountId"
            )
            return null
        }
        Log.d(TAG, "MicrosoftKtorTokenProvider: Found MSAL account: ${msalAccount.username}")

        Log.d(
            TAG,
            "MicrosoftKtorTokenProvider: Attempting to acquire token silently for account: ${msalAccount.username}"
        )
        return acquireTokenAndConvertToBearer(msalAccount, isRefreshAttempt = false)
    }

    suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens? {
        Log.d(TAG, "MicrosoftKtorTokenProvider: refreshBearerTokens() called by Ktor Auth plugin")
        val accountId = activeAccountHolder.getActiveMicrosoftAccountIdValue()
        if (accountId == null) {
            Log.e(
                TAG,
                "MicrosoftKtorTokenProvider: Refresh failed - No active Microsoft account ID"
            )
            return null
        }
        Log.d(TAG, "MicrosoftKtorTokenProvider: Active account ID for refresh: $accountId")

        Log.d(TAG, "MicrosoftKtorTokenProvider: Finding MSAL account for refresh")
        val msalAccount = findMsalAccount(accountId)
        if (msalAccount == null) {
            Log.e(
                TAG,
                "MicrosoftKtorTokenProvider: MSAL IAccount not found for active ID: $accountId during refresh"
            )
            return null
        }
        Log.d(
            TAG,
            "MicrosoftKtorTokenProvider: Found MSAL account for refresh: ${msalAccount.username}"
        )

        Log.d(
            TAG,
            "MicrosoftKtorTokenProvider: Attempting token refresh for MS account: ${msalAccount.username}"
        )
        // Use mutex to prevent multiple concurrent silent refresh calls for the same account
        return refreshMutex.withLock {
            Log.d(
                TAG,
                "MicrosoftKtorTokenProvider: Acquired refresh mutex for account: ${msalAccount.username}"
            )
            // Optional: Double-check if token is still invalid or was refreshed by another call
            // This is complex with MSAL as it manages its own cache. A simple re-fetch is often easiest.
            Log.d(
                TAG,
                "MicrosoftKtorTokenProvider: Calling acquireTokenAndConvertToBearer with isRefreshAttempt=true"
            )
            acquireTokenAndConvertToBearer(msalAccount, isRefreshAttempt = true)
        }
    }

    private suspend fun acquireTokenAndConvertToBearer(
        msalAccount: IAccount,
        isRefreshAttempt: Boolean
    ): BearerTokens? {
        Log.d(
            TAG,
            "MicrosoftKtorTokenProvider: acquireTokenAndConvertToBearer(account=${msalAccount.username}, isRefreshAttempt=$isRefreshAttempt)"
        )
        return try {
            Log.d(
                TAG,
                "MicrosoftKtorTokenProvider: Calling microsoftAuthManager.acquireTokenSilent with scopes: $defaultGraphApiScopes"
            )
            val acquireTokenResult =
                microsoftAuthManager.acquireTokenSilent(msalAccount, defaultGraphApiScopes).first()
            Log.d(
                TAG,
                "MicrosoftKtorTokenProvider: Received result: ${acquireTokenResult::class.java.simpleName}"
            )

            when (acquireTokenResult) {
                is AcquireTokenResult.Success -> {
                    val expiresOn = acquireTokenResult.result.expiresOn
                    Log.i(
                        TAG,
                        "MicrosoftKtorTokenProvider: MSAL Token acquired successfully for ${msalAccount.username}. " +
                                "Expires: $expiresOn"
                    )
                    Log.d(
                        TAG,
                        "MicrosoftKtorTokenProvider: Token length: ${acquireTokenResult.result.accessToken.length} chars"
                    )

                    // The 'refreshToken' field in BearerTokens is not directly used by MSAL for its own refresh.
                    // MSAL uses its internal refresh token when acquireTokenSilent is called.
                    // We can put the account ID or a placeholder if needed by Ktor, or an empty string.
                    val accountIdOrPlaceholder = msalAccount.id ?: "ms_account_id_placeholder"
                    Log.d(
                        TAG,
                        "MicrosoftKtorTokenProvider: Creating BearerTokens with account ID: $accountIdOrPlaceholder"
                    )

                    BearerTokens(
                        acquireTokenResult.result.accessToken,
                        accountIdOrPlaceholder
                    )
                }

                is AcquireTokenResult.UiRequired -> {
                    Log.w(
                        TAG,
                        "MicrosoftKtorTokenProvider: MSAL UI required for ${msalAccount.username}. " +
                                "Cannot silently acquire/refresh token."
                    )
                    // Signal re-authentication needed
                    msalAccount.id?.let { accountId ->
                        accountRepository.markAccountForReauthentication(
                            accountId,
                            Account.PROVIDER_TYPE_MS
                        )
                    } ?: Log.e(TAG, "Cannot mark for re-auth, MSAL account ID is null.")
                    null
                }

                is AcquireTokenResult.Error -> {
                    Log.e(
                        TAG,
                        "MicrosoftKtorTokenProvider: MSAL Error acquiring token for ${msalAccount.username}",
                        acquireTokenResult.exception
                    )
                    // Check if it's an MsalUiRequiredException wrapped in Error result
                    if (acquireTokenResult.exception is MsalUiRequiredException) {
                        Log.w(
                            TAG,
                            "MSAL Error was MsalUiRequiredException for ${msalAccount.username}"
                        )
                        msalAccount.id?.let { accountId ->
                            accountRepository.markAccountForReauthentication(
                                accountId,
                                Account.PROVIDER_TYPE_MS
                            )
                        } ?: Log.e(TAG, "Cannot mark for re-auth, MSAL account ID is null.")
                    }
                    Log.e(
                        TAG,
                        "MicrosoftKtorTokenProvider: Error details: ${acquireTokenResult.exception.message}"
                    )
                    null
                }

                else -> { // Cancelled, NotInitialized, NoAccountProvided (shouldn't happen if msalAccount is valid)
                    Log.w(
                        TAG,
                        "MicrosoftKtorTokenProvider: Unexpected MSAL result during token acquisition for ${msalAccount.username}: $acquireTokenResult"
                    )
                    null
                }
            }
        } catch (e: MsalUiRequiredException) { // Catch specifically if flow.first() rethrows it
            Log.w(
                TAG,
                "MicrosoftKtorTokenProvider: MSAL UI required (caught MsalUiRequiredException) for ${msalAccount.username}. " +
                        "Cannot silently acquire/refresh token.",
                e
            )
            // Signal re-authentication needed
            msalAccount.id?.let { accountId ->
                accountRepository.markAccountForReauthentication(
                    accountId,
                    Account.PROVIDER_TYPE_MS
                )
            } ?: Log.e(
                TAG,
                "Cannot mark for re-auth from MsalUiRequiredException, MSAL account ID is null."
            )
            Log.w(TAG, "MicrosoftKtorTokenProvider: Error details: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(
                TAG,
                "MicrosoftKtorTokenProvider: Generic exception during MSAL token acquisition for ${msalAccount.username}",
                e
            )
            Log.e(
                TAG,
                "MicrosoftKtorTokenProvider: Error type: ${e.javaClass.simpleName}, Message: ${e.message}"
            )
            null
        }
    }

    private suspend fun findMsalAccount(accountId: String): IAccount? {
        Log.d(
            TAG,
            "MicrosoftKtorTokenProvider: findMsalAccount (new suspend version) called for accountId: $accountId"
        )
        val account = try {
            microsoftAuthManager.getAccountById(accountId).firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Exception while calling microsoftAuthManager.getAccountById($accountId)", e)
            null
        }

        if (account != null) {
            Log.d(
                TAG,
                "MicrosoftKtorTokenProvider: Found MSAL account with username: ${account.username} using new Flow method."
            )
        } else {
            Log.w(
                TAG,
                "MicrosoftKtorTokenProvider: No MSAL account found with ID: $accountId using new Flow method."
            )
        }
        return account
    }
}