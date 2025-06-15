package net.melisma.domain.actions

import net.melisma.core_data.repository.FolderRepository
import timber.log.Timber
import javax.inject.Inject

class DefaultSyncFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository
) : SyncFolderUseCase {
    override suspend operator fun invoke(accountId: String, folderId: String): Result<Unit> {
        Timber.d("Invoked for accountId: $accountId, folderId: $folderId")
        return folderRepository.syncFolderContents(accountId, folderId)
    }
} 