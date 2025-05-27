package net.melisma.backend_google.errors

import android.util.Log
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.ErrorDetails
import net.openid.appauth.AuthorizationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class GoogleErrorMapper @Inject constructor() : ErrorMapperService {

    private val TAG = "GoogleErrorMapper"

    override fun mapExceptionToErrorDetails(exception: Throwable?): ErrorDetails {
        Log.w(
            TAG,
            "mapExceptionToErrorDetails: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )

        return when (exception) {
            is AuthorizationException -> {
                val message =
                    exception.errorDescription ?: exception.error ?: "Google authentication failed."
                ErrorDetails(
                    message = message,
                    code = "AppAuth-${exception.code}",
                    cause = exception
                )
            }

            is CancellationException -> ErrorDetails(
                message = exception.message ?: "Operation cancelled.",
                code = "Cancellation",
                cause = exception
            )
            is com.google.android.gms.common.api.ApiException -> {
                val message = exception.message?.takeIf { it.isNotBlank() }
                    ?: "A Google Play Services error occurred (code: ${exception.statusCode})"
                ErrorDetails(
                    message = message,
                    code = "GMS-API-${exception.statusCode}",
                    cause = exception
                )
            }

            is IOException -> ErrorDetails( // General network IO errors
                message = exception.message
                    ?: "A network error occurred with Google services. Please check your connection.",
                code = "IOException",
                cause = exception
            )

            null -> ErrorDetails( // Handle null exception case
                message = "An unknown error occurred with Google services.",
                code = "UnknownThrowableNullGoogle"
                // cause is null here
            )
            // Fallback for other Throwables
            else -> ErrorDetails(
                message = exception.message?.takeIf { it.isNotBlank() }
                    ?: "An unexpected error occurred with Google services.",
                code = exception.javaClass.simpleName ?: "UnknownThrowableGoogle",
                cause = exception
            )
        }
    }

    fun mapNetworkOrApiException(exception: Throwable?): String {
        // This method might become less relevant if UI consumes ErrorDetails directly.
        // For now, make it use the new mapExceptionToErrorDetails.
        return mapExceptionToErrorDetails(exception).message
    }

    fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        // This method might become less relevant if UI consumes ErrorDetails directly.
        // For now, make it use the new mapExceptionToErrorDetails.
        return mapExceptionToErrorDetails(exception).message
    }
}
