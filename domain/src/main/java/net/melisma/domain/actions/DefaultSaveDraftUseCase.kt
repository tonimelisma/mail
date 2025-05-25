package net.melisma.domain.actions

import android.util.Log
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class DefaultSaveDraftUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : SaveDraftUseCase {
    override suspend operator fun invoke(
        accountId: String,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        Log.d(
            "DefaultSaveDraftUseCase",
            "Invoked for accountId: $accountId, messageId: $messageId, draftDetails: $draftDetails"
        )
        // return messageRepository.updateDraftMessage(accountId, messageId, draftDetails) // Actual call commented out for stub
        return Result.failure(NotImplementedError("DefaultSaveDraftUseCase not implemented"))
    }
} 