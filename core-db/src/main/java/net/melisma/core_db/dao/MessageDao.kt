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

    @Query("""
        SELECT m.* FROM messages AS m
        INNER JOIN message_folder_junction AS j ON m.id = j.messageId
        WHERE j.folderId = :folderId AND m.accountId = :accountId
        ORDER BY m.timestamp DESC
    """)
    fun getMessagesForFolder(accountId: String, folderId: String): Flow<List<MessageEntity>>

    @Query("""
        DELETE FROM messages WHERE id IN (
            SELECT m.id FROM messages m
            INNER JOIN message_folder_junction j ON m.id = j.messageId
            WHERE j.folderId = :folderId
        )
    """)
    suspend fun deleteMessagesByFolder(folderId: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAllMessagesForAccount(accountId: String)

    @Query("SELECT * FROM messages WHERE id = :id")
    fun getMessageById(id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageByIdSuspend(id: String): MessageEntity?

    @Query("""
        SELECT COUNT(*) FROM messages AS m
        INNER JOIN message_folder_junction j ON m.id = j.messageId
        WHERE j.folderId = :folderId AND m.accountId = :accountId
    """)
    suspend fun getMessagesCountForFolder(accountId: String, folderId: String): Int

    @Query("""
        SELECT m.* FROM messages AS m
        INNER JOIN message_folder_junction AS j ON m.id = j.messageId
        WHERE j.folderId = :folderId AND m.accountId = :accountId
        ORDER BY m.timestamp DESC
    """)
    fun getMessagesPagingSource(accountId: String, folderId: String): PagingSource<Int, MessageEntity>

    // NEW: PagingSource that returns messages belonging to any INBOX folder across all accounts.
    @Query(
        """
        SELECT m.* FROM messages AS m
        INNER JOIN message_folder_junction j ON m.id = j.messageId
        INNER JOIN folders f ON j.folderId = f.id
        WHERE f.wellKnownType = 'INBOX'
        AND (:filterUnread = 0 OR m.isRead = 0)
        AND (:filterStarred = 0 OR m.isStarred = 1)
        ORDER BY m.timestamp DESC
        """
    )
    fun getUnifiedInboxPagingSource(
        filterUnread: Boolean,
        filterStarred: Boolean
    ): PagingSource<Int, MessageEntity>

    @Query("UPDATE messages SET isRead = :isRead WHERE id = :messageId")
    suspend fun updateReadStatus(messageId: String, isRead: Boolean)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :messageId")
    suspend fun updateStarredStatus(messageId: String, isStarred: Boolean)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String): Int

    @Query("DELETE FROM messages WHERE messageId IN (:remoteMessageIds)")
    suspend fun deleteMessagesByRemoteIds(remoteMessageIds: List<String>): Int

    @Query("""
        SELECT m.* FROM messages m
        LEFT JOIN message_folder_junction j ON m.id = j.messageId
        WHERE m.accountId = :accountId
        AND (m.subject LIKE :query OR m.snippet LIKE :query)
        AND (:folderId IS NULL OR j.folderId = :folderId)
        ORDER BY m.timestamp DESC
    """)
    fun searchMessages(query: String, accountId: String, folderId: String?): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET lastAccessedTimestamp = :timestamp WHERE id = :messageDbId")
    suspend fun updateLastAccessedTimestamp(messageDbId: String, timestamp: Long)

    @Query("UPDATE messages SET syncStatus = :syncStatus, lastSyncError = NULL WHERE id = :messageId")
    suspend fun setSyncStatus(messageId: String, syncStatus: EntitySyncStatus)

    @Query("UPDATE messages SET syncStatus = 'ERROR', lastSyncError = :errorMessage WHERE id = :messageId")
    suspend fun setSyncError(messageId: String, errorMessage: String)

    @Query("UPDATE messages SET body = :body WHERE id = :messageId")
    suspend fun updateMessageBody(messageId: String, body: String)

    // Returns messages eligible for cache eviction based on policy.
    @Query("""
        SELECT * FROM messages 
        WHERE (lastAccessedTimestamp IS NULL OR lastAccessedTimestamp < :maxLastAccessedTimestampMillis) 
        AND (timestamp < :maxSentTimestampMillis)
        AND syncStatus NOT IN (:excludedSyncStates) 
        AND isDraft = 0 AND isOutbox = 0
    """)
    suspend fun getCacheEvictionCandidates(
        maxLastAccessedTimestampMillis: Long,
        maxSentTimestampMillis: Long,
        excludedSyncStates: List<String>
    ): List<MessageEntity>

    // --- Legacy helper queries kept temporarily ---

    @Query("SELECT id FROM messages WHERE threadId = :threadId")
    suspend fun getMessageIdsByThreadId(threadId: String): List<String>

    @Query("UPDATE messages SET isRead = :isRead WHERE id IN (:messageIds)")
    suspend fun updateReadStateForMessages(messageIds: List<String>, isRead: Boolean)

    @Query("UPDATE messages SET lastSyncError = :error WHERE id = :messageId")
    suspend fun updateLastSyncError(messageId: String, error: String)

    // --- Helpers added to satisfy legacy repository usage (will be removed after refactor) ---

    @Query("UPDATE messages SET syncStatus = :syncStatus WHERE id IN (:messageIds)")
    suspend fun setSyncStatusForMessages(messageIds: List<String>, syncStatus: EntitySyncStatus)

    // Legacy one-to-many helpers removed 2025-07-??.  Thread operations now rely solely on
    // junction-table aware helpers (getMessageIdsByThreadId & DAO label ops).

    @Query("""
        SELECT MIN(timestamp) FROM messages m
        INNER JOIN message_folder_junction j ON m.id = j.messageId
        WHERE j.folderId = :folderId
    """)
    suspend fun getOldestMessageTimestamp(folderId: String): Long?

    @Query("""
        SELECT * FROM messages
        WHERE accountId = :accountId
          AND hasFullBodyCached = 0
          AND isDraft = 0
          AND isOutbox = 0
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getMessagesMissingBody(accountId: String, limit: Int = 50): List<MessageEntity>
} 