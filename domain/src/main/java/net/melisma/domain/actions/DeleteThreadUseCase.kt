package net.melisma.domain.actions

import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.ThreadRepository
import javax.inject.Inject

class DeleteThreadUseCase @Inject constructor(
    private val threadRepository: ThreadRepository
) {
    suspend operator fun invoke(account: Account, threadId: String): Result<Unit> {
        return threadRepository.deleteThread(account, threadId)
    }
} 