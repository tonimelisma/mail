package net.melisma.core_data.errors

import net.melisma.core_data.model.GenericAuthErrorType

// New data class to hold structured error details
data class MappedErrorDetails(
    val message: String,
    val type: GenericAuthErrorType,
    val providerSpecificErrorCode: String? = null
    // Add msalRequiresInteractiveSignIn if it's generic enough,
    // otherwise, it's better handled within the Microsoft-specific mapping
    // and then translated to the msalRequiresInteractiveSignIn flag in GenericAuthResult.Error.
    // For now, keeping it out of this generic MappedErrorDetails.
)

/**
 * Interface defining the contract for mapping various exceptions into user-friendly strings.
 * Implementations will handle specific exception types (e.g., MSAL, Google Auth, Ktor network).
 */
interface ErrorMapperService {
    /**
     * Maps any exception to a structured MappedErrorDetails object.
     * This can then be used to construct GenericAuthResult.Error or GenericSignOutResult.Error.
     *
     * @param exception The exception to map.
     * @return A MappedErrorDetails object.
     */
    fun mapExceptionToErrorDetails(exception: Throwable?): MappedErrorDetails
}