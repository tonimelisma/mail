package net.melisma.domain.account

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject

class GetAccountsUseCase @Inject constructor(
    private val accountRepository: AccountRepository
) {
    operator fun invoke(): Flow<List<Account>> {
        return accountRepository.getAccounts()
    }
} 