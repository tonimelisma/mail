package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.entity.FolderEntity

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateFolders(folders: List<FolderEntity>)

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

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND type = :type LIMIT 1")
    suspend fun getFolderIdByType(accountId: String, type: String): FolderEntity?

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
} 