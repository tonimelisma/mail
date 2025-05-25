package net.melisma.domain.account

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import javax.inject.Inject

class ObserveAuthStateUseCase @Inject constructor(
    private val accountRepository: AccountRepository
) {
    operator fun invoke(): Flow<OverallApplicationAuthState> {
        return accountRepository.overallApplicationAuthState
    }
} 