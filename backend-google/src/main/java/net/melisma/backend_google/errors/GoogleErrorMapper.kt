package net.melisma.backend_google.errors

import android.util.Log
import com.auth0.android.jwt.DecodeException // For ID token parsing errors from AppAuthHelperService
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.errors.MappedErrorDetails
import net.melisma.core_data.model.GenericAuthErrorType
import net.openid.appauth.AuthorizationException // Key exception from AppAuth
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class GoogleErrorMapper @Inject constructor() : ErrorMapperService {

    private val TAG = "GoogleErrorMapper"

    override fun mapExceptionToErrorDetails(exception: Throwable?): MappedErrorDetails {
        Log.w(
            TAG,
            "mapExceptionToErrorDetails: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}"
        )

        return when (exception) {
            is AuthorizationException -> {
                // AppAuth specific error handling
                val message =
                    exception.errorDescription ?: exception.error ?: "Google authentication failed."
                val type = when (exception.code) {
                    AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code -> GenericAuthErrorType.OPERATION_CANCELLED
                    AuthorizationException.GeneralErrors.NETWORK_ERROR.code -> GenericAuthErrorType.NETWORK_ERROR
                    AuthorizationException.GeneralErrors.SERVER_ERROR.code -> GenericAuthErrorType.SERVICE_UNAVAILABLE
                    AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR.code -> GenericAuthErrorType.UNKNOWN_ERROR // Or a specific parsing error type
                    AuthorizationException.TokenRequestErrors.INVALID_GRANT.code -> GenericAuthErrorType.AUTHENTICATION_FAILED // e.g. bad refresh token
                    AuthorizationException.TokenRequestErrors.INVALID_REQUEST.code,
                    AuthorizationException.RegistrationRequestErrors.INVALID_REDIRECT_URI.code,
                    AuthorizationException.RegistrationRequestErrors.INVALID_CLIENT_METADATA.code -> GenericAuthErrorType.INVALID_REQUEST
                    // Add more specific AppAuth error mappings here
                    else -> GenericAuthErrorType.AUTHENTICATION_FAILED // Default for other AppAuth errors
                }
                MappedErrorDetails(message, type, "AppAuth-${exception.code}")
            }
            // Placeholder for DecodeException, assuming it's a custom or library-specific exception for parsing issues
            // is DecodeException -> MappedErrorDetails(
            //     exception.message ?: "Error decoding data from Google.",
            //     GenericAuthErrorType.UNKNOWN_ERROR, // Or a specific data processing error type
            //     "DecodeException"
            // )
            is CancellationException -> MappedErrorDetails(
                exception.message ?: "Operation cancelled.",
                GenericAuthErrorType.OPERATION_CANCELLED,
                "Cancellation"
            )

            is com.google.android.gms.common.api.ApiException -> {
                // Simplified handling: removed dependency on GoogleSignInStatusCodes
                val message = exception.message?.takeIf { it.isNotBlank() }
                    ?: "A Google Play Services error occurred (code: ${exception.statusCode})"
                // Type mapping can be generic or you can add specific known status codes if any are relevant
                // without GoogleSignInStatusCodes constants.
                val type = GenericAuthErrorType.UNKNOWN_ERROR // Default for unmapped API errors
                MappedErrorDetails(message, type, "GMS-API-${exception.statusCode}")
            }

            is IOException -> MappedErrorDetails( // General network IO errors
                exception.message
                    ?: "A network error occurred with Google services. Please check your connection.",
                GenericAuthErrorType.NETWORK_ERROR,
                "IOException"
            )
            // Fallback for other Throwables
            else -> MappedErrorDetails(
                exception?.message?.takeIf { it.isNotBlank() }
                    ?: "An unexpected error occurred with Google services.",
                GenericAuthErrorType.UNKNOWN_ERROR,
                exception?.javaClass?.simpleName ?: "UnknownThrowable"
            )
        }
    }

    fun mapNetworkOrApiException(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping Google Network/API error: ${exception?.javaClass?.simpleName} - ${exception?.message}",
            exception
        )
        return when (exception) {
            is AuthorizationException -> {
                Log.e(
                    TAG,
                    "AppAuth AuthorizationException (Network/API related): Type: ${exception.type}, Code: ${exception.code}, Desc: ${exception.errorDescription}",
                    exception
                )
                if (exception.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code ||
                    exception.code == AuthorizationException.GeneralErrors.SERVER_ERROR.code
                ) {
                    "Network error during Google operation. Please check your connection or try again. (AppAuth Code: ${exception.code})"
                } else {
                    "A Google service error occurred during authentication. (AppAuth Code: ${exception.code})"
                }
            }
            is IOException -> {
                Log.e(TAG, "Network or I/O error during Google operation.", exception)
                "Network error. Please check your internet connection and try again."
            }
            else -> {
                Log.e(TAG, "Unknown Google Network/API error: ${exception?.message}", exception)
                "An unknown network or Google service error occurred. Please try again."
            }
        }
    }

    fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping Google Auth error: ${exception?.javaClass?.simpleName} - ${exception?.message}",
            exception
        )
        return when (exception) {
            is AuthorizationException -> {
                Log.e(
                    TAG,
                    "Google AppAuth AuthorizationException: Type: ${exception.type}, Code: ${exception.code}, Desc: ${exception.errorDescription}",
                    exception
                )
                when (exception.code) {
                    AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code ->
                        "Sign-in cancelled by user."

                    AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW.code ->
                        "Sign-in process was cancelled."

                    AuthorizationException.TokenRequestErrors.INVALID_GRANT.code ->
                        "Authentication failed. Your session might have expired or been revoked. Please try signing in again."

                    AuthorizationException.TokenRequestErrors.INVALID_CLIENT.code,
                    AuthorizationException.TokenRequestErrors.INVALID_REQUEST.code,
                    AuthorizationException.TokenRequestErrors.INVALID_SCOPE.code,
                    AuthorizationException.TokenRequestErrors.UNAUTHORIZED_CLIENT.code,
                    AuthorizationException.TokenRequestErrors.UNSUPPORTED_GRANT_TYPE.code ->
                        "Authentication configuration error. Please contact support if this persists. (Error: ${exception.errorDescription ?: exception.code})"

                    AuthorizationException.GeneralErrors.NETWORK_ERROR.code ->
                        "Network error during sign-in. Please check your connection."

                    AuthorizationException.GeneralErrors.SERVER_ERROR.code ->
                        "Google server error during sign-in. Please try again later."

                    else ->
                        "An authentication error occurred with Google. (AppAuth: ${exception.errorDescription ?: exception.code})"
                }
            }

            is DecodeException -> {
                Log.e(TAG, "Failed to decode or parse ID token.", exception)
                "An error occurred while processing your Google Sign-In data. Please try again."
            }
            is IllegalStateException -> {
                Log.e(TAG, "An unexpected auth state occurred: ${exception.message}", exception)
                "An unexpected authentication error occurred. Please try again later."
            }
            else -> {
                Log.e(TAG, "Unknown Google Auth error: ${exception?.message}", exception)
                "An unknown authentication error occurred with Google. Please try again."
            }
        }
    }
}
