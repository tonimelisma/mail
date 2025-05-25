package net.melisma.domain.account

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject

class SignOutUseCase @Inject constructor(
    private val accountRepository: AccountRepository
) {
    suspend operator fun invoke(account: Account): Flow<GenericSignOutResult> {
        return accountRepository.signOut(account)
    }
} 