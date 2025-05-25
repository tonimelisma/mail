package net.melisma.domain.actions

import android.util.Log
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class DefaultCreateDraftUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : CreateDraftUseCase {
    override suspend operator fun invoke(
        accountId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        Log.d(
            "DefaultCreateDraftUseCase",
            "Invoked for accountId: $accountId, draftDetails: $draftDetails"
        )
        // return messageRepository.createDraftMessage(accountId, draftDetails) // Actual call commented out for stub
        return Result.failure(NotImplementedError("DefaultCreateDraftUseCase not implemented"))
    }
} 