package net.melisma.core_data.datasource

// Assuming Account model is accessible, if not, an import might be needed
// import net.melisma.core_data.model.Account 

class MailApiServiceResolutionException(message: String) : Exception(message)

interface MailApiServiceFactory {
    /**
     * Returns the appropriate MailApiService instance for the given accountId.
     * @param accountId The ID of the account for which to get the service.
     * @return The specific MailApiService implementation.
     * @throws MailApiServiceResolutionException if the service cannot be resolved (e.g., account not found, unknown provider).
     */
    suspend fun getService(accountId: String): MailApiService
} 