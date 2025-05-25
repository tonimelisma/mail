package net.melisma.domain.actions

import net.melisma.core_data.model.Account
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class DeleteMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(account: Account, messageId: String): Result<Unit> {
        return messageRepository.deleteMessage(account, messageId)
    }
} 