package net.melisma.core_data.model

/**
 * Enum to categorize generic authentication error types.
 */
enum class GenericAuthErrorType {
    ACCOUNT_NOT_FOUND,
    AUTHENTICATION_FAILED,
    INVALID_REQUEST,
    MSAL_INTERACTIVE_AUTH_REQUIRED, // Specific to MSAL, but can be a generic category
    NETWORK_ERROR,
    OPERATION_CANCELLED,
    SERVICE_UNAVAILABLE,
    UNKNOWN_ERROR
} 