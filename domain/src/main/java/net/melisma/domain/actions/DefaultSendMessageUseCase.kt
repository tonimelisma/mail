package net.melisma.domain.actions

import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MessageDraft

class DefaultSendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : SendMessageUseCase {
    override suspend operator fun invoke(draft: MessageDraft, account: Account): Result<String> {
        Timber.d(
            "Invoked for accountId: ${account.id}, draft: $draft"
        )
        return messageRepository.sendMessage(draft, account)
    }
} 