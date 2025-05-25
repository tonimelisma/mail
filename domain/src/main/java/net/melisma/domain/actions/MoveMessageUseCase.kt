package net.melisma.domain.actions

import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class MoveMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        account: net.melisma.core_data.model.Account,
        messageId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        return messageRepository.moveMessage(
            account,
            messageId,
            currentFolderId,
            destinationFolderId
        )
    }
} 