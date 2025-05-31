package net.melisma.core_db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.MessageEntity

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND folderId = :folderId ORDER BY timestamp DESC")
    fun getMessagesForFolder(accountId: String, folderId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE accountId = :accountId AND folderId = :folderId")
    suspend fun deleteMessagesForFolder(accountId: String, folderId: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAllMessagesForAccount(accountId: String)

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    fun getMessageById(messageId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getMessageByIdSuspend(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND folderId = :folderId ORDER BY timestamp DESC")
    fun getMessagesPagingSource(
        accountId: String,
        folderId: String
    ): PagingSource<Int, MessageEntity>

    // Helper to clear messages for a specific folder, then insert new ones (transactional)
    @Transaction
    suspend fun clearAndInsertMessagesForFolder(
        accountId: String,
        folderId: String,
        messages: List<MessageEntity>
    ) {
        deleteMessagesForFolder(accountId, folderId)
        insertOrUpdateMessages(messages)
    }

    @Query("UPDATE messages SET needsSync = 0, lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun clearSyncState(messageId: String)

    @Query("UPDATE messages SET lastSyncError = :errorMessage, needsSync = 1 WHERE messageId = :messageId") // Ensure needsSync is true if error occurs
    suspend fun updateLastSyncError(messageId: String, errorMessage: String)

    @Query("UPDATE messages SET needsSync = :needsSync WHERE messageId = :messageId")
    suspend fun setNeedsSync(messageId: String, needsSync: Boolean)

    @Query("UPDATE messages SET isRead = :isRead, needsSync = :needsSync WHERE messageId = :messageId")
    suspend fun updateReadState(messageId: String, isRead: Boolean, needsSync: Boolean)

    @Query("UPDATE messages SET isStarred = :isStarred, needsSync = :needsSync WHERE messageId = :messageId")
    suspend fun updateStarredState(messageId: String, isStarred: Boolean, needsSync: Boolean)

    @Query("UPDATE messages SET isLocallyDeleted = :isLocallyDeleted, needsSync = :needsSync, lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun markAsLocallyDeleted(
        messageId: String,
        isLocallyDeleted: Boolean = true,
        needsSync: Boolean = true
    )

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deletePermanentlyById(messageId: String)
} 