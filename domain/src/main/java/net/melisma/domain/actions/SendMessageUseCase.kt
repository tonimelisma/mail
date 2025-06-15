package net.melisma.domain.actions

import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MessageDraft

interface SendMessageUseCase {
    // messageId is the ID of the draft to send
    suspend operator fun invoke(draft: MessageDraft, account: Account): Result<String>
} 