package net.melisma.domain.actions

import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Attachment

class DefaultDownloadAttachmentUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : DownloadAttachmentUseCase {
    override suspend operator fun invoke(
        accountId: String,
        messageId: String,
        attachment: Attachment
    ): Flow<String?> {
        Timber.d(
            "Invoked for accountId: $accountId, messageId: $messageId, attachmentId: ${attachment.id}"
        )
        return messageRepository.downloadAttachment(accountId, messageId, attachment)
    }
} 