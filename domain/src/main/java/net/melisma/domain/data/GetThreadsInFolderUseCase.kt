package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.MailThread

interface GetThreadsInFolderUseCase {
    operator fun invoke(accountId: String, folderId: String): Flow<List<MailThread>>
} 