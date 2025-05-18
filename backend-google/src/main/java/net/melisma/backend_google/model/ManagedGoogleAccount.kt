package net.melisma.backend_google.model

/**
 * A data class that holds information about a Google account, typically combining
 * details from an ID token and potentially the AppAuth AuthState.
 * This is used by GoogleAuthManager to provide a richer account object.
 */
data class ManagedGoogleAccount(
    val accountId: String, // Typically the subject/ID from ParsedIdTokenInfo
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    // Consider if any part of AuthState is needed publicly by consumers of ManagedGoogleAccount.
    // For now, keeping it simple with UserInfo-like fields.
    // The raw AuthState can be managed internally by GoogleAuthManager if needed for operations.
) 