package net.melisma.backend_google.auth

import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.melisma.core_data.auth.NeedsReauthenticationException
import net.melisma.core_data.auth.TokenProvider
import net.melisma.core_data.auth.TokenProviderException
import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.AccountRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleKtorTokenProvider @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
    private val activeAccountHolder: ActiveGoogleAccountHolder,
    private val accountRepository: AccountRepository,
    private val authEventBus: net.melisma.core_data.auth.AuthEventBus
) : TokenProvider {
    private val TAG = "GoogleKtorTokenProv"
    private val refreshMutex = Mutex()

    // Android OAuth Client ID from BuildConfig - Not directly used in this version of getBearerTokens
    // private val androidClientId: String =
    //     net.melisma.backend_google.BuildConfig.GOOGLE_ANDROID_CLIENT_ID
    // private val redirectUriForRefresh =
    //     Uri.parse("net.melisma.mail:/oauth2redirect") // Must match AppAuth config

    override suspend fun getBearerTokens(): BearerTokens? {
        Timber.tag(TAG).d("getBearerTokens() called")
        val accountId = activeAccountHolder.activeAccountId.value
        if (accountId == null) {
            Timber.tag(TAG).w("No active Google account ID. Cannot load tokens.")
            return null
        }
        Timber.tag(TAG).d("Active Google account ID: $accountId")

        return processGetFreshAccessToken(accountId, "getBearerTokens")
    }

    override suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens? {
        Timber.tag(TAG).d("refreshBearerTokens() called.")
        val accountIdFromActiveHolder = activeAccountHolder.activeAccountId.value
        val accountIdFromOldToken = oldTokens?.refreshToken

        val accountId = accountIdFromActiveHolder ?: accountIdFromOldToken

        if (accountId == null) {
            Timber.tag(TAG)
                .e("Refresh failed - No active Google account ID and no ID in oldTokens.")
            return null
        }
        Timber.tag(TAG).d("Google account ID for refresh: $accountId")

        // Using refreshMutex to prevent concurrent refresh attempts for the same account.
        return refreshMutex.withLock {
            Timber.tag(TAG).d("Acquired refresh mutex for account: $accountId")
            processGetFreshAccessToken(accountId, "refreshBearerTokens")
        }
    }

    private suspend fun processGetFreshAccessToken(
        accountId: String,
        operation: String
    ): BearerTokens? {
        Timber.tag(TAG).d("processGetFreshAccessToken for $operation: accountId=$accountId")
        try {
            // googleAuthManager.getFreshAccessToken is a suspend fun, not returning Flow.
            when (val tokenResult = googleAuthManager.getFreshAccessToken(accountId)) {
                is GoogleGetTokenResult.Success -> {
                    Timber.tag(TAG)
                        .i("Google Token acquired successfully for $accountId during $operation.")

                    // Notify observers that authentication is healthy for this account.
                    authEventBus.publish(
                        net.melisma.core_data.auth.AuthEvent.AuthSuccess(
                            accountId,
                            Account.PROVIDER_TYPE_GOOGLE
                        )
                    )

                    return BearerTokens(
                        accessToken = tokenResult.accessToken,
                        refreshToken = accountId // Store accountId in refreshToken
                    )
                }

                is GoogleGetTokenResult.NeedsReauthentication -> {
                    Timber.tag(TAG)
                        .w("Google account needs re-login for $accountId during $operation.")
                    try {
                        accountRepository.markAccountForReauthentication(
                            accountId,
                            Account.PROVIDER_TYPE_GOOGLE
                        )
                    } catch (e: Exception) {
                        Timber.tag(TAG)
                            .e(e, "Failed to mark account $accountId for re-authentication.")
                        // Continue to throw NeedsReauthenticationException
                    }
                    throw NeedsReauthenticationException(
                        accountIdToReauthenticate = accountId,
                        message = "Google account needs re-login for $accountId: (from $operation)",
                        // tokenResult might not have a direct throwable cause, NeedsReauthentication is a state.
                        // If tokenResult.error existed (it doesn't in GoogleGetTokenResult.NeedsReauthentication), it could be passed.
                        cause = null
                    )
                }

                is GoogleGetTokenResult.Error -> {
                    Timber.tag(TAG).e(
                        tokenResult.exception,
                        "Error getting fresh Google token for $accountId during $operation: ${tokenResult.errorMessage}"
                    )
                    throw TokenProviderException(
                        message = "Error getting fresh Google token for $accountId during $operation: ${tokenResult.errorMessage}",
                        cause = tokenResult.exception
                    )
                }
                // Potentially other states if GoogleGetTokenResult is expanded, though current definition covers main ones.
                // else -> { // Should not happen with current sealed class
                //    Timber.tag(TAG).e("Unknown GoogleGetTokenResult for $accountId during $operation: $tokenResult")
                //    throw TokenProviderException("Unknown result while fetching Google token for $accountId: ${tokenResult::class.java.simpleName}")
                // }
            }
        } catch (e: NeedsReauthenticationException) {
            Timber.tag(TAG).w(
                e,
                "Re-throwing NeedsReauthenticationException during $operation for $accountId."
            )
            throw e // Re-throw if it's already the correct type
        } catch (e: TokenProviderException) {
            Timber.tag(TAG)
                .w(e, "Re-throwing TokenProviderException during $operation for $accountId.")
            throw e // Re-throw
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Generic exception during $operation for $accountId")
            throw TokenProviderException(
                message = "Generic exception during $operation for Google token for $accountId: ${e.message}",
                cause = e
            )
        }
    }
}