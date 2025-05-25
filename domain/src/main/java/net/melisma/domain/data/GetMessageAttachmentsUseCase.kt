package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Attachment

interface GetMessageAttachmentsUseCase {
    operator fun invoke(accountId: String, messageId: String): Flow<List<Attachment>>
} 