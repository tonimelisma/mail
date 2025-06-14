package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.entity.FolderEntity

@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateFolders(folders: List<FolderEntity>)

    @Delete
    suspend fun deleteFolders(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY name ASC")
    fun getFoldersForAccount(accountId: String): Flow<List<FolderEntity>>

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteAllFoldersForAccount(accountId: String)

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getFolderByIdSuspend(folderId: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND remoteId = :remoteId LIMIT 1")
    suspend fun getFolderByAccountIdAndRemoteId(accountId: String, remoteId: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND wellKnownType = :wellKnownType LIMIT 1")
    suspend fun getFolderByWellKnownTypeSuspend(
        accountId: String,
        wellKnownType: WellKnownFolderType
    ): FolderEntity?

    @Query("UPDATE folders SET lastSuccessfulSyncTimestamp = :timestamp, syncStatus = 'SYNCED', lastSyncError = null WHERE id = :folderId")
    suspend fun updateLastSuccessfulSync(folderId: String, timestamp: Long)

    @Query("UPDATE folders SET syncStatus = :syncStatus, lastSyncError = :errorMessage WHERE id = :folderId")
    suspend fun updateSyncStatusAndError(
        folderId: String,
        syncStatus: EntitySyncStatus,
        errorMessage: String?
    )
    
    @Query("UPDATE folders SET syncStatus = :syncStatus WHERE id = :folderId")
    suspend fun updateSyncStatus(folderId: String, syncStatus: EntitySyncStatus)

    @Query("DELETE FROM folders WHERE accountId = :accountId AND remoteId IN (:remoteIds)")
    suspend fun deleteFoldersByRemoteIds(accountId: String, remoteIds: List<String>): Int
} 