// File: backend-google/src/main/java/net/melisma/backend_google/auth/GoogleAuthManager.kt
package net.melisma.backend_google.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
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
    data class Success(
        val accessToken: String,
        val grantedScopes: List<String>?
    ) : // Changed to List<String>
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
        "326576675855-6vc6rrjhijjfch6j6106sd5ui2htbh61.apps.googleusercontent.com" // Your WEB Client ID for server-side verification
    private var currentNonce: String? = null

    private fun generateNonce(): String {
        Log.d(TAG, "GoogleAuthManager: generateNonce() called")
        val rawNonce = UUID.randomUUID().toString()
        val sha256 = MessageDigest.getInstance("SHA-256")
        val hashedNonceBytes = sha256.digest(rawNonce.toByteArray())
        currentNonce = android.util.Base64.encodeToString(
            hashedNonceBytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        Log.d(TAG, "GoogleAuthManager: Generated Nonce: $currentNonce")
        return currentNonce!!
    }

    suspend fun signInWithGoogle(activity: Activity): GoogleSignInResult {
        Log.d(
            TAG,
            "GoogleAuthManager: signInWithGoogle() called - forcing account chooser for explicit add."
        )
        return try {
            // Always attempt sign-in with account chooser visible for explicit "Add Account"
            // filterByAuthorizedAccounts = false ensures all accounts are shown in chooser
            // autoSelectEnabled = false ensures chooser is shown even if only one account exists
            attemptSignIn(activity, filterByAuthorizedAccounts = false, autoSelectEnabled = false)
        } catch (e: Exception) {
            currentNonce = null
            Log.e(TAG, "GoogleAuthManager: An unexpected error occurred during Google Sign-In", e)
            GoogleSignInResult.Error(e, "An unexpected error occurred: ${e.message}")
        }
    }

    private suspend fun attemptSignIn(
        activity: Activity,
        filterByAuthorizedAccounts: Boolean,
        autoSelectEnabled: Boolean
    ): GoogleSignInResult {
        Log.d(
            TAG,
            "GoogleAuthManager: attemptSignIn(filterByAuthorizedAccounts=$filterByAuthorizedAccounts, autoSelectEnabled=$autoSelectEnabled)"
        )
        val nonce = generateNonce()
        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(SERVER_CLIENT_ID)
            .setNonce(nonce)
            .setAutoSelectEnabled(autoSelectEnabled)
            .build()

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val usedNonce = currentNonce

        return try {
            Log.d(TAG, "GoogleAuthManager: Requesting Google credential with nonce: $usedNonce")
            val result: GetCredentialResponse = credentialManager.getCredential(activity, request)
            val credential = result.credential
            Log.d(TAG, "GoogleAuthManager: Received credential type: ${credential.type}")
            currentNonce = null

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                try {
                    Log.d(
                        TAG,
                        "GoogleAuthManager: Credential is CustomCredential with GoogleIdToken type, parsing now"
                    )
                    val googleIdTokenCredential =
                        GoogleIdTokenCredential.createFrom(credential.data)
                    Log.d(
                        TAG,
                        "GoogleAuthManager: Successfully parsed GoogleIdTokenCredential. ID: ${googleIdTokenCredential.id}"
                    )

                    if (googleIdTokenCredential.idToken.isBlank()) {
                        Log.e(
                            TAG,
                            "GoogleAuthManager: ID token is blank, which is a parsing issue."
                        )
                        val causeDetail =
                            IllegalArgumentException("ID token string was blank or null.")
                        val ex = GoogleIdTokenParsingException(causeDetail)
                        val msg: String? = "Failed to parse Google ID token: ID token was missing."
                        GoogleSignInResult.Error(ex, msg)
                    } else {
                        Log.i(
                            TAG,
                            "GoogleAuthManager: Google Sign-In Success. User ID: ${googleIdTokenCredential.id}, Display Name: ${googleIdTokenCredential.displayName}"
                        )
                        GoogleSignInResult.Success(googleIdTokenCredential)
                    }
                } catch (e: GoogleIdTokenParsingException) {
                    Log.e(
                        TAG,
                        "GoogleAuthManager: GoogleIdTokenParsingException during createFrom: ${e.message}",
                        e
                    )
                    GoogleSignInResult.Error(
                        e,
                        "Failed to parse Google ID token data: ${e.message}"
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "GoogleAuthManager: Exception after CustomCredential check: ${e.message}",
                        e
                    )
                    GoogleSignInResult.Error(e, "Error processing Google credential: ${e.message}")
                }
            } else {
                Log.e(
                    TAG,
                    "GoogleAuthManager: Unexpected credential type received: ${credential.type}"
                )
                GoogleSignInResult.Error(
                    IllegalStateException("Unexpected credential type: ${credential.type}"),
                    "Received an unexpected credential type."
                )
            }
        } catch (e: GetCredentialCancellationException) {
            currentNonce = null
            Log.d(
                TAG,
                "GoogleAuthManager: User cancelled Google Sign-In (GetCredentialCancellationException).",
                e
            )
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            currentNonce = null
            Log.d(TAG, "GoogleAuthManager: No Google credentials available for sign-in.", e)
            GoogleSignInResult.NoCredentialsAvailable
        } catch (e: GetCredentialException) {
            currentNonce = null
            Log.e(
                TAG,
                "GoogleAuthManager: Google Sign-In failed (GetCredentialException type: ${e.type})",
                e
            )
            GoogleSignInResult.Error(e, "Google Sign-In failed: ${e.message ?: e.type}")
        } catch (e: Exception) {
            currentNonce = null
            Log.e(TAG, "GoogleAuthManager: An unexpected error occurred during Google Sign-In", e)
            GoogleSignInResult.Error(e, "An unexpected error occurred: ${e.message}")
        }
    }

    @Deprecated("Use signInWithGoogle() instead", ReplaceWith("signInWithGoogle(activity)"))
    suspend fun signIn(
        activity: Activity,
        filterByAuthorizedAccounts: Boolean = true
    ): GoogleSignInResult {
        return signInWithGoogle(activity)
    }

    suspend fun signOut() {
        Log.d(
            TAG,
            "GoogleAuthManager: signOut() called. The step to clear credential state has been removed."
        )
        // try {
        // credentialManager.clearCredentialState(ClearCredentialStateRequest())
        // Log.i(TAG, "GoogleAuthManager: clearCredentialState call completed successfully (from CredentialManager's perspective).")
        // } catch (e: ClearCredentialException) {
        // Log.e(
        // TAG,
        // "GoogleAuthManager: ClearCredentialException during signOut",
        // e
        // )
        // Optionally rethrow or handle as a specific sign-out failure
        // } catch (e: Exception) {
        // Log.e(
        // TAG,
        // "GoogleAuthManager: Generic Exception during signOut (after ClearCredentialException check)",
        // e
        // )
        // Potentially an unexpected issue, decide if this indicates sign-out failure
        // }
        Log.d(TAG, "GoogleAuthManager: signOut() finished.")
    }

    suspend fun requestAccessToken(
        activity: Activity,
        @Suppress("UNUSED_PARAMETER") accountEmail: String?,
        scopes: List<String>
    ): GoogleScopeAuthResult = withContext(ioDispatcher) {
        Log.d(
            TAG,
            "GoogleAuthManager: requestAccessToken(accountEmail=$accountEmail, scopes=$scopes)"
        )
        if (scopes.isEmpty()) {
            Log.w(TAG, "GoogleAuthManager: requestAccessToken called with empty scopes list.")
            return@withContext GoogleScopeAuthResult.Error(
                IllegalArgumentException("Scopes list cannot be empty."),
                "No scopes requested."
            )
        }

        val gmsScopes = scopes.map { Scope(it) }
        Log.d(TAG, "GoogleAuthManager: Requesting access token for scopes: $scopes")

        val authRequestBuilder = AuthorizationRequest.builder().setRequestedScopes(gmsScopes)
        val authorizationRequest = authRequestBuilder.build()

        try {
            Log.d(TAG, "GoogleAuthManager: Calling authorizationClient.authorize()")
            val authResult: AuthorizationResult =
                authorizationClient.authorize(authorizationRequest).await()

            if (authResult.hasResolution()) {
                authResult.pendingIntent?.let {
                    Log.d(
                        TAG,
                        "GoogleAuthManager: Authorization consent required. Returning PendingIntent."
                    )
                    return@withContext GoogleScopeAuthResult.ConsentRequired(it.intentSender)
                } ?: run {
                    Log.e(
                        TAG,
                        "GoogleAuthManager: AuthorizationResult has resolution but PendingIntent is null."
                    )
                    return@withContext GoogleScopeAuthResult.Error(
                        IllegalStateException("PendingIntent missing for resolution."),
                        "Authorization consent UI could not be prepared."
                    )
                }
            } else {
                val accessToken = authResult.accessToken
                if (accessToken != null) {
                    Log.i(TAG, "GoogleAuthManager: Access token acquired successfully.")
                    return@withContext GoogleScopeAuthResult.Success(
                        accessToken,
                        authResult.grantedScopes?.map { it.toString() } // Corrected: scope.toString()
                    )
                } else {
                    Log.e(
                        TAG,
                        "GoogleAuthManager: Access token was null even though no resolution was needed. Granted scopes: ${authResult.grantedScopes}"
                    )
                    return@withContext GoogleScopeAuthResult.Error(
                        IllegalStateException("Access token is null after successful authorization without resolution."),
                        "Failed to retrieve access token despite no resolution needed."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GoogleAuthManager: Failed to authorize scopes or get access token", e)
            return@withContext GoogleScopeAuthResult.Error(
                e,
                "Failed to get access token: ${e.message}"
            )
        }
    }

    suspend fun handleScopeConsentResult(intent: Intent?): GoogleScopeAuthResult =
        withContext(ioDispatcher) {
            Log.d(TAG, "GoogleAuthManager: handleScopeConsentResult() called")
            if (intent == null) {
                Log.w(TAG, "GoogleAuthManager: handleScopeConsentResult called with null intent.")
                return@withContext GoogleScopeAuthResult.Error(
                    IllegalArgumentException("Intent data is null."),
                    "Authorization intent data missing."
                )
            }
            try {
                Log.d(TAG, "GoogleAuthManager: Processing consent result intent")
                val authorizationResult =
                    authorizationClient.getAuthorizationResultFromIntent(intent)
                if (authorizationResult.hasResolution()) {
                    Log.w(
                        TAG,
                        "GoogleAuthManager: AuthorizationResult still has resolution after consent intent. PendingIntent: ${authorizationResult.pendingIntent}"
                    )
                    return@withContext GoogleScopeAuthResult.Error(
                        IllegalStateException("Consent still required after intent completion."),
                        "Authorization not fully completed by the user."
                    )
                }
                val accessToken = authorizationResult.accessToken
                if (accessToken != null) {
                    Log.i(
                        TAG,
                        "GoogleAuthManager: Access token acquired successfully after consent."
                    )
                    return@withContext GoogleScopeAuthResult.Success(
                        accessToken,
                        authorizationResult.grantedScopes?.map { it.toString() } // Corrected: scope.toString()
                    )
                } else {
                    Log.e(
                        TAG,
                        "GoogleAuthManager: Access token is null after consent intent. Granted scopes: ${authorizationResult.grantedScopes}"
                    )
                    return@withContext GoogleScopeAuthResult.Error(
                        IllegalStateException("Access token is null after consent."),
                        "Failed to retrieve access token post-consent."
                    )
                }
            } catch (e: ApiException) {
                Log.e(
                    TAG,
                    "GoogleAuthManager: ApiException while handling scope consent result: ${e.statusCode}",
                    e
                )
                return@withContext GoogleScopeAuthResult.Error(
                    e,
                    "Failed to process consent: ${e.message}"
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "GoogleAuthManager: Unexpected error while handling scope consent result",
                    e
                )
                return@withContext GoogleScopeAuthResult.Error(
                    e,
                    "Unexpected error processing consent: ${e.message}"
                )
            }
        }

    fun toGenericAccount(idTokenCredential: GoogleIdTokenCredential): Account {
        Log.d(TAG, "GoogleAuthManager: toGenericAccount() called for ID: ${idTokenCredential.id}")
        return Account(
            id = idTokenCredential.id,
            username = idTokenCredential.displayName
                ?: "Google User (${idTokenCredential.id.take(6)}...)",
            providerType = "GOOGLE"
        )
    }

    fun getEmailFromCredential(idTokenCredential: GoogleIdTokenCredential): String? {
        Log.d(
            TAG,
            "GoogleAuthManager: getEmailFromCredential() attempting to extract email from ID token"
        )
        return null // Cannot directly get email from GoogleIdTokenCredential easily without parsing the JWT raw string.
    }
}