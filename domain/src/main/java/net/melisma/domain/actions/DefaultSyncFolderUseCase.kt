package net.melisma.domain.actions

import android.util.Log
import net.melisma.core_data.repository.FolderRepository
import javax.inject.Inject

class DefaultSyncFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository
) : SyncFolderUseCase {
    override suspend operator fun invoke(accountId: String, folderId: String): Result<Unit> {
        Log.d("DefaultSyncFolderUseCase", "Invoked for accountId: $accountId, folderId: $folderId")
        // return folderRepository.syncFolderContents(accountId, folderId) // Actual call commented out for stub
        return Result.success(Unit)
        // Or return Result.failure(NotImplementedError("DefaultSyncFolderUseCase not implemented"))
    }
} 