package net.melisma.domain.actions

interface SyncFolderUseCase {
    suspend operator fun invoke(accountId: String, folderId: String): Result<Unit>
} 