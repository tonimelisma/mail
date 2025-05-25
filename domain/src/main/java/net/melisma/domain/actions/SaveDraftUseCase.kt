package net.melisma.domain.actions

import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft

interface SaveDraftUseCase {
    suspend operator fun invoke(
        accountId: String,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message>
} 