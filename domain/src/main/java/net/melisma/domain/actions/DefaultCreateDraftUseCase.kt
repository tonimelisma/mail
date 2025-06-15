package net.melisma.domain.actions

import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultCreateDraftUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : CreateDraftUseCase {
    override suspend operator fun invoke(
        accountId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        Timber.d(
            "Invoked for accountId: $accountId, draftDetails: $draftDetails"
        )
        return messageRepository.createDraftMessage(accountId, draftDetails)
    }
} 