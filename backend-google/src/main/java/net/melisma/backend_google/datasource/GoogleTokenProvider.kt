package net.melisma.backend_google.datasource

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of TokenProvider for Google authentication
 */
@Singleton
class GoogleTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val errorMapper: ErrorMapperService
) : TokenProvider {

    private val TAG = "GoogleTokenProvider"

    /**
     * Gets an access token for a Google account
     *
     * @param account The generic Account model for which to get a token
     * @param scopes The list of OAuth scopes to request
     * @param activity Optional Activity context for interactive auth if needed
     * @return Result containing the access token or an error
     */
    override suspend fun getAccessToken(
        account: Account,
        scopes: List<String>,
        activity: Activity?
    ): Result<String> = withContext(ioDispatcher) {
        try {
            // Verify account is a Google account
            if (account.providerType != "GOOGLE") {
                Log.e(
                    TAG,
                    "Non-Google account passed to GoogleTokenProvider: ${account.providerType}"
                )
                return@withContext Result.failure(IllegalArgumentException("Account is not a Google account"))
            }

            // Get the associated GoogleSignInAccount
            val googleAccount = GoogleSignIn.getLastSignedInAccount(context)
            if (googleAccount == null || googleAccount.id != account.id) {
                Log.e(TAG, "No matching Google account found for ID: ${account.id}")
                return@withContext Result.failure(
                    IllegalStateException("No matching Google account found")
                )
            }

            // Check if we have the necessary permissions already
            val scopeObjects = scopes.map { Scope(it) }.toTypedArray()
            if (!GoogleSignIn.hasPermissions(googleAccount, *scopeObjects)) {
                Log.w(TAG, "Google account missing required scopes: $scopes")
                return@withContext Result.failure(
                    IllegalStateException("Missing required permissions. Re-authentication needed.")
                )
            }

            // Request OAuth token - this must be done off the main thread
            val scopeString = "oauth2:${scopes.joinToString(" ")}"
            val token = GoogleAuthUtil.getToken(context, googleAccount.account!!, scopeString)
            Log.d(TAG, "Successfully acquired Google token for ${account.username}")

            return@withContext Result.success(token)
        } catch (e: GoogleAuthException) {
            Log.e(TAG, "Google auth exception getting token", e)
            Result.failure(e)
        } catch (e: IOException) {
            Log.e(TAG, "IO exception getting Google token", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception getting Google token", e)
            Result.failure(e)
        }
    }
}