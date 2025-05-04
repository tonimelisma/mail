package net.melisma.backend_microsoft.datasource

import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.model.Account
import net.melisma.feature_auth.AcquireTokenResult
import net.melisma.feature_auth.MicrosoftAuthManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [TokenProvider] using [MicrosoftAuthManager] (MSAL).
 * Responsible for obtaining OAuth2 access tokens for Microsoft accounts defined by the generic [Account] model.
 */
@Singleton
class MicrosoftTokenProvider @Inject constructor(
    // Inject the MicrosoftAuthManager provided by :feature-auth (via Hilt module in :app)
    private val microsoftAuthManager: MicrosoftAuthManager
) : TokenProvider { // Implement the interface from :core-data

    private val TAG = "MicrosoftTokenProvider"

    /**
     * Acquires an access token for the specified Microsoft account and scopes.
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
            ?: return Result.failure(IllegalStateException("MSAL IAccount not found for generic Account ID: ${account.id}"))

        // Use CompletableDeferred to bridge the callback-based MSAL API with suspend functions
        val resultDeferred = CompletableDeferred<Result<String>>()

        // Attempt silent token acquisition first
        microsoftAuthManager.acquireTokenSilent(msalAccount, scopes) { silentResult ->
            when (silentResult) {
                is AcquireTokenResult.Success -> {
                    Log.d(TAG, "Silent token acquisition successful for ${account.username}")
                    resultDeferred.complete(Result.success(silentResult.result.accessToken))
                }

                is AcquireTokenResult.UiRequired -> {
                    // Silent failed, try interactive if Activity is available
                    Log.w(
                        TAG,
                        "Silent token acquisition requires UI interaction for ${account.username}"
                    )
                    if (activity != null) {
                        acquireTokenInteractive(activity, msalAccount, scopes, resultDeferred)
                    } else {
                        // Cannot proceed without Activity for interactive flow
                        Log.e(
                            TAG,
                            "Interactive token acquisition needed but Activity is null for ${account.username}"
                        )
                        resultDeferred.complete(
                            Result.failure(
                                MsalUiRequiredException( // Return specific MSAL exception
                                    MsalUiRequiredException.NO_ACCOUNT_FOUND, // Example error code
                                    "UI interaction required, but no Activity provided."
                                )
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
                    resultDeferred.complete(Result.failure(silentResult.exception))
                }

                is AcquireTokenResult.Cancelled -> {
                    Log.w(TAG, "Silent token acquisition appears cancelled for ${account.username}")
                    resultDeferred.complete(Result.failure(CancellationException("Authentication cancelled by user (silent flow).")))
                }

                is AcquireTokenResult.NotInitialized -> {
                    Log.e(TAG, "MSAL not initialized during silent token acquisition.")
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.UNKNOWN_ERROR,
                                "MSAL not initialized."
                            )
                        )
                    )
                }

                is AcquireTokenResult.NoAccountProvided -> {
                    // This shouldn't happen here as we provide an account
                    Log.e(TAG, "Internal error: NoAccountProvided during silent token acquisition.")
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.UNKNOWN_ERROR,
                                "Internal error: Account mapping failed."
                            )
                        )
                    )
                }
            }
        }
        // Wait for the deferred result (from either silent or interactive flow)
        return resultDeferred.await()
    }

    /**
     * Private helper function to handle the interactive token acquisition flow.
     */
    private fun acquireTokenInteractive(
        activity: Activity,
        msalAccount: IAccount,
        scopes: List<String>,
        resultDeferred: CompletableDeferred<Result<String>>
    ) {
        microsoftAuthManager.acquireTokenInteractive(
            activity,
            msalAccount,
            scopes
        ) { interactiveResult ->
            when (interactiveResult) {
                is AcquireTokenResult.Success -> {
                    Log.d(
                        TAG,
                        "Interactive token acquisition successful for ${msalAccount.username}"
                    )
                    resultDeferred.complete(Result.success(interactiveResult.result.accessToken))
                }

                is AcquireTokenResult.Error -> {
                    Log.e(
                        TAG,
                        "Interactive token acquisition failed for ${msalAccount.username}",
                        interactiveResult.exception
                    )
                    resultDeferred.complete(Result.failure(interactiveResult.exception))
                }

                is AcquireTokenResult.Cancelled -> {
                    Log.w(
                        TAG,
                        "Interactive token acquisition cancelled by user for ${msalAccount.username}"
                    )
                    resultDeferred.complete(Result.failure(MsalUserCancelException())) // Use specific exception
                }

                else -> {
                    // Handle unexpected states from interactive flow if necessary
                    Log.e(
                        TAG,
                        "Unexpected result during interactive token acquisition for ${msalAccount.username}: $interactiveResult"
                    )
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.UNKNOWN_ERROR,
                                "Unexpected interactive auth result."
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Finds the original MSAL IAccount based on the generic Account ID by looking
     * into the list provided by MicrosoftAuthManager.
     *
     * @param accountId The ID from the generic [Account] model.
     * @return The corresponding [IAccount] object, or null if not found.
     */
    private fun getMsalAccountById(accountId: String?): IAccount? {
        if (accountId == null) return null
        // Access the accounts list from the injected auth manager
        return microsoftAuthManager.accounts.find { it.id == accountId }
    }

    // Error mapping is now centralized in ErrorMapper, remove local version if present.
}
