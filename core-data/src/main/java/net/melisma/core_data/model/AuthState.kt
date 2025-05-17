package net.melisma.core_data.model

/**
 * Represents the overall authentication state of the application, including
 * initialization status and the signed-in status across different providers.
 */
sealed class AuthState {
    /** Indicates the authentication system is currently initializing. */
    data object Initializing : AuthState()

    /** Indicates the user is signed out from all providers. */
    data object SignedOut : AuthState()

    /** Indicates the user is signed in with Microsoft. */
    data class SignedInWithMicrosoft(val username: String) : AuthState()

    /** Indicates the user is signed in with Google. */
    data class SignedInWithGoogle(val username: String) : AuthState()

    /** Indicates the user is signed in with both Microsoft and Google. */
    data class SignedInBoth(val msUsername: String, val googleUsername: String) : AuthState()

    /**
     * Indicates a general authentication error occurred (not specific to initialization).
     * This could be a sign-in failure, token refresh issue, etc.
     * @param message A descriptive error message.
     */
    data class AuthError(val message: String) : AuthState()

    // Note: The original Initialized and InitializationError states might be 
    // implicitly handled by moving to SignedOut or AuthError after initialization attempts.
    // Or, Initialized could be a separate state if needed, but often the specific SignedInXXX states cover it.
    // For now, let's omit explicit 'Initialized' and 'InitializationError' if the above cover the needs.
    // The repository was using determineAuthState(isInitialized: Boolean, error: MsalException?)
    // where isInitialized=false && error=null -> Initializing
    // error != null -> AuthError (which matches here)
    // otherwise it determined SignedIn states or SignedOut.
}
