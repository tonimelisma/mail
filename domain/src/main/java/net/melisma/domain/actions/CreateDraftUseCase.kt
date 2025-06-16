package net.melisma.domain.actions

import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft

interface CreateDraftUseCase {
    suspend operator fun invoke(account: Account, draftDetails: MessageDraft): Result<Message>
} 