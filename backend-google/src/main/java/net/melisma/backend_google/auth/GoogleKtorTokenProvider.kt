package net.melisma.backend_google.auth

import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleKtorTokenProvider @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
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
        val accountId = activeAccountHolder.activeAccountId.value
        if (accountId == null) {
            Timber.tag(TAG).w("No active Google account ID found.")
            return null
        }

        Timber.tag(TAG)
            .d("Fetching fresh access token for account ID: $accountId via GoogleAuthManager.")
        when (val result = googleAuthManager.getFreshAccessToken(accountId)) {
            is GoogleGetTokenResult.Success -> {
                Timber.tag(TAG).i(
                    "Successfully fetched fresh access token for $accountId: ${
                        result.accessToken.take(10)
                    }..."
                )
                return BearerTokens(result.accessToken, "")
            }

            is GoogleGetTokenResult.Error -> {
                Timber.tag(TAG).e(
                    result.exception,
                    "Failed to get fresh access token for $accountId: ${result.errorMessage} (Type: ${result.errorType})"
                )
                return null
            }

            is GoogleGetTokenResult.NeedsReauthentication -> {
                Timber.tag(TAG).w(
                    "Getting fresh access token for $accountId resulted in NeedsReauthentication."
                )
                throw GoogleNeedsReauthenticationException(
                    accountId = result.accountId,
                    cause = null
                )
            }
        }
    }
}