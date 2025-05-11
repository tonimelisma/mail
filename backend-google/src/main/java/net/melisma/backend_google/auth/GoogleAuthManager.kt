package net.melisma.backend_google.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.Account
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Sealed classes for results
sealed class GoogleSignInResult {
    data class Success(val idTokenCredential: GoogleIdTokenCredential) : GoogleSignInResult()
    data class Error(val exception: Exception, val message: String? = null) : GoogleSignInResult()
    object Cancelled : GoogleSignInResult()
    object NoCredentialsAvailable : GoogleSignInResult()
}

sealed class GoogleScopeAuthResult {
    data class Success(val accessToken: String, val grantedScopes: List<String>?) :
        GoogleScopeAuthResult()

    data class ConsentRequired(val pendingIntent: IntentSender) : GoogleScopeAuthResult()
    data class Error(val exception: Exception, val message: String? = null) :
        GoogleScopeAuthResult()
}

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "GoogleAuthManager"
    private val credentialManager: CredentialManager = CredentialManager.create(context)
    private val authorizationClient by lazy { Identity.getAuthorizationClient(context) }
    private val oneTapClient by lazy { Identity.getSignInClient(context) }

    private val SERVER_CLIENT_ID =
        "326576675855-6vc6rrjhijjfch6j6106sd5ui2htbh61.apps.googleusercontent.com"
    private var currentNonce: String? = null

    private fun generateNonce(): String {
        val rawNonce = UUID.randomUUID().toString()
        val sha256 = MessageDigest.getInstance("SHA-256")
        val hashedNonceBytes = sha256.digest(rawNonce.toByteArray())
        currentNonce = android.util.Base64.encodeToString(
            hashedNonceBytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        Log.d(TAG, "Generated Nonce: $currentNonce")
        return currentNonce!!
    }

    suspend fun signIn(
        activity: Activity,
        filterByAuthorizedAccounts: Boolean = true
    ): GoogleSignInResult {
        val nonce = generateNonce()
        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(SERVER_CLIENT_ID)
            .setNonce(nonce)
            .setAutoSelectEnabled(filterByAuthorizedAccounts)
            .build()

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val usedNonce = currentNonce // Capture for potential verification if needed

        return try {
            Log.d(TAG, "Requesting Google credential with nonce: $usedNonce")
            val result: GetCredentialResponse = credentialManager.getCredential(activity, request)
            val credential = result.credential
            Log.d(TAG, "Received credential type: ${credential.type}")
            currentNonce = null // Nonce is consumed for this attempt

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                try {
                    val googleIdTokenCredential =
                        GoogleIdTokenCredential.createFrom(credential.data)
                    Log.d(
                        TAG,
                        "Successfully parsed GoogleIdTokenCredential. ID: ${googleIdTokenCredential.id}"
                    )

                    if (googleIdTokenCredential.idToken.isBlank()) {
                        Log.e(TAG, "ID token is blank, which is a parsing issue.")
                        // Create a generic exception to hold our specific message
                        val causeDetail =
                            IllegalArgumentException("ID token string was blank or null.")
                        // Pass this as the cause to GoogleIdTokenParsingException
                        val ex = GoogleIdTokenParsingException(causeDetail)
                        // The user-facing message can be more general or include specifics
                        val msg: String? = "Failed to parse Google ID token: ID token was missing."
                        GoogleSignInResult.Error(ex, msg)
                    } else {
                        Log.i(
                            TAG,
                            "Google Sign-In Success. User ID: ${googleIdTokenCredential.id}, Display Name: ${googleIdTokenCredential.displayName}"
                        )
                        GoogleSignInResult.Success(googleIdTokenCredential)
                    }
                } catch (e: GoogleIdTokenParsingException) {
                    // This catch block is for when GoogleIdTokenCredential.createFrom() itself throws this exception
                    Log.e(TAG, "GoogleIdTokenParsingException during createFrom: ${e.message}", e)
                    GoogleSignInResult.Error(
                        e,
                        "Failed to parse Google ID token data: ${e.message}"
                    )
                } catch (e: Exception) {
                    // Catch other potential errors during parsing or handling of GoogleIdTokenCredential
                    Log.e(TAG, "Exception after CustomCredential check: ${e.message}", e)
                    GoogleSignInResult.Error(e, "Error processing Google credential: ${e.message}")
                }
            } else {
                Log.e(TAG, "Unexpected credential type received: ${credential.type}")
                GoogleSignInResult.Error(
                    IllegalStateException("Unexpected credential type: ${credential.type}"),
                    "Received an unexpected credential type."
                )
            }
        } catch (e: GetCredentialCancellationException) {
            currentNonce = null
            Log.d(TAG, "User cancelled Google Sign-In (GetCredentialCancellationException).", e)
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            currentNonce = null
            Log.d(TAG, "No Google credentials available for sign-in.", e)
            GoogleSignInResult.NoCredentialsAvailable
        } catch (e: GetCredentialException) {
            currentNonce = null
            Log.e(TAG, "Google Sign-In failed (GetCredentialException type: ${e.type})", e)
            GoogleSignInResult.Error(e, "Google Sign-In failed: ${e.message ?: e.type}")
        } catch (e: Exception) {
            currentNonce = null
            Log.e(TAG, "An unexpected error occurred during Google Sign-In", e)
            GoogleSignInResult.Error(e, "An unexpected error occurred: ${e.message}")
        }
    }

    suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Log.i(TAG, "CredentialManager state cleared for sign-out.")
        } catch (e: ClearCredentialException) {
            Log.e(TAG, "Error clearing CredentialManager state during sign-out", e)
            // Optionally, rethrow or handle more specifically if needed
        }
    }

    suspend fun requestAccessToken(
        activity: Activity,
        @Suppress("UNUSED_PARAMETER") accountEmail: String?, // Kept for potential future use with account hinting
        scopes: List<String>
    ): GoogleScopeAuthResult = withContext(ioDispatcher) {
        if (scopes.isEmpty()) {
            Log.w(TAG, "requestAccessToken called with empty scopes list.")
            return@withContext GoogleScopeAuthResult.Error(
                IllegalArgumentException("Scopes list cannot be empty."),
                "No scopes requested."
            )
        }

        val gmsScopes = scopes.map { Scope(it) }
        Log.d(TAG, "Requesting access token for scopes: $scopes")

        val authRequestBuilder = AuthorizationRequest.builder().setRequestedScopes(gmsScopes)
        val authorizationRequest = authRequestBuilder.build()

        try {
            val authResult: AuthorizationResult =
                authorizationClient.authorize(authorizationRequest).await()
            if (authResult.hasResolution()) {
                authResult.pendingIntent?.let {
                    Log.d(TAG, "Authorization consent required. Returning PendingIntent.")
                    return@withContext GoogleScopeAuthResult.ConsentRequired(it.intentSender)
                } ?: run {
                    Log.e(TAG, "AuthorizationResult has resolution but PendingIntent is null.")
                    return@withContext GoogleScopeAuthResult.Error(
                        IllegalStateException("PendingIntent missing for resolution."),
                        "Authorization consent UI could not be prepared."
                    )
                }
            } else {
                val accessToken = authResult.accessToken
                if (accessToken != null) {
                    Log.i(TAG, "Access token acquired successfully.")
                    return@withContext GoogleScopeAuthResult.Success(
                        accessToken,
                        authResult.grantedScopes
                    )
                } else {
                    Log.e(
                        TAG,
                        "Access token was null even though no resolution was needed. Granted scopes: ${authResult.grantedScopes}"
                    )
                    return@withContext GoogleScopeAuthResult.Error(
                        IllegalStateException("Access token is null after successful authorization without resolution."),
                        "Failed to retrieve access token despite no resolution needed."
                    )
                }
            }
        } catch (e: Exception) { // Catches ApiException from .await() among others
            Log.e(TAG, "Failed to authorize scopes or get access token", e)
            return@withContext GoogleScopeAuthResult.Error(
                e,
                "Failed to get access token: ${e.message}"
            )
        }
    }

    suspend fun handleScopeConsentResult(intent: Intent?): GoogleScopeAuthResult =
        withContext(ioDispatcher) {
            if (intent == null) {
                Log.w(TAG, "handleScopeConsentResult called with null intent.")
                return@withContext GoogleScopeAuthResult.Error(
                    IllegalArgumentException("Intent data is null."),
                    "Authorization intent data missing."
                )
            }
        try {
            val authorizationResult = authorizationClient.getAuthorizationResultFromIntent(intent)
            if (authorizationResult.hasResolution()) {
                Log.w(
                    TAG,
                    "AuthorizationResult still has resolution after consent intent. PendingIntent: ${authorizationResult.pendingIntent}"
                )
                return@withContext GoogleScopeAuthResult.Error(
                    IllegalStateException("Consent still required after intent completion."),
                    "Authorization not fully completed by the user."
                )
            }
            val accessToken = authorizationResult.accessToken
            if (accessToken != null) {
                Log.i(TAG, "Access token acquired successfully after consent.")
                return@withContext GoogleScopeAuthResult.Success(
                    accessToken,
                    authorizationResult.grantedScopes
                )
            } else {
                Log.e(
                    TAG,
                    "Access token is null after consent intent. Granted scopes: ${authorizationResult.grantedScopes}"
                )
                return@withContext GoogleScopeAuthResult.Error(
                    IllegalStateException("Access token is null after consent."),
                    "Failed to retrieve access token post-consent."
                )
            }
        } catch (e: ApiException) {
            Log.e(TAG, "ApiException while handling scope consent result: ${e.statusCode}", e)
            return@withContext GoogleScopeAuthResult.Error(
                e,
                "Failed to process consent: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while handling scope consent result", e)
            return@withContext GoogleScopeAuthResult.Error(
                e,
                "Unexpected error processing consent: ${e.message}"
            )
        }
    }

    fun toGenericAccount(idTokenCredential: GoogleIdTokenCredential): Account {
        return Account(
            id = idTokenCredential.id, // Google User ID (sub claim)
            username = idTokenCredential.displayName
                ?: "Google User (${idTokenCredential.id.take(6)}...)",
            providerType = "GOOGLE"
        )
    }
}
