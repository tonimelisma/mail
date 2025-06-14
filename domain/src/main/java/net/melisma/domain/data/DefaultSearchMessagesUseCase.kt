package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Message
import net.melisma.core_data.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultSearchMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) : SearchMessagesUseCase {
    override operator fun invoke(
        accountId: String,
        query: String,
        folderId: String?
    ): Flow<List<Message>> {
        Timber.d("SearchMessagesUseCase invoked (account=$accountId, folder=$folderId) query='$query'")
        return messageRepository.searchMessages(accountId, query, folderId)
    }
} 