package net.melisma.core_data.model

// Assumes Account model (net.melisma.core_data.model.Account) is already generic and suitable.
// data class Account(val id: String, val username: String, val providerType: String, val needsReauthentication: Boolean = false)

sealed class GenericAuthResult {
    data class Success(val account: Account) : GenericAuthResult()

    /**
     * Indicates that an external UI step is required (e.g., launching an Intent for AppAuth).
     * The Intent should be obtained via a separate mechanism (e.g., a dedicated flow or method in the repository).
     */
    data class UiActionRequired(val intent: android.content.Intent) : GenericAuthResult()
    data class Error(
        val message: String,
        val type: GenericAuthErrorType,
        val providerSpecificErrorCode: String? = null, // For logging or very specific cases
        // isUiRequiredForResolution is specific to MSAL's MsalUiRequiredException.
        // Other UI required scenarios (like needing to re-launch interactive sign-in)
        // should be modeled by the ViewModel based on error type or specific flags.
        // For MSAL's specific MsalUiRequiredException, this can be a specific error type or a flag.
        val msalRequiresInteractiveSignIn: Boolean = false
    ) : GenericAuthResult()

    object Cancelled : GenericAuthResult()
    // InProgress is removed to simplify the states returned by signIn.
    // Loading states should be managed by the ViewModel observing the flow.
}

enum class GenericAuthErrorType {
    NETWORK_ERROR,
    SERVICE_UNAVAILABLE, // e.g., MSAL not initialized, AppAuth misconfiguration
    AUTHENTICATION_FAILED, // General auth failure, wrong credentials, token exchange failure
    AUTHORIZATION_DENIED, // User denied permissions
    TOKEN_OPERATION_FAILED, // Failed to get/refresh/revoke token
    ACCOUNT_NOT_FOUND,
    INVALID_REQUEST,
    OPERATION_CANCELLED, // Explicit cancellation by user
    MSAL_INTERACTIVE_AUTH_REQUIRED, // Specific for MsalUiRequiredException
    UNKNOWN_ERROR
}

sealed class GenericSignOutResult {
    object Success : GenericSignOutResult()
    data class Error(
        val message: String,
        val type: GenericAuthErrorType, // Re-use GenericAuthErrorType
        val providerSpecificErrorCode: String? = null
    ) : GenericSignOutResult()
} 