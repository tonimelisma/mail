package net.melisma.core_db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.SyncStatus
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

    @Query("SELECT COUNT(*) FROM messages WHERE accountId = :accountId AND folderId = :folderId")
    suspend fun getMessagesCountForFolder(accountId: String, folderId: String): Int

    @Query("UPDATE messages SET folderId = :newFolderId, syncStatus = :syncStatus WHERE messageId = :messageId")
    suspend fun updateMessageFolderAndSyncState(
        messageId: String,
        newFolderId: String,
        syncStatus: SyncStatus
    )

    // Used by worker after successful API move to update local folderId and clear sync flags
    @Query("UPDATE messages SET folderId = :newFolderId, syncStatus = 'SYNCED', lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun updateFolderIdAndClearSyncStateOnSuccess(messageId: String, newFolderId: String)

    @Query("UPDATE messages SET folderId = :folderId, lastSyncError = :errorMessage, syncStatus = :syncStatus WHERE messageId = :messageId")
    suspend fun updateFolderIdSyncErrorAndStatus(
        messageId: String,
        folderId: String,
        errorMessage: String,
        syncStatus: SyncStatus
    )

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

    @Query("UPDATE messages SET syncStatus = 'SYNCED', lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun clearSyncState(messageId: String)

    @Query("UPDATE messages SET syncStatus = 'SYNCED', lastSyncError = NULL, folderId = :newFolderId WHERE messageId = :messageId")
    suspend fun clearSyncStateAndSetFolder(messageId: String, newFolderId: String)

    @Query("UPDATE messages SET lastSyncError = :errorMessage, syncStatus = 'ERROR' WHERE messageId = :messageId")
    suspend fun updateLastSyncError(messageId: String, errorMessage: String)

    @Query("UPDATE messages SET syncStatus = :syncStatus WHERE messageId = :messageId")
    suspend fun setSyncStatus(messageId: String, syncStatus: SyncStatus)

    @Query("UPDATE messages SET isRead = :isRead, syncStatus = :syncStatus WHERE messageId = :messageId")
    suspend fun updateReadState(messageId: String, isRead: Boolean, syncStatus: SyncStatus)

    @Query("UPDATE messages SET isRead = :isRead WHERE messageId = :messageId")
    suspend fun updateReadStatus(messageId: String, isRead: Boolean)

    @Query("UPDATE messages SET isStarred = :isStarred, syncStatus = :syncStatus WHERE messageId = :messageId")
    suspend fun updateStarredState(messageId: String, isStarred: Boolean, syncStatus: SyncStatus)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE messageId = :messageId")
    suspend fun updateStarredStatus(messageId: String, isStarred: Boolean)

    @Query("UPDATE messages SET isLocallyDeleted = :isLocallyDeleted, syncStatus = :syncStatus, lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun markAsLocallyDeleted(
        messageId: String,
        isLocallyDeleted: Boolean = true,
        syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD
    )

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deletePermanentlyById(messageId: String)

    // Deletes messages whose IDs are in the provided list
    @Query("DELETE FROM messages WHERE messageId IN (:messageIds)")
    suspend fun deleteMessagesByRemoteIds(messageIds: List<String>): Int

    @Query("UPDATE messages SET folderId = :newFolderId, syncStatus = :syncStatus, lastSyncError = :lastSyncError WHERE messageId = :messageId")
    suspend fun updateMessageFolderSyncStateAndError(
        messageId: String,
        newFolderId: String,
        syncStatus: SyncStatus,
        lastSyncError: String?
    )

    @Query("UPDATE messages SET folderId = :newFolderId WHERE messageId = :messageId")
    suspend fun updateFolderId(messageId: String, newFolderId: String)

    // For worker to update folderId and clear sync state on successful API move
    @Query("UPDATE messages SET folderId = :newFolderId, syncStatus = 'SYNCED', lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun updateFolderOnMoveSyncSuccess(messageId: String, newFolderId: String)

    // Draft operations
    @Query("SELECT * FROM messages WHERE accountId = :accountId AND isDraft = 1 ORDER BY timestamp DESC")
    fun getDraftsForAccount(accountId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET isDraft = :isDraft, syncStatus = :syncStatus WHERE messageId = :messageId")
    suspend fun updateDraftState(messageId: String, isDraft: Boolean, syncStatus: SyncStatus)

    @Query(
        """
        UPDATE messages 
        SET subject = :subject, 
            snippet = :snippet, 
            recipientNames = :recipientNames, 
            recipientAddresses = :recipientAddresses,
            timestamp = :timestamp,
            syncStatus = :syncStatus,
            draftType = :draftType,
            draftParentId = :draftParentId
        WHERE messageId = :messageId
    """
    )
    suspend fun updateDraftContent(
        messageId: String,
        subject: String?,
        snippet: String?,
        recipientNames: List<String>?,
        recipientAddresses: List<String>?,
        timestamp: Long,
        syncStatus: SyncStatus,
        draftType: String?,
        draftParentId: String?
    )

    // Outbox operations
    @Query("SELECT * FROM messages WHERE accountId = :accountId AND isOutbox = 1 ORDER BY timestamp DESC")
    fun getOutboxForAccount(accountId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET isOutbox = :isOutbox, isDraft = 0, syncStatus = :syncStatus WHERE messageId = :messageId")
    suspend fun moveToOutbox(messageId: String, isOutbox: Boolean = true, syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD)

    @Query("UPDATE messages SET sendAttempts = sendAttempts + 1, lastSendError = :error, syncStatus = 'ERROR' WHERE messageId = :messageId")
    suspend fun incrementSendAttempts(messageId: String, error: String?)

    @Query(
        """
        UPDATE messages 
        SET isOutbox = 0, 
            syncStatus = 'SYNCED', 
            lastSendError = NULL, 
            folderId = :sentFolderId,
            sentTimestamp = :sentTimestamp
        WHERE messageId = :messageId
    """
    )
    suspend fun markAsSent(messageId: String, sentFolderId: String, sentTimestamp: Long)

    @Query("UPDATE messages SET lastSendError = :error, syncStatus = 'ERROR' WHERE messageId = :messageId")
    suspend fun updateSendError(messageId: String, error: String)

    @Query("UPDATE messages SET lastSendError = NULL, syncStatus = 'PENDING_UPLOAD', sendAttempts = 0 WHERE messageId = :messageId")
    suspend fun prepareForRetry(messageId: String)

    @Query("SELECT * FROM messages WHERE (syncStatus = 'PENDING_UPLOAD' OR syncStatus = 'PENDING_DOWNLOAD' OR syncStatus = 'ERROR') AND (isDraft = 1 OR isOutbox = 1)")
    suspend fun getPendingSyncDraftsAndOutbox(): List<MessageEntity>

    // TODO: P3_CACHE - Refine query to also check for messages not marked as 'keep_offline' if such a flag is added later.
    @Query("SELECT * FROM messages WHERE timestamp < :maxTimestamp AND syncStatus != :pendingStatus AND isDraft = 0 AND isOutbox = 0")
    fun getMessagesOlderThan(maxTimestamp: Long, pendingStatus: SyncStatus = SyncStatus.PENDING_UPLOAD): List<MessageEntity>

    // Methods for thread operations
    @Query("SELECT messageId FROM messages WHERE threadId = :threadId AND accountId = :accountId")
    suspend fun getMessageIdsByThreadId(threadId: String, accountId: String): List<String>

    @Query("SELECT messageId FROM messages WHERE threadId = :threadId AND accountId = :accountId AND folderId = :folderId")
    suspend fun getMessageIdsByThreadIdAndFolder(
        threadId: String,
        accountId: String,
        folderId: String
    ): List<String>

    @Query("UPDATE messages SET isRead = :isRead, syncStatus = :syncStatus WHERE messageId IN (:messageIds)")
    suspend fun updateReadStateForMessages(
        messageIds: List<String>,
        isRead: Boolean,
        syncStatus: SyncStatus
    )

    @Query("UPDATE messages SET isLocallyDeleted = 1, syncStatus = :syncStatus WHERE messageId IN (:messageIds)")
    suspend fun markMessagesAsLocallyDeleted(messageIds: List<String>, syncStatus: SyncStatus)

    @Query("UPDATE messages SET folderId = :newFolderId, syncStatus = :syncStatus WHERE messageId IN (:messageIds)")
    suspend fun updateFolderIdForMessages(
        messageIds: List<String>,
        newFolderId: String,
        syncStatus: SyncStatus
    )

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND (subject LIKE :query OR snippet LIKE :query) AND (:folderId IS NULL OR folderId = :folderId) ORDER BY timestamp DESC")
    fun searchMessages(
        query: String,
        accountId: String,
        folderId: String?
    ): Flow<List<MessageEntity>>
}