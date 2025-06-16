package net.melisma.domain.account

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.GenericSignOutAllResult
import javax.inject.Inject

class SignOutAllMicrosoftAccountsUseCase @Inject constructor(
    private val accountRepository: AccountRepository
) {
    operator fun invoke(): Flow<GenericSignOutAllResult> {
        return accountRepository.signOutAllMicrosoftAccounts()
    }
} 