package net.melisma.backend_google.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import net.melisma.core_data.model.Account
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a Google Sign-In operation
 */
sealed class GoogleSignInResult {
    data class Success(val account: GoogleSignInAccount) : GoogleSignInResult()
    data class Error(val exception: Exception) : GoogleSignInResult()
    object Cancelled : GoogleSignInResult()
}

/**
 * Manages Google Sign-In operations
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "GoogleAuthManager"

    // Basic sign-in options for email access
    private val baseSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail() // Email, profile, openid included by default
        .build()

    // Gmail-specific sign-in options
    private fun buildGmailSignInOptions(scopes: List<String>): GoogleSignInOptions {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        // Add each requested scope
        scopes.forEach { scope ->
            builder.requestScopes(Scope(scope))
        }

        return builder.build()
    }

    /**
     * Get the GoogleSignInClient for the specified scopes
     */
    private fun getSignInClient(scopes: List<String>): GoogleSignInClient {
        val options = if (scopes.any { it.contains("gmail") }) {
            buildGmailSignInOptions(scopes)
        } else {
            baseSignInOptions
        }

        return GoogleSignIn.getClient(context, options)
    }

    /**
     * Launch the Google Sign-In flow
     *
     * @param activity The activity context for the sign-in flow
     * @param launcher The ActivityResultLauncher that will handle the result
     * @param scopes The OAuth scopes to request
     */
    fun signIn(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>,
        scopes: List<String>
    ) {
        val signInClient = getSignInClient(scopes)
        launcher.launch(signInClient.signInIntent)
    }

    /**
     * Process the result of the Google Sign-In flow
     *
     * @param data The Intent data from onActivityResult
     * @return GoogleSignInResult with the status and account
     */
    fun handleSignInResult(data: Intent?): GoogleSignInResult {
        try {
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            return GoogleSignInResult.Success(account)
        } catch (e: ApiException) {
            Log.e(TAG, "Google sign-in failed: ${e.statusCode}", e)
            return if (e.statusCode == 12501) { // User cancelled
                GoogleSignInResult.Cancelled
            } else {
                GoogleSignInResult.Error(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google sign-in", e)
            return GoogleSignInResult.Error(e)
        }
    }

    /**
     * Sign out the current Google account
     *
     * @param onComplete Callback to execute when sign-out is complete
     */
    fun signOut(onComplete: () -> Unit) {
        val signInClient = GoogleSignIn.getClient(context, baseSignInOptions)
        signInClient.signOut().addOnCompleteListener {
            onComplete()
        }
    }

    /**
     * Check if a user is currently signed in with Google
     *
     * @return The GoogleSignInAccount if signed in, null otherwise
     */
    fun getLastSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    /**
     * Convert a GoogleSignInAccount to a generic Account model
     *
     * @param googleAccount The Google account to convert
     * @return A generic Account model
     */
    fun toGenericAccount(googleAccount: GoogleSignInAccount): Account {
        return Account(
            id = googleAccount.id ?: "",
            username = googleAccount.email ?: "Unknown Email",
            providerType = "GOOGLE"
        )
    }

    /**
     * Check if the specified account has the required scopes
     *
     * @param googleAccount The account to check
     * @param scopes The list of scope strings to verify
     * @return true if the account has all the required scopes
     */
    fun hasScopes(googleAccount: GoogleSignInAccount, scopes: List<String>): Boolean {
        val scopeObjects = scopes.map { Scope(it) }.toTypedArray()
        return GoogleSignIn.hasPermissions(googleAccount, *scopeObjects)
    }
}