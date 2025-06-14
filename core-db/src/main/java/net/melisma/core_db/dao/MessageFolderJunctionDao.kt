package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.MessageFolderJunction

@Dao
interface MessageFolderJunctionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(junctions: List<MessageFolderJunction>)

    @Query("DELETE FROM message_folder_junction WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String)

    @Query("DELETE FROM message_folder_junction WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: String)

    @Query("SELECT folderId FROM message_folder_junction WHERE messageId = :messageId")
    suspend fun getFoldersForMessage(messageId: String): List<String>

    @Query("SELECT messageId FROM message_folder_junction WHERE folderId = :folderId")
    fun getMessageIdsForFolder(folderId: String): Flow<List<String>>
} 