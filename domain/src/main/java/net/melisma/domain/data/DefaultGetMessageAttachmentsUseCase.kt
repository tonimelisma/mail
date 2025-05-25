package net.melisma.domain.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class DefaultGetMessageAttachmentsUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : GetMessageAttachmentsUseCase {
    override operator fun invoke(accountId: String, messageId: String): Flow<List<Attachment>> {
        Log.d(
            "DefaultGetMessageAttachmentsUseCase",
            "Invoked for accountId: $accountId, messageId: $messageId"
        )
        // messageRepository.getMessageAttachments(accountId, messageId) // Actual call commented out for stub
        return flowOf(emptyList())
    }
} 