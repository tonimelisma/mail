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
        val accountId = activeAccountHolder.getActiveAccountIdValue()
        if (accountId == null) {
            Log.w(TAG, "Ktor: No active Google account ID set for loading tokens.")
            return null
        }

        val storedTokens = tokenPersistenceService.getTokens(accountId)
        return if (storedTokens?.accessToken?.isNotBlank() == true) {
            // Proactive check for expiry (optional, Ktor will also trigger refresh on 401)
            val fiveMinutesInMillis = 5 * 60 * 1000
            if (storedTokens.expiresIn != 0L && storedTokens.expiresIn < (System.currentTimeMillis() + fiveMinutesInMillis)) {
                Log.i(
                    TAG,
                    "Ktor: Access token for $accountId is expiring soon or expired. Attempting refresh before use."
                )
                return refreshBearerTokensInternal(accountId, storedTokens.refreshToken)
            }
            Log.d(TAG, "Ktor: Loaded valid access token for account $accountId.")
            BearerTokens(
                storedTokens.accessToken,
                storedTokens.refreshToken ?: ""
            ) // Ensure refresh token is not null for BearerTokens constructor
        } else {
            Log.w(TAG, "Ktor: No valid access token found in persistence for account $accountId.")
            null
        }
    }

    // This is the function Ktor's Auth plugin will call
    suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens? {
        val accountId = activeAccountHolder.getActiveAccountIdValue()
        if (accountId == null) {
            Log.e(TAG, "Ktor Refresh: No active Google account ID.")
            return null
        }
        // Prefer refresh token from Ktor's context if available, otherwise fetch from persistence
        val refreshToken = oldTokens?.refreshToken?.takeIf { it.isNotBlank() }
            ?: tokenPersistenceService.getTokens(accountId)?.refreshToken

        return refreshBearerTokensInternal(accountId, refreshToken)
    }

    private suspend fun refreshBearerTokensInternal(
        accountId: String,
        refreshToken: String?
    ): BearerTokens? {
        if (refreshToken.isNullOrBlank()) {
            Log.e(TAG, "Ktor Refresh Internal: No refresh token available for account $accountId.")
            tokenPersistenceService.clearTokens(
                accountId,
                removeAccount = false
            ) // Clear potentially invalid state
            return null
        }

        Log.d(TAG, "Ktor Refresh Internal: Attempting for account $accountId.")
        return refreshMutex.withLock {
            // Double-check if another request already refreshed the token while waiting for mutex
            val latestPersistedTokens = tokenPersistenceService.getTokens(accountId)
            val fiveMinutesInMillis = 5 * 60 * 1000
            if (latestPersistedTokens?.accessToken?.isNotBlank() == true &&
                (latestPersistedTokens.expiresIn == 0L || latestPersistedTokens.expiresIn > (System.currentTimeMillis() + fiveMinutesInMillis))
            ) {
                // If token is fresh and different from what might have triggered this, use it
                if (latestPersistedTokens.refreshToken?.isNotBlank() == true) {
                    Log.i(
                        TAG,
                        "Ktor Refresh Internal: Tokens seem to have been refreshed by another process for $accountId. Using new ones."
                    )
                    return@withLock BearerTokens(
                        latestPersistedTokens.accessToken,
                        latestPersistedTokens.refreshToken
                    )
                }
            }

            try {
                val refreshedTokenResponse = appAuthHelperService.refreshAccessToken(
                    refreshToken = refreshToken,
                    clientId = androidClientId,
                    redirectUri = redirectUriForRefresh
                )

                // Persist the newly refreshed tokens (AppAuth might return a new refresh token)
                val currentAccountInfo = tokenPersistenceService.getAccountInfo(accountId)
                tokenPersistenceService.saveTokens(
                    accountId,
                    refreshedTokenResponse,
                    email = currentAccountInfo["email"], // Preserve existing details
                    displayName = currentAccountInfo["displayName"]
                )

                val newAccessToken = refreshedTokenResponse.accessToken
                val newRefreshToken = refreshedTokenResponse.refreshToken
                    ?: refreshToken // Use new if provided, else old

                if (newAccessToken.isNullOrBlank()) {
                    Log.e(
                        TAG,
                        "Ktor Refresh Internal: Refreshed access token is blank for $accountId."
                    )
                    return@withLock null
                }
                Log.i(TAG, "Ktor Refresh Internal: Tokens refreshed successfully for $accountId.")
                BearerTokens(newAccessToken, newRefreshToken)
            } catch (e: Exception) {
                Log.e(TAG, "Ktor Refresh Internal: Failed for $accountId.", e)
                if (e.message?.contains("invalid_grant", ignoreCase = true) == true) {
                    Log.e(
                        TAG,
                        "Ktor Refresh Internal: 'invalid_grant' detected. Clearing tokens for $accountId to force re-auth."
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