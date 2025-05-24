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
import net.melisma.core_data.errors.MappedErrorDetails
import net.melisma.core_data.model.GenericAuthErrorType
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

    private fun mapMsalExceptionToDetails(exception: MsalException): MappedErrorDetails {
        val defaultMessage =
            exception.message ?: "An error occurred during Microsoft authentication."
        val errorCode = exception.errorCode

        return when (exception) {
            is MsalUiRequiredException -> MappedErrorDetails(
                exception.message ?: "Your session has expired. Please sign in again.",
                GenericAuthErrorType.MSAL_INTERACTIVE_AUTH_REQUIRED,
                errorCode
            )

            is MsalUserCancelException -> MappedErrorDetails(
                exception.message ?: "Operation cancelled by user.",
                GenericAuthErrorType.OPERATION_CANCELLED,
                errorCode
            )

            is MsalDeclinedScopeException -> MappedErrorDetails(
                "Please accept all permissions, including offline access. This is required for the app to function properly.",
                GenericAuthErrorType.AUTHENTICATION_FAILED,
                errorCode
            )

            is MsalClientException -> {
                val msg =
                    exception.message ?: "A client error occurred during Microsoft authentication."
                val type = when (exception.errorCode) {
                    "no_current_account" -> GenericAuthErrorType.ACCOUNT_NOT_FOUND
                    "invalid_parameter" -> GenericAuthErrorType.INVALID_REQUEST
                    // Add more mappings here based on actual MsalClientException.errorCode strings
                    else -> GenericAuthErrorType.AUTHENTICATION_FAILED
                }
                MappedErrorDetails(msg, type, errorCode)
            }

            is MsalServiceException -> {
                val msg =
                    exception.message ?: "A service error occurred with Microsoft authentication."
                val type = when (exception.errorCode) {
                    // Add specific service error code mappings here
                    // Example: MsalServiceException.INVALID_GRANT might map to AUTHENTICATION_FAILED
                    else -> GenericAuthErrorType.SERVICE_UNAVAILABLE // Or AUTHENTICATION_FAILED
                }
                MappedErrorDetails(msg, type, errorCode)
            }

            else -> MappedErrorDetails(
                defaultMessage,
                GenericAuthErrorType.AUTHENTICATION_FAILED,
                errorCode
            )
        }
    }

    override fun mapExceptionToErrorDetails(exception: Throwable?): MappedErrorDetails {
        Log.w(
            TAG,
            "mapExceptionToErrorDetails: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )

        return when (exception) {
            is MsalException -> mapMsalExceptionToDetails(exception)
            is CancellationException -> MappedErrorDetails(
                exception.message ?: "Operation cancelled.",
                GenericAuthErrorType.OPERATION_CANCELLED,
                "Cancellation"
            )

            is UnknownHostException -> MappedErrorDetails(
                "No internet connection. Please check your network.",
                GenericAuthErrorType.NETWORK_ERROR,
                "UnknownHost"
            )

            is ClientRequestException -> MappedErrorDetails(
                "Error connecting to Microsoft services (HTTP ${exception.response.status.value}). Check network or server status.",
                GenericAuthErrorType.NETWORK_ERROR,
                "KtorClientRequest-${exception.response.status.value}"
            )

            is ServerResponseException -> MappedErrorDetails(
                "Microsoft service error (HTTP ${exception.response.status.value}). Please try again later.",
                GenericAuthErrorType.SERVICE_UNAVAILABLE,
                "KtorServerResponse-${exception.response.status.value}"
            )

            is SerializationException -> MappedErrorDetails(
                "Error processing data from Microsoft. ${exception.message ?: ""}",
                GenericAuthErrorType.UNKNOWN_ERROR,
                "Serialization"
            )

            is IOException -> MappedErrorDetails(
                exception.message ?: "A network error occurred. Please check your connection.",
                GenericAuthErrorType.NETWORK_ERROR,
                "IOException"
            )

            else -> MappedErrorDetails(
                exception?.message?.takeIf { it.isNotBlank() } ?: "An unexpected error occurred.",
                GenericAuthErrorType.UNKNOWN_ERROR,
                exception?.javaClass?.simpleName ?: "UnknownThrowable"
            )
        }
    }

    fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "mapAuthExceptionToUserMessage (legacy): ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )
        if (exception is CancellationException) {
            return exception.message ?: "Authentication cancelled."
        }

        if (exception is MsalException) {
            val details = mapMsalExceptionToDetails(exception)
            return when (exception) {
                is MsalUserCancelException -> "Authentication cancelled."
                is MsalUiRequiredException -> "Your session has expired. Please sign in again or refresh your session."
                is MsalClientException -> when (exception.errorCode) {
                    "no_current_account" -> "No account found or your session is invalid. Please sign in."
                    "invalid_parameter" -> "The authentication request was invalid. Please try again."
                    else -> details.message
                }

                is MsalServiceException -> details.message
                else -> details.message
            }
        }

        val mappedDetails = mapExceptionToErrorDetails(exception)

        return when (mappedDetails.type) {
            GenericAuthErrorType.NETWORK_ERROR -> "A network error occurred. Please check your internet connection and try again."
            GenericAuthErrorType.SERVICE_UNAVAILABLE -> "Microsoft services are temporarily unavailable. Please try again later."
            GenericAuthErrorType.OPERATION_CANCELLED -> mappedDetails.message
            else -> mappedDetails.message.takeIf { it.isNotBlank() }
                ?: "An unknown authentication error occurred."
        }
    }
}
