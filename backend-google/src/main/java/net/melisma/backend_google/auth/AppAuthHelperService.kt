package net.melisma.backend_google.auth

// Explicit AppAuth imports
// import net.openid.appauth.* // Remove wildcard import

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.auth0.android.jwt.DecodeException
import com.auth0.android.jwt.JWT
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
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
    private val context: Context
) {

    private var authService: AuthorizationService = AuthorizationService(context)

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
        scopes: List<String>
    ): AuthorizationRequest {
        val combinedScopes = (MANDATORY_SCOPES + scopes).distinct().joinToString(" ")
        Timber.d(
            "Building AuthorizationRequest with Client ID: %s, Redirect URI: %s, Scopes: %s",
            clientId,
            redirectUri,
            combinedScopes
        )

        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE, // We want an authorization code
            redirectUri
        ).setScopes(combinedScopes)
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

    fun refreshAccessToken(
        authState: AuthState,
        callback: (String?, String?, AuthorizationException?) -> Unit
    ) {
        Timber.d("Attempting to refresh access token.")
        if (authState.needsTokenRefresh) {
            authState.performActionWithFreshTokens(authService, callback)
        } else {
            Timber.d(
                "Token does not need refresh, using existing access token: %s",
                authState.accessToken
            )
            callback(authState.accessToken, authState.idToken, null)
        }
    }

    // This data class might be an issue if another class outside this package tries to refer to it
    // as AppAuthHelperService.GoogleTokenData. It's not used by the current version of
    // GoogleTokenPersistenceService or GoogleKtorTokenProvider after recent refactors.
    // Consider removing if truly unused.
    data class GoogleTokenData(
        val accessToken: String?,
        val refreshToken: String?,
        val idToken: String?,
        val accessTokenExpirationTime: Long?
    )

    fun dispose() {
        authService.dispose()
        Timber.d("AppAuthHelperService disposed.")
    }

    suspend fun revokeToken(refreshToken: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val googleRevocationEndpoint = "https://oauth2.googleapis.com/revoke"
            Timber.d(
                "Attempting to revoke refresh token: ${
                    refreshToken.take(
                        10
                    )
                }... via manual POST to $googleRevocationEndpoint"
            )

            // IMPORTANT: The AppAuth library does not provide a helper method for RFC 7009 token revocation.
            // This must be implemented by making a direct HTTP POST request to the revocation endpoint.
            // You will need an HTTP client (e.g., OkHttp, Ktor Client) for this.
            //
            // The request should be:
            // METHOD: POST
            // URL: https://oauth2.googleapis.com/revoke
            // HEADER: Content-Type: application/x-www-form-urlencoded
            // BODY: token=THE_REFRESH_TOKEN_STRING

            // Pseudocode for HTTP client usage:
            /*
            val httpClient = // ... obtain/configure your HTTP client
            val requestBodyString = "token=$refreshToken"
            // ... create appropriate request body for your client

            // ... build the POST request to googleRevocationEndpoint with the body and headers

            // ... execute the request asynchronously

            // httpClient.newCall(request).enqueue(object : Callback {
            //    override fun onResponse(call: Call, response: Response) {
            //        if (continuation.isActive) {
            //            if (response.isSuccessful) { // Typically 200 OK
            //                Timber.i("Token revocation request completed successfully (HTTP ${response.code()}).")
            //                continuation.resume(true)
            //            } else {
            //                Timber.e(response.body?.string(), "Token revocation request failed (HTTP ${response.code()}).")
            //                continuation.resume(false)
            //            }
            //        }
            //        response.close() // Ensure response is closed
            //    }
            //
            //    override fun onFailure(call: Call, e: IOException) {
            //        if (continuation.isActive) {
            //            Timber.e(e, "Token revocation HTTP call failed.")
            //            continuation.resumeWithException(e) // Or simply resume(false)
            //        }
            //    }
            // })
            //
            // continuation.invokeOnCancellation {
            //    Timber.w("Token revocation coroutine cancelled for token: ${refreshToken.take(10)}...")
            //    // If your HTTP client supports cancellation, trigger it here.
            // }
            */

            // For now, indicating non-implementation:
            Timber.e(
                "Token revocation for ${
                    refreshToken.take(
                        10
                    )
                } not fully implemented. Manual HTTP POST request logic is required."
            )
            if (continuation.isActive) {
                continuation.resume(false) // Placeholder: Revocation is not actually performed.
            }
    }
} 