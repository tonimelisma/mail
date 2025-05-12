package net.melisma.backend_google.auth

import android.net.Uri
import android.util.Log
import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleKtorTokenProvider @Inject constructor(
    private val tokenPersistenceService: GoogleTokenPersistenceService,
    private val appAuthHelperService: AppAuthHelperService,
    private val activeAccountHolder: ActiveGoogleAccountHolder // To know which account's tokens
) {
    private val TAG = "GoogleKtorTokenProv"
    private val refreshMutex = Mutex()

    // Android OAuth Client ID from BuildConfig
    private val androidClientId: String =
        net.melisma.backend_google.BuildConfig.GOOGLE_ANDROID_CLIENT_ID
    private val redirectUriForRefresh =
        Uri.parse("net.melisma.mail:/oauth2redirect") // Must match AppAuth config

    suspend fun loadBearerTokens(): BearerTokens? {
        Log.d(TAG, "GoogleKtorTokenProvider: loadBearerTokens() called")
        val accountId = activeAccountHolder.getActiveAccountIdValue()
        if (accountId == null) {
            Log.w(
                TAG,
                "GoogleKtorTokenProvider: No active Google account ID set for loading tokens."
            )
            return null
        }
        Log.d(TAG, "GoogleKtorTokenProvider: Active account ID: $accountId")

        Log.d(TAG, "GoogleKtorTokenProvider: Getting stored tokens from persistence service")
        val storedTokens = tokenPersistenceService.getTokens(accountId)

        if (storedTokens?.accessToken?.isNotBlank() == true) {
            Log.d(TAG, "GoogleKtorTokenProvider: Found valid access token in storage")

            // Proactive check for expiry (optional, Ktor will also trigger refresh on 401)
            val fiveMinutesInMillis = 5 * 60 * 1000
            val currentTime = System.currentTimeMillis()

            if (storedTokens.expiresIn != 0L && storedTokens.expiresIn < (currentTime + fiveMinutesInMillis)) {
                Log.i(
                    TAG,
                    "GoogleKtorTokenProvider: Access token for $accountId is expiring soon or expired. " +
                            "Current time: $currentTime, Expiry: ${storedTokens.expiresIn}, " +
                            "Expires in: ${storedTokens.expiresIn - currentTime}ms. Attempting refresh before use."
                )
                return refreshBearerTokensInternal(accountId, storedTokens.refreshToken)
            }

            Log.d(
                TAG, "GoogleKtorTokenProvider: Loaded valid access token for account $accountId. " +
                        "Expires in: ${storedTokens.expiresIn - System.currentTimeMillis()}ms"
            )

            val bearerTokens = BearerTokens(
                storedTokens.accessToken,
                storedTokens.refreshToken ?: ""
            ) // Ensure refresh token is not null for BearerTokens constructor

            Log.d(
                TAG,
                "GoogleKtorTokenProvider: Returning BearerTokens with access token: ${
                    storedTokens.accessToken.take(8)
                }..., " +
                        "refresh token present: ${storedTokens.refreshToken != null}"
            )

            return bearerTokens
        } else {
            Log.w(
                TAG,
                "GoogleKtorTokenProvider: No valid access token found in persistence for account $accountId."
            )
            return null
        }
    }

    // This is the function Ktor's Auth plugin will call
    suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens? {
        Log.d(TAG, "GoogleKtorTokenProvider: refreshBearerTokens() called by Ktor Auth plugin")
        val accountId = activeAccountHolder.getActiveAccountIdValue()
        if (accountId == null) {
            Log.e(TAG, "GoogleKtorTokenProvider: Ktor Refresh: No active Google account ID.")
            return null
        }
        Log.d(TAG, "GoogleKtorTokenProvider: Refreshing tokens for account ID: $accountId")

        // Prefer refresh token from Ktor's context if available, otherwise fetch from persistence
        val oldRefreshToken = oldTokens?.refreshToken
        Log.d(
            TAG,
            "GoogleKtorTokenProvider: Refresh token from Ktor context present: ${oldRefreshToken?.isNotBlank() == true}"
        )

        val refreshToken = oldRefreshToken?.takeIf { it.isNotBlank() }
            ?: run {
                Log.d(
                    TAG,
                    "GoogleKtorTokenProvider: No refresh token in Ktor context, fetching from persistence service"
                )
                tokenPersistenceService.getTokens(accountId)?.refreshToken
            }

        Log.d(TAG, "GoogleKtorTokenProvider: Final refresh token present: ${refreshToken != null}")
        return refreshBearerTokensInternal(accountId, refreshToken)
    }

    private suspend fun refreshBearerTokensInternal(
        accountId: String,
        refreshToken: String?
    ): BearerTokens? {
        Log.d(
            TAG,
            "GoogleKtorTokenProvider: refreshBearerTokensInternal(accountId=$accountId, refreshToken=${
                refreshToken?.take(8)
            }...)"
        )

        if (refreshToken.isNullOrBlank()) {
            Log.e(
                TAG,
                "GoogleKtorTokenProvider: No refresh token available for account $accountId."
            )
            Log.d(
                TAG,
                "GoogleKtorTokenProvider: Clearing tokens for account to clean up invalid state"
            )
            tokenPersistenceService.clearTokens(
                accountId,
                removeAccount = false
            ) // Clear potentially invalid state
            return null
        }

        Log.d(TAG, "GoogleKtorTokenProvider: Attempting token refresh for account $accountId")
        return refreshMutex.withLock {
            Log.d(TAG, "GoogleKtorTokenProvider: Acquired refresh mutex for account $accountId")

            // Double-check if another request already refreshed the token while waiting for mutex
            Log.d(
                TAG,
                "GoogleKtorTokenProvider: Double-checking if token was refreshed while waiting for mutex"
            )
            val latestPersistedTokens = tokenPersistenceService.getTokens(accountId)
            val fiveMinutesInMillis = 5 * 60 * 1000
            val currentTime = System.currentTimeMillis()

            if (latestPersistedTokens?.accessToken?.isNotBlank() == true &&
                (latestPersistedTokens.expiresIn == 0L || latestPersistedTokens.expiresIn > (currentTime + fiveMinutesInMillis))
            ) {
                // If token is fresh and different from what might have triggered this, use it
                if (latestPersistedTokens.refreshToken?.isNotBlank() == true) {
                    Log.i(
                        TAG,
                        "GoogleKtorTokenProvider: Tokens seem to have been refreshed by another process for $accountId." +
                                " Current time: $currentTime, Token expiry: ${latestPersistedTokens.expiresIn}" +
                                " (${latestPersistedTokens.expiresIn - currentTime}ms until expiry). Using these fresh tokens."
                    )
                    return@withLock BearerTokens(
                        latestPersistedTokens.accessToken,
                        latestPersistedTokens.refreshToken
                    )
                }
            } else {
                Log.d(
                    TAG,
                    "GoogleKtorTokenProvider: No fresh tokens found, proceeding with refresh"
                )
            }

            try {
                Log.d(TAG, "GoogleKtorTokenProvider: Calling AppAuthHelperService to refresh token")
                val refreshedTokenResponse = appAuthHelperService.refreshAccessToken(
                    refreshToken = refreshToken,
                    clientId = androidClientId,
                    redirectUri = redirectUriForRefresh
                )
                Log.d(
                    TAG,
                    "GoogleKtorTokenProvider: Token refresh successful, got new access token"
                )

                // Persist the newly refreshed tokens (AppAuth might return a new refresh token)
                Log.d(
                    TAG,
                    "GoogleKtorTokenProvider: Getting account info before persisting new tokens"
                )
                val currentAccountInfo = tokenPersistenceService.getAccountInfo(accountId)
                Log.d(TAG, "GoogleKtorTokenProvider: Persisting refreshed tokens to storage")
                tokenPersistenceService.saveTokens(
                    accountId,
                    refreshedTokenResponse,
                    email = currentAccountInfo["email"], // Preserve existing details
                    displayName = currentAccountInfo["displayName"]
                )

                val newAccessToken = refreshedTokenResponse.accessToken
                val newRefreshToken = refreshedTokenResponse.refreshToken
                    ?: refreshToken // Use new if provided, else old

                Log.d(
                    TAG,
                    "GoogleKtorTokenProvider: New access token: ${newAccessToken?.take(8)}..., " +
                            "new refresh token provided: ${refreshedTokenResponse.refreshToken != null}"
                )

                if (newAccessToken.isNullOrBlank()) {
                    Log.e(
                        TAG,
                        "GoogleKtorTokenProvider: Refreshed access token is blank for $accountId, cannot use it."
                    )
                    return@withLock null
                }
                Log.i(TAG, "GoogleKtorTokenProvider: Tokens refreshed successfully for $accountId")
                Log.d(TAG, "GoogleKtorTokenProvider: Creating BearerTokens with new tokens")
                BearerTokens(newAccessToken, newRefreshToken)
            } catch (e: Exception) {
                Log.e(TAG, "GoogleKtorTokenProvider: Token refresh failed for $accountId", e)
                Log.e(TAG, "GoogleKtorTokenProvider: Error details: ${e.message}")

                if (e.message?.contains("invalid_grant", ignoreCase = true) == true) {
                    Log.e(
                        TAG,
                        "GoogleKtorTokenProvider: 'invalid_grant' detected. Clearing tokens for $accountId to force re-auth."
                    )
                    tokenPersistenceService.clearTokens(
                        accountId,
                        removeAccount = true
                    ) // Remove account if refresh token is invalid
                }
                null
            }
        }
    }
}