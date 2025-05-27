package net.melisma.backend_microsoft.errors

import android.util.Log
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.serialization.SerializationException
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.ErrorDetails
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Microsoft-specific implementation of [ErrorMapperService].
 * Handles mapping MSAL exceptions and common network exceptions.
 */
@Singleton
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

    private fun mapMsalExceptionToDetails(exception: MsalException): ErrorDetails {
        val defaultMessage =
            exception.message ?: "An error occurred during Microsoft authentication."
        val errorCode = exception.errorCode

        return when (exception) {
            is MsalUiRequiredException -> ErrorDetails(
                message = exception.message ?: "Your session has expired. Please sign in again.",
                code = errorCode,
                cause = exception
            )

            is MsalUserCancelException -> ErrorDetails(
                message = exception.message ?: "Operation cancelled by user.",
                code = errorCode,
                cause = exception
            )

            is MsalDeclinedScopeException -> ErrorDetails(
                message = "Please accept all permissions, including offline access. This is required for the app to function properly.",
                code = errorCode,
                cause = exception
            )

            is MsalClientException -> {
                val msg =
                    exception.message ?: "A client error occurred during Microsoft authentication."
                ErrorDetails(message = msg, code = errorCode, cause = exception)
            }

            is MsalServiceException -> {
                val msg =
                    exception.message ?: "A service error occurred with Microsoft authentication."
                ErrorDetails(message = msg, code = errorCode, cause = exception)
            }

            else -> ErrorDetails(
                message = defaultMessage,
                code = errorCode,
                cause = exception
            )
        }
    }

    override fun mapExceptionToErrorDetails(exception: Throwable?): ErrorDetails {
        Log.w(
            TAG,
            "mapExceptionToErrorDetails: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )

        return when (exception) {
            is MsalException -> mapMsalExceptionToDetails(exception)
            is CancellationException -> ErrorDetails(
                message = exception.message ?: "Operation cancelled.",
                code = "Cancellation",
                cause = exception
            )

            is UnknownHostException -> ErrorDetails(
                message = "No internet connection. Please check your network.",
                code = "UnknownHost",
                cause = exception
            )

            is ClientRequestException -> ErrorDetails(
                message = "Error connecting to Microsoft services (HTTP ${exception.response.status.value}). Check network or server status.",
                code = "KtorClientRequest-${exception.response.status.value}",
                cause = exception
            )

            is ServerResponseException -> ErrorDetails(
                message = "Microsoft service error (HTTP ${exception.response.status.value}). Please try again later.",
                code = "KtorServerResponse-${exception.response.status.value}",
                cause = exception
            )

            is SerializationException -> ErrorDetails(
                message = "Error processing data from Microsoft. ${exception.message ?: ""}",
                code = "Serialization",
                cause = exception
            )

            is IOException -> ErrorDetails(
                message = exception.message
                    ?: "A network error occurred. Please check your connection.",
                code = "IOException",
                cause = exception
            )

            null -> ErrorDetails(
                message = "An unknown error occurred.",
                code = "UnknownThrowableNull"
            )

            else -> ErrorDetails(
                message = exception.message?.takeIf { it.isNotBlank() }
                    ?: "An unexpected error occurred.",
                code = exception.javaClass.simpleName ?: "UnknownThrowable",
                cause = exception
            )
        }
    }

    fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "mapAuthExceptionToUserMessage (legacy): ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )
        return mapExceptionToErrorDetails(exception).message
    }
}
