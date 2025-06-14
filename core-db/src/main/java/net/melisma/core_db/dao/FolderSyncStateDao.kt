package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.FolderSyncStateEntity

@Dao
interface FolderSyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: FolderSyncStateEntity)

    @Query("SELECT * FROM folder_sync_state WHERE folderId = :folderId")
    fun observeState(folderId: String): Flow<FolderSyncStateEntity?>

    @Query("DELETE FROM folder_sync_state WHERE folderId = :folderId")
    suspend fun delete(folderId: String)
} 