package net.melisma.domain.actions

import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft

interface CreateDraftUseCase {
    suspend operator fun invoke(accountId: String, draftDetails: MessageDraft): Result<Message>
} 