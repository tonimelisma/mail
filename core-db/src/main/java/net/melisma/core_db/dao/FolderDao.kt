package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.entity.FolderEntity
import timber.log.Timber

@Dao
interface FolderDao {

    // Internal helper methods for the transactional upsert
    @Insert(onConflict = OnConflictStrategy.ABORT) // Abort if somehow a duplicate ID is generated, though unlikely for UUIDs
    suspend fun insertFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Transaction
    suspend fun insertOrUpdateFolders(folders: List<FolderEntity>) {
        for (folder in folders) {
            if (folder.remoteId != null) {
                val existingFolder =
                    getFolderByAccountIdAndRemoteId(folder.accountId, folder.remoteId)
                if (existingFolder != null) {
                    // Preserve existing local ID, update other fields
                    updateFolder(folder.copy(id = existingFolder.id))
                } else {
                    // New folder for this remoteId
                    insertFolder(folder)
                }
            } else {
                // This folder doesn't have a remoteId. This might be a special local folder.
                // We assume its local 'id' is unique and try to insert it.
                // If there's a conflict on the primary key 'id', it will be handled by onConflict strategy of insertFolder.
                Timber.w("FolderDao: Attempting to insert folder with null remoteId. Name: ${folder.name}, ID: ${folder.id}. Ensure local ID is unique.")
                // Check if a folder with this local ID already exists to prevent crashes if ABORT is used.
                val folderWithSameLocalId = getFolderByIdSuspend(folder.id)
                if (folderWithSameLocalId != null) {
                    // If it's the *same* folder conceptually just being re-passed, update it.
                    // If it's a *different* folder trying to use an existing local ID, that's an issue.
                    // For simplicity here, we'll update. This relies on `folder.id` being correctly managed upstream for local-only folders.
                    updateFolder(folder)
                } else {
                    insertFolder(folder)
                }
            }
        }
    }

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY name ASC")
    fun getFoldersForAccount(accountId: String): Flow<List<FolderEntity>>

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteAllFoldersForAccount(accountId: String)

    @Query("DELETE FROM folders")
    suspend fun clearAllFolders() // Potentially for logout or full cache clear

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getFolderByIdSuspend(folderId: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :folderId")
    fun getFolderByIdFlow(folderId: String): Flow<FolderEntity?>

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND remoteId = :remoteId LIMIT 1")
    suspend fun getFolderByAccountIdAndRemoteId(accountId: String, remoteId: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND wellKnownType = :wellKnownType LIMIT 1")
    suspend fun getFolderByWellKnownTypeSuspend(
        accountId: String,
        wellKnownType: WellKnownFolderType
    ): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND wellKnownType = :wellKnownType LIMIT 1")
    fun getFolderByWellKnownTypeFlow(
        accountId: String,
        wellKnownType: WellKnownFolderType
    ): Flow<FolderEntity?>

    @Query("UPDATE folders SET nextPageToken = :nextPageToken, lastFullContentSyncTimestamp = :lastFullContentSyncTimestamp WHERE id = :folderId")
    suspend fun updatePagingTokens(
        folderId: String,
        nextPageToken: String?,
        lastFullContentSyncTimestamp: Long?
    )

    @Query("UPDATE folders SET lastSuccessfulSyncTimestamp = :timestamp, syncStatus = :syncStatus, lastSyncError = null WHERE id = :folderId")
    suspend fun updateLastSuccessfulSync(folderId: String, timestamp: Long, syncStatus: SyncStatus)

    @Query("UPDATE folders SET syncStatus = :syncStatus, lastSyncError = :errorMessage WHERE id = :folderId")
    suspend fun updateSyncStatusAndError(
        folderId: String,
        syncStatus: SyncStatus,
        errorMessage: String?
    )

    @Query("UPDATE folders SET messageListSyncToken = :syncToken WHERE id = :folderId")
    suspend fun updateMessageListSyncToken(folderId: String, syncToken: String?)

    // Deletes folders for a given account that match the provided remote IDs
    @Query("DELETE FROM folders WHERE accountId = :accountId AND remoteId IN (:remoteIds)")
    suspend fun deleteFoldersByRemoteIds(accountId: String, remoteIds: List<String>): Int
} 