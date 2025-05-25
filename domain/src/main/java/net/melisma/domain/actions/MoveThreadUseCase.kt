package net.melisma.domain.actions

import net.melisma.core_data.repository.ThreadRepository
import javax.inject.Inject

class MoveThreadUseCase @Inject constructor(
    private val threadRepository: ThreadRepository
) {
    // TODO: Implement actual logic
    suspend operator fun invoke(
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        // return threadRepository.moveThread(threadId, currentFolderId, destinationFolderId)
        return Result.success(Unit) // Stub
    }
} 