package net.melisma.core_data.errors

import net.melisma.core_data.model.ErrorDetails

/**
 * Interface defining the contract for mapping various exceptions into user-friendly strings.
 * Implementations will handle specific exception types (e.g., MSAL, Google Auth, Ktor network).
 */
interface ErrorMapperService {
    /**
     * Maps any exception to a structured ErrorDetails object.
     * This can then be used to construct GenericAuthResult.Error or GenericSignOutResult.Error.
     *
     * @param exception The exception to map.
     * @return An ErrorDetails object from the core_data.model package.
     */
    fun mapExceptionToErrorDetails(exception: Throwable?): ErrorDetails
}