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
    suspend fun getFolderIdsForMessage(messageId: String): List<String>

    @Query("SELECT folderId FROM message_folder_junction WHERE messageId IN (:messageIds)")
    suspend fun getFolderIdsForMessageIds(messageIds: Set<String>): List<String>

    @Query("SELECT messageId FROM message_folder_junction WHERE folderId = :folderId")
    fun getMessageIdsForFolder(folderId: String): Flow<List<String>>

    /**
     * Convenience helper to replace all folder links for a given message inside a single transaction.
     */
    @androidx.room.Transaction
    suspend fun replaceFoldersForMessage(messageId: String, newFolderIds: List<String>) {
        deleteByMessage(messageId)
        if (newFolderIds.isNotEmpty()) {
            insertAll(newFolderIds.map { fid -> MessageFolderJunction(messageId, fid) })
        }
    }

    /**
     * Inserts a single message-folder link. Safe on conflict (REPLACE).
     */
    suspend fun addLabel(messageId: String, folderId: String) {
        insertAll(listOf(MessageFolderJunction(messageId, folderId)))
    }

    /**
     * Removes a single message-folder link.
     */
    @Query("DELETE FROM message_folder_junction WHERE messageId = :messageId AND folderId = :folderId")
    suspend fun removeLabel(messageId: String, folderId: String)
} 