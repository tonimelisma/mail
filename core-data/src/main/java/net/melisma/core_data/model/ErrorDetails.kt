package net.melisma.core_data.model

/**
 * A data class to hold standardized error information.
 *
 * @param message A user-friendly message describing the error.
 * @param code An optional error code or type string for programmatic handling.
 * @param cause The optional underlying exception that caused this error.
 * @param isNeedsReAuth Indicates if the error suggests a re-authentication requirement.
 * @param isConnectivityIssue Indicates if the error is related to network connectivity.
 */
data class ErrorDetails(
    val message: String,
    val code: String? = null, // e.g., "network_error", "auth_failure", "api_error_XYZ"
    val cause: Throwable? = null, // For logging or deeper inspection, not typically shown to user
    val isNeedsReAuth: Boolean = false, // Added parameter
    val isConnectivityIssue: Boolean = false // Added parameter
) 