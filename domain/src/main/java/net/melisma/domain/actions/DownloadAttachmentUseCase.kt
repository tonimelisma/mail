package net.melisma.domain.actions

interface DownloadAttachmentUseCase {
    suspend operator fun invoke(
        accountId: String,
        messageId: String,
        attachmentId: String
    ): Result<ByteArray>
} 