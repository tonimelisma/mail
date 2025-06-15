package net.melisma.domain.actions

import net.melisma.core_data.repository.AccountRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultSyncAccountUseCase @Inject constructor(
    private val accountRepository: AccountRepository
) : SyncAccountUseCase {
    override suspend operator fun invoke(accountId: String): Result<Unit> {
        Timber.d("Invoked for accountId: $accountId")
        return accountRepository.syncAccount(accountId)
    }
} 