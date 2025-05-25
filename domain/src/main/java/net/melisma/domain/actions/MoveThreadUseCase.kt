package net.melisma.domain.actions

import net.melisma.core_data.repository.ThreadRepository
import javax.inject.Inject

class MoveThreadUseCase @Inject constructor(
    private val threadRepository: ThreadRepository
) {
    suspend operator fun invoke(
        account: net.melisma.core_data.model.Account,
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        return threadRepository.moveThread(account, threadId, currentFolderId, destinationFolderId)
    }
} 