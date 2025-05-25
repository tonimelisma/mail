package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Message

interface SearchMessagesUseCase {
    operator fun invoke(
        accountId: String,
        query: String,
        folderId: String? = null
    ): Flow<List<Message>>
} 