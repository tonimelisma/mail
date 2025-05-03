package net.melisma.mail.data.datasources

// *** ADDED IMPORT ***
import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import net.melisma.feature_auth.AcquireTokenResult
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.Account
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of TokenProvider using MicrosoftAuthManager (MSAL).
 * Responsible for obtaining OAuth2 access tokens for Microsoft accounts.
 */
@Singleton
class MicrosoftTokenProvider @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager
) : TokenProvider {

    private val TAG = "MicrosoftTokenProvider"

    /**
     * Acquires an access token for the specified Microsoft account and scopes.
     * Attempts silent acquisition first, falling back to interactive flow if needed and an Activity is provided.
     */
    override suspend fun getAccessToken(
        account: Account,
        scopes: List<String>,
        activity: Activity?
    ): Result<String> {
        if (account.providerType != "MS") {
            return Result.failure(IllegalArgumentException("Account provider type is not MS: ${account.providerType}"))
        }
        val msalAccount = getMsalAccountById(account.id)
            ?: return Result.failure(IllegalStateException("MSAL IAccount not found for generic Account ID: ${account.id}"))

        val resultDeferred = CompletableDeferred<Result<String>>()

        microsoftAuthManager.acquireTokenSilent(msalAccount, scopes) { silentResult ->
            when (silentResult) {
                is AcquireTokenResult.Success -> {
                    Log.d(TAG, "Silent token acquisition successful for ${account.username}")
                    resultDeferred.complete(Result.success(silentResult.result.accessToken))
                }

                is AcquireTokenResult.UiRequired -> {
                    Log.w(
                        TAG,
                        "Silent token acquisition requires UI interaction for ${account.username}"
                    )
                    if (activity != null) {
                        acquireTokenInteractive(activity, msalAccount, scopes, resultDeferred)
                    } else {
                        Log.e(
                            TAG,
                            "Interactive token acquisition needed but Activity is null for ${account.username}"
                        )
                        resultDeferred.complete(
                            Result.failure(
                                MsalUiRequiredException(
                                    null,
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
                // Handle cancellation based on the Result type
                is AcquireTokenResult.Cancelled -> {
                    Log.w(TAG, "Silent token acquisition cancelled for ${account.username}")
                    // Return failure with a CancellationException or a specific custom exception/message
                    resultDeferred.complete(Result.failure(CancellationException("Authentication cancelled by user (silent flow).")))
                }

                is AcquireTokenResult.NotInitialized -> {
                    Log.w(TAG, "MSAL not initialized during silent token acquisition.")
                    // Use a relevant error code or message
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.INVALID_PARAMETER,
                                "MSAL not initialized."
                            )
                        )
                    ) // Example using INVALID_PARAMETER
                }

                is AcquireTokenResult.NoAccountProvided -> {
                    Log.e(TAG, "Internal error: NoAccountProvided during silent token acquisition.")
                    // Use the documented error code
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.NO_CURRENT_ACCOUNT,
                                "Internal error: Account mapping failed during silent flow."
                            )
                        )
                    )
                }
            }
        }
        return resultDeferred.await()
    }

    /**
     * Handles the interactive token acquisition flow using MSAL.
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
                // Handle cancellation based on the Result type
                is AcquireTokenResult.Cancelled -> {
                    Log.w(
                        TAG,
                        "Interactive token acquisition cancelled by user for ${msalAccount.username}"
                    )
                    // Return failure with a CancellationException or MsalUserCancelException
                    resultDeferred.complete(Result.failure(MsalUserCancelException())) // Use specific exception type
                }

                else -> {
                    Log.e(
                        TAG,
                        "Unexpected result during interactive token acquisition for ${msalAccount.username}: $interactiveResult"
                    )
                    resultDeferred.complete(
                        Result.failure(
                            MsalClientException(
                                MsalClientException.UNKNOWN_ERROR,
                                "Unexpected interactive authentication result."
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Finds the original MSAL IAccount based on the generic Account ID.
     */
    private fun getMsalAccountById(accountId: String?): IAccount? {
        if (accountId == null) return null
        return microsoftAuthManager.accounts.find { it.id == accountId }
    }

    /** Maps MSAL exceptions to user-friendly messages. */
    private fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        // Handle standard CancellationException from our logic above
        if (exception is CancellationException) {
            return exception.message ?: "Authentication cancelled."
        }
        if (exception !is MsalException) {
            return exception?.message ?: "An unknown error occurred."
        }
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        val code = exception.errorCode ?: "UNKNOWN"
        return when (exception) {
            // Check specific types first
            is MsalUserCancelException -> "Authentication cancelled."
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalClientException -> when (exception.errorCode) {
                MsalClientException.NO_CURRENT_ACCOUNT -> "Account not found or session invalid."
                // Add other specific MsalClientException codes here if needed.
                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication client error ($code)"
            }

            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error ($code)"

            else -> exception.message?.takeIf { it.isNotBlank() } ?: "Authentication failed ($code)"
        }
    }
}
