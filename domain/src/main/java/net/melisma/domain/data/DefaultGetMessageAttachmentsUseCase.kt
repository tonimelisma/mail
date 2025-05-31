package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultGetMessageAttachmentsUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : GetMessageAttachmentsUseCase {
    override operator fun invoke(accountId: String, messageId: String): Flow<List<Attachment>> {
        Timber.d(
            "Invoked for accountId: $accountId, messageId: $messageId"
        )
        // messageRepository.getMessageAttachments(accountId, messageId) // Actual call commented out for stub
        return flowOf(emptyList())
    }
} 