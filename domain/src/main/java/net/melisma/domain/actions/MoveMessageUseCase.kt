package net.melisma.domain.actions

import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class MoveMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    // TODO: Implement actual logic
    suspend operator fun invoke(
        messageId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        // return messageRepository.moveMessage(messageId, currentFolderId, destinationFolderId)
        return Result.success(Unit) // Stub
    }
} 