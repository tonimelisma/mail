package net.melisma.backend_google.auth

import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.AccountRepository
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleKtorTokenProvider @Inject constructor(
    private val tokenPersistenceService: GoogleTokenPersistenceService,
    private val appAuthHelperService: AppAuthHelperService,
    private val activeAccountHolder: ActiveGoogleAccountHolder,
    private val accountRepository: AccountRepository // Injected AccountRepository
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

        var stateAfterRefreshAttempt: AuthState? = null

        if (authState.needsTokenRefresh) {
            Timber.tag(TAG)
                .i("Access token needs refresh for account: $accountId. Attempting refresh.")
            val deferredRefreshResult = kotlinx.coroutines.CompletableDeferred<AuthState?>()

            appAuthHelperService.refreshAccessToken(authState) { newAccessToken, newIdToken, ex ->
                tokenProviderScope.launch {
                    if (ex != null) {
                        Timber.tag(TAG).e(ex, "Token refresh failed for account $accountId.")
                        if (ex is AuthorizationException && ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) {
                            if (ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code) {
                                Timber.tag(TAG)
                                    .e("INVALID_GRANT error during token refresh for $accountId. Clearing AuthState.")
                                tokenPersistenceService.clearTokens(
                                    accountId,
                                    removeAccount = false
                                )
                                // Signal re-authentication needed
                                accountRepository.markAccountForReauthentication(
                                    accountId,
                                    Account.PROVIDER_TYPE_GOOGLE
                                )
                            }
                        }
                        deferredRefreshResult.complete(null)
                        return@launch
                    }

                    if (newAccessToken != null) {
                        Timber.tag(TAG)
                            .i("Token refresh successful for account $accountId. New access token acquired.")
                        val updateSuccess =
                            tokenPersistenceService.updateAuthState(accountId, authState)
                        if (updateSuccess) {
                            Timber.tag(TAG)
                                .d("Successfully persisted updated AuthState after refresh for $accountId.")
                            deferredRefreshResult.complete(authState)
                        } else {
                            Timber.tag(TAG)
                                .e("Failed to persist updated AuthState after refresh for $accountId.")
                            deferredRefreshResult.complete(null)
                        }
                    } else {
                        Timber.tag(TAG)
                            .w("Token refresh did not yield a new access token for $accountId (but no exception was thrown).")
                        deferredRefreshResult.complete(null)
                    }
                }
            }
            stateAfterRefreshAttempt = deferredRefreshResult.await()

            if (stateAfterRefreshAttempt == null) {
                Timber.tag(TAG)
                    .e("Token refresh process failed or did not return an updated AuthState for $accountId. Cannot provide token.")
                return null
            }
            Timber.tag(TAG)
                .d("AuthState after refresh attempt for $accountId. Has access token: ${stateAfterRefreshAttempt.accessToken != null}")
        } else {
            Timber.tag(TAG).d("Token for $accountId does not need refresh.")
        }

        val finalAuthStateToUse = stateAfterRefreshAttempt ?: authState

        if (finalAuthStateToUse.accessToken == null) {
            Timber.tag(TAG)
                .e("No access token available in final AuthState for account $accountId after all checks. Initial state needs refresh: ${authState.needsTokenRefresh}, Refresh attempted: ${stateAfterRefreshAttempt != null}")
            return null
        }

        Timber.tag(TAG).i(
            "Providing BearerTokens for account $accountId. Access token: ${
                finalAuthStateToUse.accessToken?.take(10)
            }..., Refresh token present: ${finalAuthStateToUse.refreshToken != null}"
        )
        // Use accountId or empty string as placeholder for refreshToken, as per Rec. 1 and consistency
        val refreshTokenPlaceholder =
            "" // Explicitly an empty string for non-functional placeholder
        return BearerTokens(finalAuthStateToUse.accessToken!!, refreshTokenPlaceholder)
    }
}