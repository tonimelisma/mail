package net.melisma.core_data.model

/**
 * Represents the possible states of the overall authentication system, particularly
 * focusing on the initialization status of the underlying authentication provider (e.g., MSAL).
 * This allows the application to react appropriately during startup or if initialization fails.
 */
sealed class AuthState {
    /** Indicates the authentication system is currently initializing (e.g., MSAL is being created). */
    data object Initializing : AuthState()

    /** Indicates the authentication system has successfully initialized and is ready for use. */
    data object Initialized : AuthState()

    /**
     * Indicates an error occurred during the initialization of the authentication system.
     * @param error The exception that occurred during initialization, if available.
     */
    data class InitializationError(val error: Exception?) : AuthState()
}
