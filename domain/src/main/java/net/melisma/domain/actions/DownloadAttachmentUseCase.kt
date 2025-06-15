package net.melisma.domain.actions

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Attachment

interface DownloadAttachmentUseCase {
    suspend operator fun invoke(
        accountId: String,
        messageId: String,
        attachment: Attachment
    ): Flow<String?>
} 