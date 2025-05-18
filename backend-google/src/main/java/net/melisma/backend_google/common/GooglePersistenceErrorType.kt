package net.melisma.backend_google.common

/**
 * Enum defining specific error types that can occur during Google account
 * persistence operations via [net.melisma.backend_google.auth.GoogleTokenPersistenceService].
 */
enum class GooglePersistenceErrorType {
    ENCRYPTION_FAILED,
    DECRYPTION_FAILED,
    STORAGE_FAILED, // e.g., AccountManager.addAccountExplicitly failed
    ACCOUNT_NOT_FOUND,
    AUTH_STATE_SERIALIZATION_FAILED,
    AUTH_STATE_DESERIALIZATION_FAILED,
    MISSING_AUTH_STATE_JSON,
    TOKEN_UPDATE_FAILED, // General failure during updateAuthState
    TOKEN_REFRESH_INVALID_GRANT, // Specific to Google's invalid_grant, might be raised by a manager rather than persistence service directly
    UNKNOWN_ERROR
} 