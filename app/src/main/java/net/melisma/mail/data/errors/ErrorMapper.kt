package net.melisma.mail.data.errors

import android.util.Log
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import java.io.IOException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Centralized utility for mapping exceptions from data sources (Graph API, MSAL)
 * into user-friendly error messages.
 */
object ErrorMapper {

    private const val TAG = "ErrorMapper"

    /** Maps Graph API related exceptions to user-friendly messages. */
    fun mapGraphExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping graph exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )
        return when (exception) {
            is UnknownHostException -> "No internet connection" // Specific check for network
            is IOException -> "Network error occurred" // General IO errors
            // Consider adding checks for specific HTTP error codes if GraphApiHelper provides them
            else -> exception?.message?.takeIf { it.isNotBlank() }
                ?: "An unknown error occurred" // Fallback generic message
        }
    }

    /** Maps Authentication (MSAL) related exceptions to user-friendly messages. */
    fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        // Handle standard CancellationException first (e.g., from user cancelling flows)
        if (exception is CancellationException) {
            return exception.message ?: "Authentication cancelled."
        }
        // If it's not an MSAL exception, try mapping it as a general/graph exception
        if (exception !is MsalException) {
            return mapGraphExceptionToUserMessage(exception) // Fallback to general mapping
        }

        // It's an MsalException, log details and map specific types/codes
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        val code = exception.errorCode ?: "UNKNOWN" // Use UNKNOWN if error code is null

        return when (exception) {
            // Check specific exception types known to have distinct user meanings
            is MsalUserCancelException -> "Authentication cancelled."
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."

            // Handle common client exceptions with specific codes
            is MsalClientException -> when (exception.errorCode) {
                MsalClientException.NO_CURRENT_ACCOUNT -> "Account not found or session invalid."
                // TODO: Add mappings for other specific MsalClientException error codes if needed based on observed issues or MSAL documentation
                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication client error ($code)" // Generic client error with code
            }

            // Handle service exceptions (errors from the Microsoft identity platform)
            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error ($code)" // Generic service error with code

            // Fallback for any other MsalException type
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication failed ($code)" // Generic auth failed with code
        }
    }
}