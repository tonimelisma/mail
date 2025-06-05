package net.melisma.core_data.datasource

interface MailApiServiceSelector {
    fun getServiceByProviderType(providerType: String): MailApiService?
    suspend fun getServiceByAccountId(accountId: String): MailApiService?
} 