package net.melisma.backend_microsoft.datasource

import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import net.melisma.backend_microsoft.auth.AcquireTokenResult
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.model.Account
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [TokenProvider] using [MicrosoftAuthManager] (MSAL).
 * Responsible for obtaining OAuth2 access tokens for Microsoft accounts defined by the generic [Account] model.
 */
@Singleton
class MicrosoftTokenProvider @Inject constructor(
    // Inject the MicrosoftAuthManager (provider needs update later)
    private val microsoftAuthManager: MicrosoftAuthManager
) : TokenProvider { // Implement the interface from :core-data

    private val TAG = "MicrosoftTokenProvider"

    /**
     * Acquires an access token for the specified Microsoft account and scopes.
     * Uses Flow to collect token results.
     * Attempts silent acquisition first, falling back to interactive flow if needed and an Activity is provided.
     * Ensures the provided account is of type "MS".
     * Maps underlying MSAL results/exceptions to a standard [Result<String>].
     */
    override suspend fun getAccessToken(
        account: Account,
        scopes: List<String>,
        activity: Activity?
    ): Result<String> {
        // Ensure this provider only handles Microsoft accounts
        if (account.providerType != "MS") {
            Log.e(TAG, "Attempted to get MS token for non-MS account type: ${account.providerType}")
            return Result.failure(IllegalArgumentException("Account provider type is not MS: ${account.providerType}"))
        }

        // Find the corresponding MSAL IAccount object using the generic Account ID
        val msalAccount = getMsalAccountById(account.id)
            ?: return Result.failure(IllegalStateException("MSAL IAccount not found for generic Account ID: ${account.id}. Known accounts: ${microsoftAuthManager.accounts.joinToString { it.id ?: "null" }}"))

        // First try to acquire token silently using the Flow API
        // The first() terminal operator will wait for the flow to emit one item
        val silentResult = microsoftAuthManager.acquireTokenSilent(msalAccount, scopes).first()

        return when (silentResult) {
            is AcquireTokenResult.Success -> {
                Log.d(TAG, "Silent token acquisition successful for ${account.username}")
                Result.success(silentResult.result.accessToken)
            }

            is AcquireTokenResult.UiRequired -> {
                // Silent failed, try interactive if Activity is available
                Log.w(
                    TAG,
                    "Silent token acquisition requires UI interaction for ${account.username}"
                )

                if (activity != null) {
                    // Use the interactive flow API
                    val interactiveResult = microsoftAuthManager.acquireTokenInteractive(
                        activity,
                        msalAccount,
                        scopes
                    ).first() // Wait for the first result

                    when (interactiveResult) {
                        is AcquireTokenResult.Success -> {
                            Log.d(
                                TAG,
                                "Interactive token acquisition successful for ${msalAccount.username}"
                            )
                            Result.success(interactiveResult.result.accessToken)
                        }

                        is AcquireTokenResult.Error -> {
                            Log.e(
                                TAG,
                                "Interactive token acquisition failed for ${msalAccount.username}",
                                interactiveResult.exception
                            )
                            Result.failure(interactiveResult.exception)
                        }

                        is AcquireTokenResult.Cancelled -> {
                            Log.w(
                                TAG,
                                "Interactive token acquisition cancelled by user for ${msalAccount.username}"
                            )
                            Result.failure(MsalUserCancelException())
                        }

                        // Unexpected results from interactive flow
                        is AcquireTokenResult.NotInitialized,
                        is AcquireTokenResult.NoAccountProvided,
                        is AcquireTokenResult.UiRequired -> {
                            Log.e(
                                TAG,
                                "Unexpected result during interactive token acquisition: ${interactiveResult::class.simpleName}"
                            )
                            Result.failure(
                                MsalClientException(
                                    MsalClientException.UNKNOWN_ERROR,
                                    "Unexpected internal state during interactive auth flow: ${interactiveResult::class.simpleName}"
                                )
                            )
                        }
                    }
                } else {
                    // Cannot proceed without Activity for interactive flow
                    Log.e(
                        TAG,
                        "Interactive token acquisition needed but Activity is null for ${account.username}"
                    )
                    Result.failure(
                        MsalUiRequiredException(
                            MsalUiRequiredException.NO_ACCOUNT_FOUND,
                            "UI interaction required, but no Activity provided."
                        )
                    )
                }
            }

            is AcquireTokenResult.Error -> {
                Log.e(
                    TAG,
                    "Silent token acquisition failed for ${account.username}",
                    silentResult.exception
                )
                Result.failure(silentResult.exception)
            }

            is AcquireTokenResult.Cancelled -> {
                Log.w(TAG, "Silent token acquisition appears cancelled for ${account.username}")
                Result.failure(CancellationException("Authentication cancelled (silent flow)."))
            }

            is AcquireTokenResult.NotInitialized -> {
                Log.e(TAG, "MSAL not initialized during silent token acquisition.")
                Result.failure(
                    MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "MSAL not initialized."
                    )
                )
            }

            is AcquireTokenResult.NoAccountProvided -> {
                // This shouldn't happen here as we find and provide an account above.
                Log.e(TAG, "Internal error: NoAccountProvided during silent token acquisition.")
                Result.failure(
                    MsalClientException(
                        MsalClientException.INVALID_PARAMETER,
                        "Internal error: Account mapping failed before silent call."
                    )
                )
            }
        }
    }

    /**
     * Finds the original MSAL IAccount based on the generic Account ID by looking
     * into the list provided by the injected MicrosoftAuthManager.
     *
     * @param accountId The ID from the generic [Account] model.
     * @return The corresponding [IAccount] object, or null if not found.
     */
    private fun getMsalAccountById(accountId: String?): IAccount? {
        if (accountId == null) return null
        // Access the accounts list from the injected auth manager
        return microsoftAuthManager.accounts.find { it.id == accountId }
    }

    // Note: Error mapping (if any specific logic was here) should be handled by the injected ErrorMapper if needed.
}