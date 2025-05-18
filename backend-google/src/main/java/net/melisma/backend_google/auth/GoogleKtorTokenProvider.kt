package net.melisma.backend_google.auth

import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationException
import net.openid.appauth.TokenResponse
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleKtorTokenProvider @Inject constructor(
    private val tokenPersistenceService: GoogleTokenPersistenceService,
    private val appAuthHelperService: AppAuthHelperService,
    private val activeAccountHolder: ActiveGoogleAccountHolder
) {
    private val TAG = "GoogleKtorTokenProvider"
    private val mutex = Mutex()
    private val tokenProviderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Android OAuth Client ID from BuildConfig - Not directly used in this version of getBearerTokens
    // private val androidClientId: String =
    //     net.melisma.backend_google.BuildConfig.GOOGLE_ANDROID_CLIENT_ID
    // private val redirectUriForRefresh =
    //     Uri.parse("net.melisma.mail:/oauth2redirect") // Must match AppAuth config

    suspend fun getBearerTokens(): BearerTokens? = mutex.withLock {
        Timber.tag(TAG).d("Attempting to get BearerTokens.")
        val accountId = activeAccountHolder.getActiveAccountIdValue()
        if (accountId == null) {
            Timber.tag(TAG).w("No active Google account ID found.")
            return null
        }

        var authState = tokenPersistenceService.getAuthState(accountId)
        if (authState == null) {
            Timber.tag(TAG)
                .e("No AuthState found for account ID: $accountId. Cannot provide token.")
            return null
        }
        Timber.tag(TAG)
            .d("Initial AuthState loaded. Has access token: ${authState.accessToken != null}, Needs refresh: ${authState.needsTokenRefresh}, Account: $accountId")

        var authStateToUse = authState

        if (authState.needsTokenRefresh) {
            Timber.tag(TAG)
                .i("Access token needs refresh for account: $accountId. Attempting refresh.")
            try {
                val refreshedTokenResponse: TokenResponse =
                    appAuthHelperService.refreshAccessToken(authState)

                authState.update(refreshedTokenResponse, null)

                Timber.tag(TAG)
                    .i("Token refresh successful for account $accountId. New access token acquired.")

                val updateSuccess = tokenPersistenceService.updateAuthState(accountId, authState)
                if (updateSuccess) {
                    Timber.tag(TAG)
                        .d("Successfully persisted updated AuthState after refresh for $accountId.")
                    authStateToUse = authState
                } else {
                    Timber.tag(TAG)
                        .e("Failed to persist updated AuthState after refresh for $accountId.")
                    return null
                }
            } catch (ex: AuthorizationException) {
                Timber.tag(TAG).e(
                    ex,
                    "Token refresh failed for account $accountId with AuthorizationException."
                )
                if (ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
                    ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
                ) {
                    Timber.tag(TAG)
                        .e("INVALID_GRANT error during token refresh for $accountId. Clearing AuthState and signaling re-auth need.")
                    tokenPersistenceService.clearTokens(
                        accountId,
                        removeAccount = false
                    )
                    throw GoogleNeedsReauthenticationException(accountId = accountId, cause = ex)
                }
            } catch (ex: Exception) {
                Timber.tag(TAG)
                    .e(ex, "Token refresh failed for account $accountId with general Exception.")
                return null
            }
            Timber.tag(TAG)
                .d("AuthState after refresh attempt for $accountId. Has access token: ${authStateToUse.accessToken != null}")
        } else {
            Timber.tag(TAG).d("Token for $accountId does not need refresh.")
        }

        if (authStateToUse.accessToken == null) {
            Timber.tag(TAG)
                .e("No access token available in final AuthState for account $accountId after all checks. Initial state needs refresh: ${authState.needsTokenRefresh}")
            return null
        }

        Timber.tag(TAG).i(
            "Providing BearerTokens for account $accountId. Access token: ${
                authStateToUse.accessToken?.take(10)
            }..., Refresh token present: ${authStateToUse.refreshToken != null}"
        )
        val refreshTokenPlaceholder = ""
        return BearerTokens(authStateToUse.accessToken!!, refreshTokenPlaceholder)
    }
}