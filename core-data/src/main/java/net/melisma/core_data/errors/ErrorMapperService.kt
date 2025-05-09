package net.melisma.core_data.errors

/**
 * Interface defining the contract for mapping various exceptions into user-friendly strings.
 * Implementations will handle specific exception types (e.g., MSAL, Google Auth, Ktor network).
 */
interface ErrorMapperService {
    /**
     * Maps network or general API related exceptions to user-friendly messages.
     *
     * @param exception The exception to map.
     * @return A user-friendly error string.
     */
    fun mapNetworkOrApiException(exception: Throwable?): String

    /**
     * Maps Authentication (MSAL, Google Auth, etc.) related exceptions to user-friendly messages.
     *
     * @param exception The exception to map.
     * @return A user-friendly error string.
     */
    fun mapAuthExceptionToUserMessage(exception: Throwable?): String
}