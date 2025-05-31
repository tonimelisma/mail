package net.melisma.domain.actions

import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultSendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : SendMessageUseCase {
    override suspend operator fun invoke(accountId: String, messageId: String): Result<Unit> {
        Timber.d(
            "Invoked for accountId: $accountId, messageId: $messageId"
        )
        // return messageRepository.sendMessage(accountId, messageId) // Actual call commented out for stub
        return Result.success(Unit)
        // Or return Result.failure(NotImplementedError("DefaultSendMessageUseCase not implemented"))
    }
} 