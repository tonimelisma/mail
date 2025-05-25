package net.melisma.domain.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.melisma.core_data.model.Message
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Inject

class DefaultSearchMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : SearchMessagesUseCase {
    override operator fun invoke(
        accountId: String,
        query: String,
        folderId: String?
    ): Flow<List<Message>> {
        Log.d(
            "DefaultSearchMessagesUseCase",
            "Invoked for accountId: $accountId, query: $query, folderId: $folderId"
        )
        // messageRepository.searchMessages(accountId, query, folderId) // Actual call commented out for stub
        return flowOf(emptyList())
    }
} 