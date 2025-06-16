package net.melisma.domain.actions

import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultCreateDraftUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : CreateDraftUseCase {
    override suspend operator fun invoke(
        account: Account,
        draftDetails: MessageDraft
    ): Result<Message> {
        Timber.d(
            "Invoked for accountId: ${account.id}, draftDetails: $draftDetails"
        )
        return messageRepository.createDraftMessage(account, draftDetails)
    }
} 