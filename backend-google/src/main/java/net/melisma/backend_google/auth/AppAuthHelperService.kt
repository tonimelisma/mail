package net.melisma.backend_google.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Helper service that encapsulates AppAuth library interactions for Google OAuth.
 * Provides methods to create and manage the authorization flow.
 */
@Singleton
class AppAuthHelperService @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "AppAuthHelperService"

    // Google OAuth 2.0 endpoints
    companion object {
        private const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val GOOGLE_ISSUER_URL = "https://accounts.google.com"

        // Default scopes for Gmail API access
        const val GMAIL_SCOPES = "https://mail.google.com/ email profile openid"

        // Auth state tracking
        private const val STATE_IDLE = 0
        private const val STATE_AUTHORIZING = 1
        private const val STATE_AUTH_COMPLETED = 2
        private const val STATE_TOKEN_EXCHANGE_COMPLETED = 3
        private const val STATE_ERROR = 4
    }

    // Lazy initialization of AuthorizationService to avoid creating it until needed
    private val authService by lazy { AuthorizationService(context) }

    // Hold the configuration in an AtomicReference to allow updating it if needed
    private val serviceConfig = AtomicReference<AuthorizationServiceConfiguration?>(null)

    // State management for the authorization flow
    private val _authState = MutableStateFlow(STATE_IDLE)
    val authState: StateFlow<Int> = _authState

    // Last error message (if any)
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /**
     * Ensures the AuthorizationServiceConfiguration is initialized.
     * Will create it statically if not already available.
     */
    suspend fun getServiceConfiguration(): AuthorizationServiceConfiguration =
        withContext(ioDispatcher) {
            Log.d(TAG, "AppAuthHelperService: getServiceConfiguration() called")
            // Return existing config if available
            serviceConfig.get()?.let {
                Log.d(TAG, "AppAuthHelperService: Returning existing service configuration")
                return@withContext it
            }

            // Create static configuration
            Log.d(
                TAG,
                "AppAuthHelperService: Creating new static configuration for Google OAuth endpoints"
            )
            val config = AuthorizationServiceConfiguration(
                Uri.parse(GOOGLE_AUTH_ENDPOINT),
                Uri.parse(GOOGLE_TOKEN_ENDPOINT)
            )
            serviceConfig.set(config)

            Log.d(
                TAG,
                "AppAuthHelperService: Created static service configuration for Google OAuth endpoints"
            )
            return@withContext config
        }

    /**
     * Alternative method to discover and fetch configuration from Google's issuer URL.
     * This is an alternative to static configuration and requires network connectivity.
     */
    suspend fun fetchServiceConfigurationFromIssuer(): AuthorizationServiceConfiguration? =
        withContext(ioDispatcher) {
            Log.d(TAG, "AppAuthHelperService: fetchServiceConfigurationFromIssuer() called")
            try {
                val discoveryUri = Uri.parse(GOOGLE_ISSUER_URL)
                Log.d(
                    TAG,
                    "AppAuthHelperService: Fetching configuration from issuer URL: $GOOGLE_ISSUER_URL"
                )
                var fetchedConfig: AuthorizationServiceConfiguration? = null
                var fetchException: Exception? = null

                // Using a CountDownLatch would be better here, but keeping it simple
                val fetchCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

                AuthorizationServiceConfiguration.fetchFromIssuer(
                    discoveryUri
                ) { configuration, ex ->
                    if (configuration != null) {
                        fetchedConfig = configuration
                        serviceConfig.set(configuration)
                        Log.d(
                            TAG,
                            "AppAuthHelperService: Successfully fetched service configuration from issuer"
                        )
                    } else {
                        fetchException = ex
                        Log.e(
                            TAG,
                            "AppAuthHelperService: Error fetching service configuration from issuer",
                            ex
                        )
                    }
                    fetchCompleted.set(true)
                }

                // Wait for fetch to complete (would be better with structured concurrency)
                Log.d(TAG, "AppAuthHelperService: Waiting for configuration fetch to complete")
                while (!fetchCompleted.get()) {
                    kotlinx.coroutines.delay(50)
                }

                fetchException?.let {
                    Log.e(TAG, "AppAuthHelperService: Rethrowing exception from fetch", it)
                    throw it
                }
                Log.d(
                    TAG,
                    "AppAuthHelperService: Returning fetched configuration: ${fetchedConfig != null}"
                )
                return@withContext fetchedConfig
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "AppAuthHelperService: Exception during fetchServiceConfigurationFromIssuer",
                    e
                )
                return@withContext null
            }
        }

    /**
     * Builds an AuthorizationRequest for Google OAuth.
     *
     * @param clientId The Android OAuth client ID from Google Cloud Console
     * @param redirectUri The registered redirect URI for the Android client
     * @param scopes Space-separated list of OAuth scopes to request
     * @return AuthorizationRequest configured for Google OAuth
     */
    suspend fun buildAuthorizationRequest(
        clientId: String,
        redirectUri: Uri,
        scopes: String
    ): AuthorizationRequest = withContext(ioDispatcher) {
        Log.d(
            TAG,
            "AppAuthHelperService: buildAuthorizationRequest(clientId=$clientId, redirectUri=$redirectUri, scopes=$scopes)"
        )

        // Ensure we have a service configuration
        Log.d(TAG, "AppAuthHelperService: Getting service configuration")
        val serviceConfiguration = getServiceConfiguration()

        // Generate a code verifier and challenge for PKCE
        Log.d(TAG, "AppAuthHelperService: Generating code verifier for PKCE")
        val codeVerifier = CodeVerifierUtil.generateRandomCodeVerifier()
        val codeChallenge = CodeVerifierUtil.deriveCodeVerifierChallenge(codeVerifier)
        val codeChallengeMethod = CodeVerifierUtil.getCodeVerifierChallengeMethod()

        Log.d(
            TAG,
            "AppAuthHelperService: Building authorization request with PKCE (code challenge method: $codeChallengeMethod)"
        )
        // Build and return the authorization request with PKCE
        val request = AuthorizationRequest.Builder(
            serviceConfiguration,
            clientId,
            ResponseTypeValues.CODE,
            redirectUri
        )
            .setScope(scopes)
            .setCodeVerifier(
                codeVerifier,
                codeChallenge,
                codeChallengeMethod
            )
            .build()

        // Add detailed logging of the full authorization request URI and components
        Log.i(TAG, "Constructed AuthorizationRequest URI: ${request.toUri()}")
        // Log individual components for clarity as well
        Log.d(TAG, "AuthRequest ClientID: ${request.clientId}")
        Log.d(TAG, "AuthRequest Redirect URI: ${request.redirectUri}")
        Log.d(TAG, "AuthRequest Scope: ${request.scope}")
        Log.d(TAG, "AuthRequest ResponseType: ${request.responseType}")
        Log.d(TAG, "AuthRequest State: ${request.state}")
        Log.d(TAG, "AuthRequest CodeVerifier: ${request.codeVerifier} (Don't log in production)")
        Log.d(TAG, "AuthRequest CodeChallenge: ${request.codeVerifierChallenge}")
        Log.d(TAG, "AuthRequest CodeChallengeMethod: ${request.codeVerifierChallengeMethod}")

        Log.d(TAG, "AppAuthHelperService: Authorization request built successfully")
        return@withContext request
    }

    /**
     * Initiates the authorization request by launching the appropriate intent.
     *
     * @param activity The activity from which to launch the intent
     * @param clientId The Android OAuth client ID from Google Cloud Console
     * @param redirectUri The registered redirect URI for the Android client
     * @param scopes Space-separated list of OAuth scopes to request (defaults to GMAIL_SCOPES)
     * @return An intent that should be launched via startActivityForResult or an ActivityResultLauncher
     */
    suspend fun initiateAuthorizationRequest(
        activity: Activity,
        clientId: String,
        redirectUri: Uri,
        scopes: String = GMAIL_SCOPES
    ): Intent = withContext(ioDispatcher) {
        Log.d(
            TAG,
            "AppAuthHelperService: initiateAuthorizationRequest(clientId=$clientId, redirectUri=$redirectUri, scopes=$scopes)"
        )
        try {
            _authState.value = STATE_AUTHORIZING
            _lastError.value = null
            Log.d(TAG, "AppAuthHelperService: State changed to AUTHORIZING")

            // Build the authorization request
            Log.d(TAG, "AppAuthHelperService: Building authorization request")
            val authRequest = buildAuthorizationRequest(clientId, redirectUri, scopes)

            // Create a custom tabs intent builder for better user experience
            Log.d(TAG, "AppAuthHelperService: Creating custom tabs intent for authorization")
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()

            // Get the intent from AppAuth's authorization service
            Log.d(
                TAG,
                "AppAuthHelperService: Getting authorization request intent from auth service"
            )
            val authIntent = authService.getAuthorizationRequestIntent(
                authRequest,
                customTabsIntent
            )

            Log.d(TAG, "AppAuthHelperService: Authorization request intent created successfully")
            return@withContext authIntent
        } catch (e: Exception) {
            Log.e(TAG, "AppAuthHelperService: Error creating authorization request intent", e)
            _authState.value = STATE_ERROR
            _lastError.value = "Failed to create authorization request: ${e.message}"
            throw e
        }
    }

    /**
     * Processes the authorization response from the redirect.
     *
     * @param intent The intent data returned to the activity after authorization
     * @return AuthorizationResponse if successful, null if there was an error
     */
    fun handleAuthorizationResponse(intent: Intent): AuthorizationResponse? {
        Log.d(TAG, "AppAuthHelperService: handleAuthorizationResponse() called with intent")
        try {
            // Extract the authorization response from the intent
            Log.d(TAG, "AppAuthHelperService: Extracting authorization response from intent")
            val response = AuthorizationResponse.fromIntent(intent)
            val exception = net.openid.appauth.AuthorizationException.fromIntent(intent)

            if (response != null) {
                Log.d(
                    TAG,
                    "AppAuthHelperService: Successfully received authorization response, code: ${
                        response.authorizationCode?.take(8)
                    }..."
                )
                _authState.value = STATE_AUTH_COMPLETED
                Log.d(TAG, "AppAuthHelperService: State changed to AUTH_COMPLETED")
                return response
            } else if (exception != null) {
                Log.e(TAG, "AppAuthHelperService: Error in authorization response", exception)
                _authState.value = STATE_ERROR
                _lastError.value = "Authorization failed: ${exception.message}"
                Log.e(TAG, "AppAuthHelperService: State changed to ERROR: ${exception.message}")
            } else {
                Log.e(TAG, "AppAuthHelperService: No response or exception found in intent")
                _authState.value = STATE_ERROR
                _lastError.value = "No authorization response received"
                Log.e(TAG, "AppAuthHelperService: State changed to ERROR: No response received")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AppAuthHelperService: Exception processing authorization response", e)
            _authState.value = STATE_ERROR
            _lastError.value = "Failed to process authorization response: ${e.message}"
            Log.e(TAG, "AppAuthHelperService: State changed to ERROR: ${e.message}")
        }

        return null
    }

    /**
     * Exchanges an authorization code for tokens.
     *
     * @param authResponse The authorization response containing the code to exchange
     * @return TokenResponse containing access and refresh tokens
     */
    suspend fun performTokenRequest(
        authResponse: AuthorizationResponse
    ): TokenResponse = withContext(ioDispatcher) {
        Log.d(TAG, "AppAuthHelperService: performTokenRequest() called")
        try {
            Log.d(
                TAG,
                "AppAuthHelperService: Starting token exchange for authorization code: ${
                    authResponse.authorizationCode?.take(8)
                }..."
            )

            // Create a token request from the authorization response
            Log.d(TAG, "AppAuthHelperService: Creating token exchange request")
            // AppAuth automatically includes the code_verifier that pairs with the challenge
            val tokenRequest = authResponse.createTokenExchangeRequest()
            Log.d(
                TAG,
                "AppAuthHelperService: Token request created with grant type: ${tokenRequest.grantType}"
            )

            // For Google with PKCE, we use NoClientAuthentication since client secret isn't needed
            // Android/native apps should generally use PKCE instead of client secrets
            val clientAuth = NoClientAuthentication.INSTANCE
            Log.d(TAG, "AppAuthHelperService: Using NoClientAuthentication for token request")

            // Perform the token request using coroutines
            Log.d(TAG, "AppAuthHelperService: Performing token request")
            val tokenResponse = performTokenRequestSuspend(tokenRequest, clientAuth)

            Log.d(
                TAG,
                "AppAuthHelperService: Token exchange successful, received access token: ${
                    tokenResponse.accessToken?.take(8)
                }..."
            )
            Log.d(
                TAG,
                "AppAuthHelperService: Refresh token present: ${tokenResponse.refreshToken != null}"
            )
            Log.d(
                TAG,
                "AppAuthHelperService: Token expires in: ${tokenResponse.accessTokenExpirationTime}"
            )
            _authState.value = STATE_TOKEN_EXCHANGE_COMPLETED
            Log.d(TAG, "AppAuthHelperService: State changed to TOKEN_EXCHANGE_COMPLETED")

            return@withContext tokenResponse
        } catch (e: Exception) {
            Log.e(TAG, "AppAuthHelperService: Token exchange failed", e)
            _authState.value = STATE_ERROR
            _lastError.value = "Token exchange failed: ${e.message}"
            Log.e(TAG, "AppAuthHelperService: State changed to ERROR: ${e.message}")
            throw e
        }
    }

    /**
     * Creates a token refresh request and exchanges it for new tokens.
     *
     * @param refreshToken The refresh token to use
     * @param clientId The client ID associated with the token
     * @param redirectUri The redirect URI associated with the original authorization
     * @return TokenResponse containing new access (and possibly refresh) token
     */
    suspend fun refreshAccessToken(
        refreshToken: String,
        clientId: String,
        redirectUri: Uri
    ): TokenResponse = withContext(ioDispatcher) {
        Log.d(
            TAG,
            "AppAuthHelperService: refreshAccessToken(refreshToken=${refreshToken.take(8)}..., clientId=$clientId, redirectUri=$redirectUri)"
        )
        try {
            Log.d(TAG, "AppAuthHelperService: Starting token refresh")

            // Ensure we have a service configuration
            Log.d(TAG, "AppAuthHelperService: Getting service configuration for refresh")
            val serviceConfiguration = getServiceConfiguration()

            // Build the token request for refresh
            Log.d(TAG, "AppAuthHelperService: Building token request with grant_type=refresh_token")
            val tokenRequest = TokenRequest.Builder(
                serviceConfiguration,
                clientId
            )
                .setGrantType("refresh_token")
                .setRefreshToken(refreshToken)
                .setRedirectUri(redirectUri)  // Google often requires this even for refresh
                .build()

            // For Google with PKCE, we use NoClientAuthentication
            val clientAuth = NoClientAuthentication.INSTANCE
            Log.d(TAG, "AppAuthHelperService: Using NoClientAuthentication for refresh")

            // Perform the token request
            Log.d(TAG, "AppAuthHelperService: Performing token refresh request")
            val tokenResponse = performTokenRequestSuspend(tokenRequest, clientAuth)

            Log.d(
                TAG,
                "AppAuthHelperService: Token refresh successful, received new access token: ${
                    tokenResponse.accessToken?.take(8)
                }..."
            )
            Log.d(
                TAG,
                "AppAuthHelperService: New refresh token present: ${tokenResponse.refreshToken != null}"
            )
            Log.d(
                TAG,
                "AppAuthHelperService: New token expires in: ${tokenResponse.accessTokenExpirationTime}"
            )
            return@withContext tokenResponse
        } catch (e: Exception) {
            Log.e(TAG, "AppAuthHelperService: Token refresh failed", e)
            Log.e(TAG, "AppAuthHelperService: Refresh error: ${e.message}")
            throw e
        }
    }

    /**
     * Coroutine wrapper for AppAuth's performTokenRequest.
     * Converts callback-based API to suspend function.
     */
    private suspend fun performTokenRequestSuspend(
        request: TokenRequest,
        clientAuth: ClientAuthentication
    ): TokenResponse = suspendCancellableCoroutine { continuation ->
        Log.d(
            TAG,
            "AppAuthHelperService: performTokenRequestSuspend() called for grant type: ${request.grantType}"
        )

        authService.performTokenRequest(request, clientAuth) { response, exception ->
            Log.d(TAG, "AppAuthHelperService: Token request callback received")
            when {
                response != null -> {
                    Log.d(TAG, "AppAuthHelperService: Token request successful")
                    Log.d(
                        TAG,
                        "AppAuthHelperService: Received token type: ${response.tokenType}, access token: ${
                            response.accessToken?.take(8)
                        }..."
                    )
                    Log.d(
                        TAG,
                        "AppAuthHelperService: Refresh token present: ${response.refreshToken != null}, id token present: ${response.idToken != null}"
                    )
                    Log.d(
                        TAG,
                        "AppAuthHelperService: Token expires in: ${response.accessTokenExpirationTime}"
                    )
                    continuation.resume(response)
                }

                exception != null -> {
                    Log.e(TAG, "AppAuthHelperService: Token request error", exception)
                    Log.e(
                        TAG,
                        "AppAuthHelperService: Token error type: ${exception.type}, description: ${exception.errorDescription}"
                    )
                    continuation.resumeWithException(
                        exception
                    )
                }

                else -> {
                    Log.e(
                        TAG,
                        "AppAuthHelperService: Token request returned null response with no exception"
                    )
                    continuation.resumeWithException(
                        RuntimeException("Token request failed with no error information")
                    )
                }
            }
        }

        // If coroutine is cancelled, we need to clean up
        continuation.invokeOnCancellation {
            Log.d(TAG, "AppAuthHelperService: Token request cancelled")
        }
    }

    /**
     * Creates a data class to hold token information in a structured way.
     * This can be used to pass token data to storage services.
     *
     * @param tokenResponse The raw token response from AppAuth
     * @return A GoogleTokenData object containing structured token information
     */
    fun extractTokenData(tokenResponse: TokenResponse): GoogleTokenData {
        Log.d(
            TAG,
            "AppAuthHelperService: extractTokenData() called to create GoogleTokenData object"
        )
        val data = GoogleTokenData(
            accessToken = tokenResponse.accessToken.orEmpty(),
            refreshToken = tokenResponse.refreshToken,
            idToken = tokenResponse.idToken,
            tokenType = tokenResponse.tokenType.orEmpty(),
            scopes = tokenResponse.scope?.split(" ") ?: emptyList(),
            expiresIn = tokenResponse.accessTokenExpirationTime ?: 0
        )
        Log.d(
            TAG,
            "AppAuthHelperService: Created GoogleTokenData with access token: ${
                data.accessToken.take(8)
            }..., " +
                    "refresh token present: ${data.refreshToken != null}, expires in: ${data.expiresIn}"
        )
        return data
    }

    /**
     * Resets the auth state to idle.
     * Useful when you want to start a new authorization flow.
     */
    fun resetAuthState() {
        Log.d(TAG, "AppAuthHelperService: resetAuthState() called")
        _authState.value = STATE_IDLE
        _lastError.value = null
        Log.d(TAG, "AppAuthHelperService: State reset to IDLE")
    }

    /**
     * Clean up resources when the service is no longer needed.
     * Should be called in onDestroy() of any Activity or Fragment using this service,
     * or when the app is shutting down.
     */
    fun dispose() {
        Log.d(TAG, "AppAuthHelperService: dispose() called")
        authService.dispose()
        Log.d(TAG, "AppAuthHelperService: AuthorizationService disposed")
    }

    /**
     * Provides access to the AuthorizationService instance.
     * This allows direct interaction with the AppAuth library when needed.
     */
    fun getAuthorizationService(): AuthorizationService {
        Log.d(TAG, "AppAuthHelperService: getAuthorizationService() called")
        return authService
    }

    /**
     * Data class to hold token information in a structured way.
     */
    data class GoogleTokenData(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        val tokenType: String,
        val scopes: List<String>,
        val expiresIn: Long
    ) {
        val isValid: Boolean
            get() = accessToken.isNotBlank()

        fun hasRefreshToken(): Boolean = !refreshToken.isNullOrBlank()
    }
}