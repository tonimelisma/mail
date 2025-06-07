package net.melisma.data.datasource

import net.melisma.backend_google.GmailApiHelper
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.MailApiServiceFactory
import net.melisma.core_data.datasource.MailApiServiceResolutionException
import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MailApiServiceFactoryImpl @Inject constructor(
    private val accountRepository: AccountRepository,
    private val gmailApiHelper: GmailApiHelper,
    private val graphApiHelper: GraphApiHelper
) : MailApiServiceFactory {

    override suspend fun getService(accountId: String): MailApiService {
        val account = accountRepository.getAccountByIdSuspend(accountId)
            ?: throw MailApiServiceResolutionException("Account not found for ID: $accountId")

        return when (account.providerType) {
            Account.PROVIDER_TYPE_GOOGLE -> gmailApiHelper
            Account.PROVIDER_TYPE_MS -> graphApiHelper
            else -> throw MailApiServiceResolutionException("Unknown provider type '${account.providerType}' for account ID: $accountId")
        }
    }
} 