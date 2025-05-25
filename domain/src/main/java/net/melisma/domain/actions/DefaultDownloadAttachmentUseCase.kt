package net.melisma.domain.actions

import android.util.Log
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class DefaultDownloadAttachmentUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : DownloadAttachmentUseCase {
    override suspend operator fun invoke(
        accountId: String,
        messageId: String,
        attachmentId: String
    ): Result<ByteArray> {
        Log.d(
            "DefaultDownloadAttachmentUseCase",
            "Invoked for accountId: $accountId, messageId: $messageId, attachmentId: $attachmentId"
        )
        // return messageRepository.downloadAttachment(accountId, messageId, attachmentId) // Actual call commented out for stub
        return Result.failure(NotImplementedError("DefaultDownloadAttachmentUseCase not implemented"))
    }
} 