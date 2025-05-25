package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Message // Assuming Message contains all details
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class GetMessageDetailsUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    // Assuming accountId is needed to correctly route in a multi-account scenario if MessageRepository isn't already account-specific
    // or if the getMessageDetails needs it.
    // The current MessageRepository interface methods like setTargetFolder take Account object.
    // Let's assume for now we also need account identifier for fetching specific message.
    suspend operator fun invoke(
        messageId: String,
        accountId: String
    ): Flow<Message?> { // Added suspend, Return Flow<Message?> to handle not found or errors gracefully
        // This method (getMessageDetails) needs to be added to MessageRepository
        return messageRepository.getMessageDetails(messageId, accountId)
    }
} 