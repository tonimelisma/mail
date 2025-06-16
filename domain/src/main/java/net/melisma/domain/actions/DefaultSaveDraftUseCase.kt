package net.melisma.domain.actions

import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultSaveDraftUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : SaveDraftUseCase {
    override suspend operator fun invoke(
        account: Account,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        Timber.d(
            "Invoked for accountId: ${account.id}, messageId: $messageId, draftDetails: $draftDetails"
        )
        return messageRepository.updateDraftMessage(account, messageId, draftDetails)
    }
} 