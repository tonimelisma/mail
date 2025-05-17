package net.melisma.backend_google.errors

import android.util.Log
import com.auth0.android.jwt.DecodeException // For ID token parsing errors from AppAuthHelperService
import net.melisma.core_data.errors.ErrorMapperService
import net.openid.appauth.AuthorizationException // Key exception from AppAuth
import java.io.IOException
import javax.inject.Inject

class GoogleErrorMapper @Inject constructor() : ErrorMapperService {

    private val TAG = "GoogleErrorMapper"

    override fun mapNetworkOrApiException(exception: Throwable?): String {
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

    override fun mapAuthExceptionToUserMessage(exception: Throwable?): String {
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
