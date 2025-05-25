package net.melisma.domain.actions

import android.util.Log
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Inject

class DefaultSyncAccountUseCase @Inject constructor(
    private val accountRepository: AccountRepository
) : SyncAccountUseCase {
    override suspend operator fun invoke(accountId: String): Result<Unit> {
        Log.d("DefaultSyncAccountUseCase", "Invoked for accountId: $accountId")
        // return accountRepository.syncAccount(accountId) // Actual call commented out for stub
        return Result.success(Unit)
        // Or return Result.failure(NotImplementedError("DefaultSyncAccountUseCase not implemented"))
    }
} 