package net.melisma.backend_google.auth

// Explicit AppAuth imports
// import net.openid.appauth.* // Remove wildcard import

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.auth0.android.jwt.DecodeException
import com.auth0.android.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.suspendCancellableCoroutine
import net.melisma.backend_google.di.UnauthenticatedGoogleHttpClient
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Define the data class for parsed ID token information
data class ParsedIdTokenInfo(
    val userId: String?,
    val email: String?,
    val displayName: String?,
    val picture: String? = null
)

@Singleton
class AppAuthHelperService @Inject constructor(
    private val context: Context,
    @UnauthenticatedGoogleHttpClient private val httpClient: HttpClient // Changed to use UnauthenticatedGoogleHttpClient
) {

    private val authService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    companion object {
        const val GOOGLE_AUTH_REQUEST_CODE = 1000
        const val GMAIL_API_SCOPE_BASE = "https://www.googleapis.com/auth/gmail."
        val GMAIL_SCOPES = listOf(
            "${GMAIL_API_SCOPE_BASE}readonly", // View your messages and settings
            "${GMAIL_API_SCOPE_BASE}modify",   // Modify but not delete messages (e.g., mark read/unread)
            "${GMAIL_API_SCOPE_BASE}labels",   // Manage your labels (folders)
            // Add other Gmail scopes as needed, e.g., send, metadata
        )
        val MANDATORY_SCOPES = listOf(
            "openid",
            "email",
            "profile",
            "offline_access" // For refresh token
        )

        // Simplified to 2-argument constructor for now
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"), // Authorization endpoint
            Uri.parse("https://oauth2.googleapis.com/token")          // Token endpoint
            // Revocation endpoint will need to be handled differently if this basic config works
        )
    }

    fun buildAuthorizationRequest(
        clientId: String,
        redirectUri: Uri,
        scopes: List<String>,
        loginHint: String? = null
    ): AuthorizationRequest {
        val combinedScopes = (MANDATORY_SCOPES + scopes).distinct().joinToString(" ")
        Timber.d(
            "Building AuthorizationRequest with Client ID: %s, Redirect URI: %s, Scopes: %s, LoginHint: %s",
            clientId,
            redirectUri,
            combinedScopes,
            loginHint ?: "N/A"
        )

        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE, // We want an authorization code
            redirectUri
        ).setScopes(combinedScopes)

        loginHint?.let {
            builder.setLoginHint(it)
        }
        // PKCE is handled automatically by AppAuth by default.

        return builder.build()
    }

    fun initiateAuthorizationRequest(
        activity: Activity,
        authRequest: AuthorizationRequest
    ) {
        Timber.d("Initiating authorization request.")
        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
        activity.startActivityForResult(authIntent, GOOGLE_AUTH_REQUEST_CODE)
    }

    /**
     * Creates an Intent for an authorization request. This can be used by the caller
     * to start the authorization flow, for example, by emitting it to UI components.
     */
    fun createAuthorizationRequestIntent(authRequest: AuthorizationRequest): Intent {
        Timber.d("Creating authorization request intent.")
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    suspend fun exchangeAuthorizationCode(
        authResponse: AuthorizationResponse,
        clientSecret: String? = null // Added clientSecret parameter for completeness, though often not used for Google with PKCE
    ): TokenResponse = suspendCancellableCoroutine { continuation ->
        Timber.d(
            "Suspending: Exchanging authorization code for tokens. Code: ${
                authResponse.authorizationCode?.take(
                    10
                )
            }..."
        )

        val additionalParametersFromSecret =
            clientSecret?.let { mapOf<String, String>("client_secret" to it) }
        val tokenRequest =
            authResponse.createTokenExchangeRequest(additionalParametersFromSecret ?: emptyMap())

        authService.performTokenRequest(tokenRequest) { response, ex ->
            if (continuation.isActive) { // Check if the coroutine is still active
                if (response != null) {
                    Timber.i("Resuming: Token exchange successful. AccessToken: ${response.accessToken?.isNotEmpty()}, IdToken: ${response.idToken?.isNotEmpty()}")
                    continuation.resume(response)
                } else {
                    Timber.e(ex, "Resuming: Token exchange failed.")
                    continuation.resumeWithException(
                        ex ?: AuthorizationException.GeneralErrors.SERVER_ERROR
                    )
                }
            } else {
                Timber.w(
                    "Coroutine no longer active when token response/exception received. Code: ${
                        authResponse.authorizationCode?.take(
                            10
                        )
                    }"
                )
            }
        }
        continuation.invokeOnCancellation {
            Timber.w(
                "Token exchange coroutine cancelled. Code: ${
                    authResponse.authorizationCode?.take(
                        10
                    )
                }"
            )
            // Optional: any cleanup specific to cancellation, though AppAuth might not have explicit cancellation for an in-flight request.
        }
    }

    fun performTokenRequest(
        authResponse: AuthorizationResponse,
        clientSecret: String? = null,
        callback: (TokenResponse?, AuthorizationException?) -> Unit
    ) {
        Timber.d("Performing token request for auth code: %s", authResponse.authorizationCode)
        val additionalParametersFromSecret =
            clientSecret?.let { mapOf<String, String>("client_secret" to it) }
        val tokenRequest =
            authResponse.createTokenExchangeRequest(additionalParametersFromSecret ?: emptyMap())
        authService.performTokenRequest(tokenRequest, callback)
    }

    fun parseIdToken(idTokenString: String): ParsedIdTokenInfo? {
        return try {
            Timber.d("Attempting to parse ID token.")
            val jwt = JWT(idTokenString)
            val userId = jwt.subject
            val email = jwt.getClaim("email").asString()
            val name = jwt.getClaim("name").asString()
            val picture = jwt.getClaim("picture").asString()
            Timber.i("Successfully parsed ID token. UserID: %s, Email: %s", userId, email)
            ParsedIdTokenInfo(userId, email, name, picture)
        } catch (e: DecodeException) {
            Timber.e(e, "Failed to decode ID token.")
            null
        } catch (e: Exception) {
            Timber.e(e, "An unexpected error occurred while parsing ID token.")
            null
        }
    }

    suspend fun refreshAccessToken(authState: AuthState): TokenResponse =
        suspendCancellableCoroutine { continuation ->
            Timber.d("Suspending: Attempting to refresh access token. Needs refresh: ${authState.needsTokenRefresh}")

            // Directly use NoClientAuthentication.INSTANCE for Google public client.
            val clientAuthentication: ClientAuthentication =
                net.openid.appauth.NoClientAuthentication.INSTANCE

            authState.performActionWithFreshTokens(
                authService,
                clientAuthentication,
                object : AuthState.AuthStateAction {
                    override fun execute(
                        accessToken: String?,
                        idToken: String?,
                        ex: AuthorizationException?
                    ) {
                        if (continuation.isActive) {
                            if (ex != null) {
                                Timber.e(ex, "Resuming: Token refresh failed.")
                                continuation.resumeWithException(ex)
                            } else {
                                val refreshRequest = authState.createTokenRefreshRequest(emptyMap())

                                val refreshedTokenResponse = TokenResponse.Builder(refreshRequest)
                                    .setAccessToken(accessToken)
                                    .setIdToken(idToken)
                                    .setRefreshToken(authState.refreshToken)
                                    .setAccessTokenExpirationTime(authState.accessTokenExpirationTime)
                                    .setTokenType(
                                        authState.lastTokenResponse?.tokenType
                                            ?: TokenResponse.TOKEN_TYPE_BEARER
                                    )
                                    .build()
                                Timber.i("Resuming: Token refresh successful. AccessToken: ${refreshedTokenResponse.accessToken?.isNotEmpty()}, IdToken: ${refreshedTokenResponse.idToken?.isNotEmpty()}")
                                continuation.resume(refreshedTokenResponse)
                            }
                        } else {
                            Timber.w("Token refresh coroutine no longer active when action executed.")
                        }
                    }
                })
            continuation.invokeOnCancellation {
                Timber.w("Token refresh coroutine cancelled.")
            }
        }

    fun dispose() {
        authService.dispose()
        Timber.d("AppAuthHelperService disposed.")
    }

    suspend fun revokeToken(refreshToken: String): Boolean {
        val googleRevocationEndpoint = "https://oauth2.googleapis.com/revoke"
        Timber.d(
            "Attempting to revoke refresh token: ${
                refreshToken.take(
                    10
                )
            }... via POST to $googleRevocationEndpoint"
        )

        return try {
            val response: HttpResponse = httpClient.submitForm(
                url = googleRevocationEndpoint,
                formParameters = Parameters.build {
                    append("token", refreshToken)
                }
            )

            if (response.status.isSuccess()) {
                Timber.i("Successfully revoked token (HTTP ${response.status.value}).")
                true
            } else {
                val errorBody = response.bodyAsText() // Corrected Ktor body access
                Timber.e(
                    "Failed to revoke token. HTTP Status: ${response.status.value}. Body: $errorBody"
                )
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during token revocation.")
            false
        }
    }
} 