package net.melisma.backend_google.auth

/**
 * Exception class for Google authentication errors.
 * Provides standardized error codes for common authentication failures.
 */
class GoogleAuthenticationException(
    message: String,
    val errorCode: String
) : Exception(message) {
    companion object {
        const val CODE_USER_CANCEL = "USER_CANCEL"
        const val CODE_UNAUTHORIZED = "UNAUTHORIZED"
        const val CODE_INVALID_SCOPES = "INVALID_SCOPES"
        const val CODE_NO_ACCOUNT = "NO_ACCOUNT"
        const val CODE_NETWORK_ERROR = "NETWORK_ERROR"
        const val CODE_API_ERROR = "API_ERROR"
        const val CODE_UNKNOWN = "UNKNOWN"
    }
}