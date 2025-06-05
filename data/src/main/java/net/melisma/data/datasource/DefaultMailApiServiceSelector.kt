package net.melisma.data.datasource

import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultMailApiServiceSelector @Inject constructor(
    private val apiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    private val accountRepository: AccountRepository // To fetch account details if only accountId is provided
) : MailApiServiceSelector {

    override fun getServiceByProviderType(providerType: String): MailApiService? {
        return apiServices[providerType.uppercase()]
    }

    override suspend fun getServiceByAccountId(accountId: String): MailApiService? {
        val account = accountRepository.getAccountByIdSuspend(accountId)
        return account?.providerType?.let { apiServices[it.uppercase()] }
    }
} 