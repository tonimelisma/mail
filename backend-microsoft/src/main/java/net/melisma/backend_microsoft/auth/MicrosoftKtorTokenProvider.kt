package net.melisma.backend_microsoft.auth

// import android.util.Log // Timber is used
// import kotlinx.coroutines.CoroutineScope
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.SupervisorJob
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.sync.Mutex
import net.melisma.core_data.auth.NeedsReauthenticationException
import net.melisma.core_data.auth.TokenProvider
import net.melisma.core_data.auth.TokenProviderException
import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.AccountRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftKtorTokenProvider @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val activeAccountHolder: ActiveMicrosoftAccountHolder,
    private val accountRepository: AccountRepository // Used to mark account for re-authentication
) : TokenProvider {
    private val TAG = "MsKtorTokenProvider" // Shortened for clarity in logs
    private val refreshMutex = Mutex()

    // Removed local defaultGraphApiScopes, will use MicrosoftScopeDefinitions.AppRequiredScopes
    // private val defaultGraphApiScopes = listOf("https://graph.microsoft.com/.default")

    override suspend fun getBearerTokens(): BearerTokens? {
        Timber.tag(TAG).d("getBearerTokens() called")
        val accountId = activeAccountHolder.getActiveMicrosoftAccountIdValue()
        if (accountId == null) {
            Timber.tag(TAG).w("No active Microsoft account ID. Cannot load tokens.")
            return null
        }
        Timber.tag(TAG).d("Active Microsoft account ID: $accountId")

        val msalAccount = findMsalAccount(accountId)
        if (msalAccount == null) {
            Timber.tag(TAG)
                .e("MSAL IAccount not found for active ID: $accountId. This is unexpected.")
            // This could be due to a race condition (e.g. account removed right before this call)
            // Or an issue with account loading/persistence.
            // Returning null will likely cause the API call to fail.
            // Throwing an exception might be too aggressive if Ktor can't handle it gracefully.
            return null
        }
        Timber.tag(TAG).d("Found MSAL account: ${msalAccount.username}")
        return acquireTokenAndConvertToBearer(msalAccount, isRefreshAttempt = false)
    }

    override suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens? {
        Timber.tag(TAG).d("refreshBearerTokens() called.")
        // The accountId should be stored in oldTokens.refreshToken as per getBearerTokens logic
        val accountId = oldTokens?.refreshToken
        if (accountId == null) {
            Timber.tag(TAG).w("No accountId found in oldTokens for refresh. Cannot refresh tokens.")
            return null
        }
        Timber.tag(TAG).d("Attempting to refresh tokens for Microsoft account ID: $accountId")

        val msalAccount = findMsalAccount(accountId)
        if (msalAccount == null) {
            Timber.tag(TAG).e(
                "MSAL IAccount not found for active ID: $accountId during refresh. This is unexpected."
            )
            // Propagate the issue as a TokenProviderException or NeedsReauthenticationException
            throw NeedsReauthenticationException(
                accountIdToReauthenticate = accountId,
                message = "MSAL account not found for ID $accountId during token refresh."
            )
        }

        // Use the centralized AppRequiredScopes for refreshing tokens as well.
        return acquireTokenAndConvertToBearer(msalAccount, isRefreshAttempt = true)
    }

    private suspend fun acquireTokenAndConvertToBearer(
        msalAccount: IAccount,
        isRefreshAttempt: Boolean
    ): BearerTokens { // Changed to non-nullable, will throw exceptions on failure
        val operationType = if (isRefreshAttempt) "refresh" else "initial load"
        Timber.tag(TAG)
            .d("acquireTokenAndConvertToBearer for $operationType: account=${msalAccount.username}")

        try {
            // Use the centralized RequiredScopes from MicrosoftScopeDefinitions
            val acquireTokenResult: AcquireTokenResult =
                microsoftAuthManager.acquireTokenSilent(
                    msalAccount,
                    MicrosoftScopeDefinitions.RequiredScopes
                )
            Timber.tag(TAG)
                .d("MSAL acquireTokenSilent result: ${acquireTokenResult::class.simpleName}")

            when (acquireTokenResult) {
                is AcquireTokenResult.Success -> {
                    Timber.tag(TAG)
                        .i("MSAL Token acquired successfully for ${msalAccount.username}. Expires: ${acquireTokenResult.result.expiresOn}")

                    val msalAccountId = msalAccount.id ?: run {
                        Timber.tag(TAG)
                            .e("MSAL account ID (IAccount.id) is null after successful token acquisition. This should not happen.")
                        throw TokenProviderException("MSAL account ID is null post-success.")
                    }

                    return BearerTokens(
                        accessToken = acquireTokenResult.result.accessToken,
                        // Store the MSAL account ID in refreshToken field.
                        // This allows the Ktor `validate` block to retrieve it to fetch user details for Principal.
                        refreshToken = msalAccountId
                    )
                }
                is AcquireTokenResult.UiRequired -> {
                    Timber.tag(TAG)
                        .w("MSAL UI required for ${msalAccount.username} during token $operationType.")
                    msalAccount.id?.let { id ->
                        accountRepository.markAccountForReauthentication(
                            id,
                            Account.PROVIDER_TYPE_MS
                        )
                    }
                    throw NeedsReauthenticationException(
                        accountIdToReauthenticate = msalAccount.id,
                        message = "MSAL UI interaction required for account ${msalAccount.username}",
                        cause = (acquireTokenResult as? AcquireTokenResult.Error)?.exception
                            ?: MsalUiRequiredException(
                                "UiRequired",
                                "MSAL reported UI interaction needed."
                            )
                    )
                }
                is AcquireTokenResult.Error -> {
                    Timber.tag(TAG).e(
                        acquireTokenResult.exception,
                        "MSAL Error acquiring token for ${msalAccount.username}"
                    )
                    if (acquireTokenResult.exception is MsalUiRequiredException) {
                        msalAccount.id?.let { id ->
                            accountRepository.markAccountForReauthentication(
                                id,
                                Account.PROVIDER_TYPE_MS
                            )
                        }
                        throw NeedsReauthenticationException(
                            accountIdToReauthenticate = msalAccount.id,
                            message = "MSAL UI interaction required (wrapped in Error) for account ${msalAccount.username}",
                            cause = acquireTokenResult.exception
                        )
                    } else {
                        throw TokenProviderException(
                            message = "MSAL Error acquiring token for ${msalAccount.username}: ${acquireTokenResult.exception.message}",
                            cause = acquireTokenResult.exception
                        )
                    }
                }

                is AcquireTokenResult.Cancelled, is AcquireTokenResult.NotInitialized, is AcquireTokenResult.NoAccountProvided -> {
                    Timber.tag(TAG)
                        .w("Unexpected MSAL result ($acquireTokenResult) during token $operationType for ${msalAccount.username}")
                    throw TokenProviderException("Unexpected or non-successful MSAL result: $acquireTokenResult")
                }

                else -> {
                    Timber.tag(TAG)
                        .e("Unhandled MSAL acquireTokenSilent result: $acquireTokenResult")
                    throw TokenProviderException("Unhandled MSAL acquireTokenSilent result: ${acquireTokenResult::class.java.simpleName}")
                }
            }
        } catch (e: MsalUiRequiredException) { // Catch direct MsalUiRequiredException if not wrapped by our sealed class
            Timber.tag(TAG)
                .w(e, "Caught direct MsalUiRequiredException for ${msalAccount.username}")
            msalAccount.id?.let { id ->
                accountRepository.markAccountForReauthentication(id, Account.PROVIDER_TYPE_MS)
            }
            throw NeedsReauthenticationException(
                accountIdToReauthenticate = msalAccount.id,
                message = "MSAL UI interaction required (direct catch) for account ${msalAccount.username}",
                cause = e
            )
        } catch (e: NeedsReauthenticationException) {
            throw e // Re-throw if it's already the correct type from our when block
        } catch (e: TokenProviderException) {
            throw e // Re-throw if it's already the correct type
        } catch (e: Exception) { // Catch any other exceptions
            Timber.tag(TAG).e(
                e,
                "Generic exception during MSAL token $operationType for ${msalAccount.username}"
            )
            throw TokenProviderException(
                message = "Generic exception during token $operationType for ${msalAccount.username}: ${e.message}",
                cause = e
            )
        }
    }

    private suspend fun findMsalAccount(accountId: String): IAccount? {
        Timber.tag(TAG).d("findMsalAccount called for accountId: $accountId")
        val iAccount: IAccount? = microsoftAuthManager.getAccount(accountId)

        if (iAccount != null) {
            Timber.tag(TAG).d("Found MSAL account with username: ${iAccount.username}")
        } else {
            Timber.tag(TAG).w("No MSAL account found with ID: $accountId")
        }
        return iAccount
    }
}