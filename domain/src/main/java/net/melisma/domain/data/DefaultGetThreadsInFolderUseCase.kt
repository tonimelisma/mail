package net.melisma.domain.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.repository.FolderRepository // Corrected import
import timber.log.Timber
import javax.inject.Inject

class DefaultGetThreadsInFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository
) : GetThreadsInFolderUseCase {
    override operator fun invoke(accountId: String, folderId: String): Flow<List<MailThread>> {
        Timber.d(
            "Invoked for accountId: $accountId, folderId: $folderId"
        )
        // folderRepository.getThreadsInFolder(accountId, folderId) // Actual call commented out for stub
        return flowOf(emptyList())
    }
} 