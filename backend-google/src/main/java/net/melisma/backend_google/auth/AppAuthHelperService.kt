package net.melisma.backend_google.auth

// Explicit AppAuth imports
// import net.openid.appauth.* // Remove wildcard import

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.auth0.android.jwt.DecodeException
import com.auth0.android.jwt.JWT
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.suspendCancellableCoroutine
import net.melisma.backend_google.di.UnauthenticatedGoogleHttpClient
import net.melisma.backend_google.model.ParsedIdTokenInfo
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
// MOVED to backend-google/src/main/java/net/melisma/backend_google/model/ParsedIdTokenInfo.kt
/*
data class ParsedIdTokenInfo(
    val userId: String?,
    val email: String?,
    val displayName: String?,
    val picture: String? = null
)
*/

@Singleton
class AppAuthHelperService @Inject constructor(
    @ApplicationContext private val context: Context,
    @UnauthenticatedGoogleHttpClient private val httpClient: HttpClient
    // Assuming GOOGLE_ANDROID_CLIENT_ID and REDIRECT_URI_STRING are provided elsewhere (e.g., BuildConfig, Hilt module)
    // For this example, let's assume they are directly available or passed if this class is @Inject constructor
    // private val clientId: String, 
    // private val redirectUri: Uri 
) {
    private val TAG = "AppAuthHelperService"

    // Make this internal so GoogleTokenPersistenceService can access it for AuthState creation.
    internal val serviceConfiguration: AuthorizationServiceConfiguration by lazy {
        AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),  // authorization endpoint
            Uri.parse("https://oauth2.googleapis.com/token"),        // token endpoint
            null                                             // registration endpoint (null for Google)
        )
    }
    
    private val authService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    companion object {
        const val GOOGLE_AUTH_REQUEST_CODE = 1000
        // const val GMAIL_API_SCOPE_BASE = "https://www.googleapis.com/auth/gmail." // Inlined

        // Define all required scopes in a single list.
        // If the user does not grant all of these, authentication should be treated as failed.
        val RequiredScopes = listOf(
            "https://www.googleapis.com/auth/gmail.readonly", // View your messages and settings
            "https://www.googleapis.com/auth/gmail.modify",   // Modify but not delete messages
            "https://www.googleapis.com/auth/gmail.labels",   // Manage your labels
            // "https://www.googleapis.com/auth/gmail.send",    // If sending mail is needed
            // "https://www.googleapis.com/auth/gmail.metadata", // If only metadata access is needed for some features
            "openid",
            "email",
            "profile",
            "offline_access" // For refresh token
        ).distinct() // Ensure no duplicates if scopes are combined programmatically later

        // GMAIL_SCOPES and MANDATORY_SCOPES are now consolidated into RequiredScopes
        /*
        val GMAIL_SCOPES = listOf(
            "${GMAIL_API_SCOPE_BASE}readonly", 
            "${GMAIL_API_SCOPE_BASE}modify",   
            "${GMAIL_API_SCOPE_BASE}labels",   
        )
        val MANDATORY_SCOPES = listOf(
            "openid",
            "email",
            "profile",
            "offline_access" 
        )
        */
    }

    init {
        // Initialize any other necessary components
    }

    fun buildAuthorizationRequest(
        loginHint: String?,
        clientId: String,
        redirectUri: Uri  
    ): AuthorizationRequest {
        val builder = AuthorizationRequest.Builder(
            serviceConfiguration, 
            clientId,
            ResponseTypeValues.CODE, 
            redirectUri
        )

        val combinedScopes = RequiredScopes.joinToString(" ") // Use the consolidated RequiredScopes
        Timber.d(
            "Building AuthorizationRequest with Client ID: %s, Redirect URI: %s, Scopes: %s, LoginHint: %s",
            clientId,
            redirectUri,
            combinedScopes,
            loginHint ?: "N/A"
        )

        builder.setScopes(combinedScopes)

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
            Timber.tag(TAG)
                .d("refreshAccessToken - Input AuthState JSON: ${authState.jsonSerializeString()}")
            Timber.tag(TAG)
                .d("refreshAccessToken - Input AuthState Config JSON: ${authState.authorizationServiceConfiguration?.toJsonString()}")
            Timber.tag(TAG)
                .d("refreshAccessToken - Input AuthState Last Auth Resp Config JSON: ${authState.lastAuthorizationResponse?.request?.configuration?.toJsonString()}")
            Timber.tag(TAG)
                .d("refreshAccessToken - Input AuthState Refresh Token Present: ${authState.refreshToken != null}")

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