package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.melisma.core_db.entity.RemoteKeyEntity

@Dao
interface RemoteKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(remoteKey: RemoteKeyEntity)

    @Query("SELECT * FROM remote_keys WHERE folderId = :folderId")
    suspend fun getRemoteKeyForFolder(folderId: String): RemoteKeyEntity?

    @Query("DELETE FROM remote_keys WHERE folderId = :folderId")
    suspend fun deleteRemoteKeyForFolder(folderId: String)

    @Query("DELETE FROM remote_keys")
    suspend fun clearAllRemoteKeys()
} 