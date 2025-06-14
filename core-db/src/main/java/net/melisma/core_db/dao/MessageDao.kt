package net.melisma.core_db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_db.entity.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND folderId = :folderId ORDER BY timestamp DESC")
    fun getMessagesForFolder(accountId: String, folderId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun deleteMessagesByFolder(folderId: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAllMessagesForAccount(accountId: String)

    @Query("SELECT * FROM messages WHERE id = :id")
    fun getMessageById(id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageByIdSuspend(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE accountId = :accountId AND folderId = :folderId")
    suspend fun getMessagesCountForFolder(accountId: String, folderId: String): Int

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND folderId = :folderId ORDER BY timestamp DESC")
    fun getMessagesPagingSource(accountId: String, folderId: String): PagingSource<Int, MessageEntity>

    @Query("UPDATE messages SET isRead = :isRead WHERE id = :messageId")
    suspend fun updateReadStatus(messageId: String, isRead: Boolean)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :messageId")
    suspend fun updateStarredStatus(messageId: String, isStarred: Boolean)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String): Int

    @Query("DELETE FROM messages WHERE messageId IN (:remoteMessageIds)")
    suspend fun deleteMessagesByRemoteIds(remoteMessageIds: List<String>): Int

    @Query("UPDATE messages SET folderId = :newFolderId WHERE id = :messageId")
    suspend fun updateFolderId(messageId: String, newFolderId: String)

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND (subject LIKE :query OR snippet LIKE :query) AND (:folderId IS NULL OR folderId = :folderId) ORDER BY timestamp DESC")
    fun searchMessages(query: String, accountId: String, folderId: String?): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET lastAccessedTimestamp = :timestamp WHERE id = :messageDbId")
    suspend fun updateLastAccessedTimestamp(messageDbId: String, timestamp: Long)

    @Query("UPDATE messages SET syncStatus = :syncStatus, lastSyncError = NULL WHERE id = :messageId")
    suspend fun setSyncStatus(messageId: String, syncStatus: EntitySyncStatus)

    @Query("UPDATE messages SET syncStatus = 'ERROR', lastSyncError = :errorMessage WHERE id = :messageId")
    suspend fun setSyncError(messageId: String, errorMessage: String)

    @Query("UPDATE messages SET body = :body WHERE id = :messageId")
    suspend fun updateMessageBody(messageId: String, body: String)

    // Returns messages eligible for cache eviction based on policy. Simplified for now.
    @Query("SELECT * FROM messages WHERE (lastAccessedTimestamp IS NULL OR lastAccessedTimestamp < :maxLastAccessedTimestampMillis) AND syncStatus NOT IN (:excludedSyncStates) AND isDraft = 0 AND isOutbox = 0")
    suspend fun getCacheEvictionCandidates(maxLastAccessedTimestampMillis: Long, excludedSyncStates: List<String>): List<MessageEntity>

    // --- Legacy helper queries kept temporarily ---

    @Query("SELECT id FROM messages WHERE threadId = :threadId")
    suspend fun getMessageIdsByThreadId(threadId: String): List<String>

    @Query("UPDATE messages SET isRead = :isRead WHERE id IN (:messageIds)")
    suspend fun updateReadStateForMessages(messageIds: List<String>, isRead: Boolean)

    @Query("SELECT id FROM messages WHERE threadId = :threadId AND folderId = :folderId")
    suspend fun getMessageIdsByThreadIdAndFolder(threadId: String, folderId: String): List<String>

    @Query("UPDATE messages SET folderId = :newFolderId WHERE id IN (:messageIds)")
    suspend fun updateFolderIdForMessages(messageIds: List<String>, newFolderId: String)

    @Query("UPDATE messages SET isLocallyDeleted = 1 WHERE id IN (:messageIds)")
    suspend fun markMessagesAsLocallyDeleted(messageIds: List<String>)

    @Query("UPDATE messages SET lastSyncError = :error WHERE id = :messageId")
    suspend fun updateLastSyncError(messageId: String, error: String)
} 