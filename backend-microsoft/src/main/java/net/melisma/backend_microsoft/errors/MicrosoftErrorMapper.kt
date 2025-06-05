package net.melisma.backend_microsoft.errors

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
import timber.log.Timber
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

    /**
     * Maps any exception to a user-friendly error message.
     * Used by GraphApiHelper to handle exceptions during API calls.
     *
     * @param exception The exception to map
     * @return A user-friendly error message
     */
    fun mapExceptionToError(exception: Exception): Exception {
        Timber.w(
            exception,
            "Mapping exception to error: ${exception.javaClass.simpleName} - ${exception.message}"
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
                cause = exception,
                isNeedsReAuth = true
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
                val needsReAuth = errorCode == "invalid_grant" || errorCode == "unauthorized_client"
                ErrorDetails(
                    message = msg,
                    code = errorCode,
                    cause = exception,
                    isNeedsReAuth = needsReAuth
                )
            }

            else -> ErrorDetails(
                message = defaultMessage,
                code = errorCode,
                cause = exception
            )
        }
    }

    override fun mapExceptionToErrorDetails(exception: Throwable?): ErrorDetails {
        Timber.w(
            exception,
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
                cause = exception,
                isConnectivityIssue = true
            )

            is ClientRequestException -> {
                val statusCode = exception.response.status.value
                val isAuthError = statusCode == 401 || statusCode == 403
                ErrorDetails(
                    message = "Error connecting to Microsoft services (HTTP $statusCode). Check network or server status.",
                    code = "KtorClientRequest-$statusCode",
                    cause = exception,
                    isNeedsReAuth = isAuthError,
                    isConnectivityIssue = !isAuthError
                )
            }

            is ServerResponseException -> ErrorDetails(
                message = "Microsoft service error (HTTP ${exception.response.status.value}). Please try again later.",
                code = "KtorServerResponse-${exception.response.status.value}",
                cause = exception,
                isConnectivityIssue = true
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
                cause = exception,
                isConnectivityIssue = true
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
        Timber.w(
            exception,
            "mapAuthExceptionToUserMessage (legacy): ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )
        return mapExceptionToErrorDetails(exception).message
    }
}
