package net.melisma.core_data.model

/**
 * Represents the result of a generic sign-out operation.
 */
sealed class GenericSignOutResult {
    /** Indicates that the sign-out process is ongoing. */
    data object Loading : GenericSignOutResult()

    /** Indicates that sign-out was successful. */
    data object Success : GenericSignOutResult()

    /**
     * Indicates that an error occurred during sign-out.
     * @param details The structured [ErrorDetails] of the failure.
     */
    data class Error(val details: ErrorDetails) : GenericSignOutResult()
} 