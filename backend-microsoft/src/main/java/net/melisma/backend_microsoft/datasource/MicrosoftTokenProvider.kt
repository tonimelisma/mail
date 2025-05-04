package net.melisma.backend_microsoft.datasource

// <<< CHANGED IMPORTS START
// <<< CHANGED IMPORTS END
import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
        // It needs to look into the list provided by the injected MicrosoftAuthManager.
        val msalAccount = getMsalAccountById(account.id)
            ?: return Result.failure(IllegalStateException("MSAL IAccount not found for generic Account ID: ${account.id}. Known accounts: ${microsoftAuthManager.accounts.joinToString { it.id ?: "null" }}")) // Added debug info


        // Use CompletableDeferred to bridge the callback-based MSAL API with suspend functions
        // Consider refactoring to callbackFlow later for better testability.
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
                                    MsalUiRequiredException.NO_ACCOUNT_FOUND, // Example error code (might need adjustment based on actual cause)
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
                    // This case might not be typical for silent flow but handle defensively.
                    Log.w(TAG, "Silent token acquisition appears cancelled for ${account.username}")
                    resultDeferred.complete(Result.failure(CancellationException("Authentication cancelled (silent flow).")))
                }

                is AcquireTokenResult.NotInitialized -> {
                    Log.e(TAG, "MSAL not initialized during silent token acquisition.")
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.UNKNOWN_ERROR, // Or a more specific code if available
                                "MSAL not initialized."
                            )
                        )
                    )
                }

                is AcquireTokenResult.NoAccountProvided -> {
                    // This shouldn't happen here as we find and provide an account above.
                    Log.e(TAG, "Internal error: NoAccountProvided during silent token acquisition.")
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.INVALID_PARAMETER, // More specific error code
                                "Internal error: Account mapping failed before silent call."
                            )
                        )
                    )
                }
                // No 'else' needed for sealed class when all branches are handled
            }
        }
        // Wait for the deferred result (from either silent or interactive flow)
        return resultDeferred.await()
    }

    /**
     * Private helper function to handle the interactive token acquisition flow.
     * This is only called if silent acquisition fails with UiRequired and an Activity is present.
     */
    private fun acquireTokenInteractive(
        activity: Activity,
        msalAccount: IAccount,
        scopes: List<String>,
        resultDeferred: CompletableDeferred<Result<String>> // Pass the deferred to complete
    ) {
        microsoftAuthManager.acquireTokenInteractive(
            activity,
            msalAccount, // Provide the account for context, MSAL might use it as hint
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
                    // Use the specific MSAL exception for user cancellation.
                    resultDeferred.complete(Result.failure(MsalUserCancelException()))
                }

                // Handle other potential results defensively, although less likely for interactive.
                is AcquireTokenResult.NotInitialized,
                is AcquireTokenResult.NoAccountProvided, // Not expected here
                is AcquireTokenResult.UiRequired -> { // Not expected as result of interactive
                    Log.e(
                        TAG,
                        "Unexpected result during interactive token acquisition for ${msalAccount.username}: ${interactiveResult::class.simpleName}"
                    )
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.UNKNOWN_ERROR,
                                "Unexpected internal state during interactive auth result: ${interactiveResult::class.simpleName}"
                            )
                        )
                    )
                }
                // No 'else' needed for sealed class
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