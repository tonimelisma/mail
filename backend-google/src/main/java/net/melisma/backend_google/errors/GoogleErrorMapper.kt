package net.melisma.backend_google.errors

import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException // Corrected import
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
// Removed UserCancellationException as it's not the correct one from androidx.credentials
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import net.melisma.core_data.errors.ErrorMapperService
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
            is ApiException -> {
                Log.e(
                    TAG,
                    "Google API operation failed (ApiException): Status Code ${exception.statusCode}",
                    exception
                )
                "A Google service error occurred (Code: ${exception.statusCode}). Please check your connection or try again."
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
            is GetCredentialCancellationException -> { // Corrected Exception Type
                Log.d(TAG, "Authentication cancelled by user (GetCredentialCancellationException).")
                "Sign-in cancelled by user."
            }

            is NoCredentialException -> {
                Log.d(TAG, "No credentials available for authentication (NoCredentialException).")
                "No Google accounts found on this device to sign in. Please add an account to your device or try another method."
            }

            is GetCredentialException -> {
                Log.e(
                    TAG,
                    "Google Sign-In failed (GetCredentialException): ${exception.type} - ${exception.message}",
                    exception
                )
                "Google Sign-In failed. Please try again. (Error: ${exception.type})"
            }

            is GoogleIdTokenParsingException -> {
                Log.e(TAG, "Failed to parse Google ID token.", exception)
                "An error occurred while processing your Google Sign-In. Please try again."
            }

            is IllegalStateException -> {
                if (exception.message?.contains("PendingIntent missing") == true ||
                    exception.message?.contains("Access token is null") == true ||
                    exception.message?.contains("Unexpected credential type") == true ||
                    exception.message?.contains("Consent still required") == true
                ) {
                    Log.e(
                        TAG,
                        "Google Auth Flow IllegalStateException: ${exception.message}",
                        exception
                    )
                    "An internal error occurred with Google Sign-In. Please try again. (${exception.message})"
                } else {
                    Log.e(TAG, "An unexpected auth state occurred: ${exception.message}", exception)
                    "An unexpected authentication error occurred. Please try again later."
                }
            }

            is ApiException -> { // Catching ApiException here too if it's auth related
                Log.e(
                    TAG,
                    "Google Auth operation failed (ApiException): Status Code ${exception.statusCode}",
                    exception
                )
                "A Google authentication error occurred (Code: ${exception.statusCode}). Please try again."
            }

            else -> {
                Log.e(TAG, "Unknown Google Auth error: ${exception?.message}", exception)
                "An unknown authentication error occurred with Google. Please try again."
            }
        }
    }
}
