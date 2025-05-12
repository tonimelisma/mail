package net.melisma.backend_microsoft.errors

import android.util.Log
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.serialization.SerializationException
import net.melisma.core_data.errors.ErrorMapperService // Import the interface
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Microsoft-specific implementation of [ErrorMapperService].
 * Handles mapping MSAL exceptions and common network exceptions.
 */
@Singleton // Mark as Singleton if appropriate for Hilt
class MicrosoftErrorMapper @Inject constructor() : ErrorMapperService {

    private val TAG = "MicrosoftErrorMapper"

    /**
     * Maps an HTTP status code and response body to a user-friendly error message.
     * Used by GraphApiHelper to handle HTTP errors from Microsoft Graph API.
     *
     * @param statusCode The HTTP status code
     * @param errorBody The error response body as text
     * @return A user-friendly error message
     */
    fun mapHttpError(statusCode: Int, errorBody: String): Exception {
        Log.w(TAG, "Mapping HTTP error: $statusCode - $errorBody")
        val errorMessage = when (statusCode) {
            401 -> "Authentication failed. Please sign in again."
            403 -> "You don't have permission to access this resource."
            404 -> "The requested item could not be found."
            429 -> "Too many requests. Please try again later."
            500, 502, 503, 504 -> "Microsoft service is unavailable. Please try again later."
            else -> "Error communicating with Microsoft services (HTTP $statusCode)."
        }
        return Exception(errorMessage)
    }

    /**
     * Maps any exception to a user-friendly error message.
     * Used by GraphApiHelper to handle exceptions during API calls.
     *
     * @param exception The exception to map
     * @return A user-friendly error message
     */
    fun mapExceptionToError(exception: Exception): Exception {
        Log.w(
            TAG,
            "Mapping exception to error: ${exception.javaClass.simpleName} - ${exception.message}",
            exception
        )
        val errorMessage = when (exception) {
            is ClientRequestException -> "Error connecting to Microsoft services (${exception.response.status.value})."
            is ServerResponseException -> "Microsoft service error (${exception.response.status.value})."
            is SerializationException -> "Error processing the response from Microsoft."
            is IOException -> "Network error. Please check your connection."
            is CancellationException -> "Operation was cancelled."
            is MsalException -> mapAuthExceptionToUserMessage(exception)
            else -> "An unexpected error occurred: ${exception.message}"
        }
        return Exception(errorMessage)
    }

    override fun mapNetworkOrApiException(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping network/API exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )
        // Handle standard coroutine cancellation first
        if (exception is CancellationException) {
            return exception.message ?: "Operation cancelled."
        }
        // TODO: Enhance with Ktor specific exceptions in Step 1.2
        return when (exception) {
            is UnknownHostException -> "No internet connection"
            is IOException -> "Network error occurred"
            // Add more specific network/API errors here if needed
            else -> exception?.message?.takeIf { it.isNotBlank() }
                ?: "An unknown network or API error occurred"
        }
    }

    override fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        // Handle standard coroutine cancellation first
        if (exception is CancellationException) {
            return exception.message ?: "Authentication cancelled."
        }

        // --- MSAL Specific Handling ---
        if (exception is MsalException) {
            Log.w(
                TAG,
                "Mapping MSAL auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
            )
            val code = exception.errorCode ?: "UNKNOWN"

            return when (exception) {
                is MsalUserCancelException -> "Authentication cancelled."
                is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
                is MsalClientException -> when (exception.errorCode) {
                    MsalClientException.NO_CURRENT_ACCOUNT -> "Account not found or session invalid."
                    MsalClientException.INVALID_PARAMETER -> "Authentication request is invalid."
                    // Add more specific MsalClientException codes as needed
                    else -> exception.message?.takeIf { it.isNotBlank() }
                        ?: "Authentication client error ($code)"
                }

                is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication service error ($code)"

                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication failed ($code)"
            }
        }

        // --- Fallback for Non-MSAL Auth or Other Exceptions ---
        // If it wasn't an MSAL exception, try mapping it as a general network/API error.
        Log.w(
            TAG,
            "Mapping non-MSAL auth/other exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}")
        val networkMapped = mapNetworkOrApiException(exception)
        // Use a generic auth fallback only if the network mapping also resulted in a generic message
        return if (networkMapped == "An unknown network or API error occurred") {
            exception?.message?.takeIf { it.isNotBlank() }
                ?: "An unknown authentication error occurred"
        } else {
            networkMapped
        }
    }
}
