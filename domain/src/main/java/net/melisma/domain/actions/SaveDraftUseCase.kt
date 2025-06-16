package net.melisma.domain.actions

import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft

interface SaveDraftUseCase {
    suspend operator fun invoke(
        account: Account,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message>
} 