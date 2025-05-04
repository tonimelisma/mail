package net.melisma.backend_microsoft.errors // Changed package

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
 * into user-friendly error messages suitable for display in the UI or logging.
 */
object ErrorMapper {

    private const val TAG = "ErrorMapper"

    /** Maps network or Graph API related exceptions to user-friendly messages. */
    fun mapGraphExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping graph exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )
        return when (exception) {
            is UnknownHostException -> "No internet connection" // Specific check for network availability
            is IOException -> "Network error occurred" // General IO errors during network operations
            // TODO: Consider adding checks for specific HTTP status codes if GraphApiHelper surfaces them
            else -> exception?.message?.takeIf { it.isNotBlank() }
                ?: "An unknown error occurred" // Fallback generic message
        }
    }

    /** Maps Authentication (MSAL) related exceptions to user-friendly messages. */
    fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        // Handle standard coroutine cancellation first
        if (exception is CancellationException) {
            return exception.message ?: "Authentication cancelled."
        }
        // If it's not an MSAL exception, treat it as a general/graph exception
        if (exception !is MsalException) {
            return mapGraphExceptionToUserMessage(exception)
        }

        // Log MSAL exception details for debugging
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        val code = exception.errorCode ?: "UNKNOWN" // Use "UNKNOWN" if code is null

        return when (exception) {
            // Specific MSAL exception types with distinct user meanings
            is MsalUserCancelException -> "Authentication cancelled."
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."

            // Handle common MsalClientExceptions based on their error codes
            is MsalClientException -> when (exception.errorCode) {
                MsalClientException.NO_CURRENT_ACCOUNT -> "Account not found or session invalid."
                MsalClientException.INVALID_PARAMETER -> "Authentication request is invalid." // Updated mapping
                // TODO: Add more specific MsalClientException codes as needed
                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication client error ($code)" // Generic client error
            }

            // Handle MsalServiceExceptions (errors from the identity provider)
            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error ($code)" // Generic service error

            // Fallback for any other MsalException types
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication failed ($code)"
        }
    }
}
