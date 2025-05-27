package net.melisma.core_data.model

import android.content.Intent // For UiActionRequired

/**
 * Represents the result of a generic authentication operation.
 */
sealed class GenericAuthResult {
    /** Indicates that the authentication process is ongoing. */
    data object Loading : GenericAuthResult()

    /**
     * Indicates that authentication was successful.
     * @param account The authenticated [Account] details.
     */
    data class Success(val account: Account) : GenericAuthResult()

    /**
     * Indicates that an error occurred during authentication.
     * @param details The structured [ErrorDetails] of the failure.
     */
    data class Error(val details: ErrorDetails) : GenericAuthResult()

    /**
     * Indicates that UI interaction is required from the user to proceed with authentication.
     * This is typically used by flows like AppAuth where an Intent needs to be launched.
     * @param intent The Intent to launch for user interaction.
     */
    data class UiActionRequired(val intent: Intent) : GenericAuthResult()
} 