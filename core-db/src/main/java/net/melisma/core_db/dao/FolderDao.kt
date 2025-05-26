package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.FolderEntity

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateFolders(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY displayName ASC")
    fun getFoldersForAccount(accountId: String): Flow<List<FolderEntity>>

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteAllFoldersForAccount(accountId: String)

    @Query("DELETE FROM folders")
    suspend fun clearAllFolders() // Potentially for logout or full cache clear
} 