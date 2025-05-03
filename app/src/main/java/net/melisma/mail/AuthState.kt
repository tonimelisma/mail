package net.melisma.mail

/**
 * Represents the possible states of the overall authentication system.
 * This sealed class provides a structured way to represent whether authentication is
 * initializing, ready, or has encountered an error.
 */
sealed class AuthState {
    /** Indicates the authentication system is currently initializing. */
    data object Initializing : AuthState()

    /** Indicates the authentication system is initialized and ready. */
    data object Initialized : AuthState()

    /** Indicates an error occurred during initialization. */
    data class InitializationError(val error: Exception?) : AuthState()
    // Consider adding specific error types or messages later if needed.
}