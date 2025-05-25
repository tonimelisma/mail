package net.melisma.domain.actions

interface SendMessageUseCase {
    // messageId is the ID of the draft to send
    suspend operator fun invoke(accountId: String, messageId: String): Result<Unit>
} 