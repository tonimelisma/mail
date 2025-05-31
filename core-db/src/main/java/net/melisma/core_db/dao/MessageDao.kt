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

    @Query("SELECT COUNT(*) FROM messages WHERE accountId = :accountId AND folderId = :folderId")
    suspend fun getMessagesCountForFolder(accountId: String, folderId: String): Int

    @Query("UPDATE messages SET folderId = :newFolderId, needsSync = :needsSync WHERE messageId = :messageId")
    suspend fun updateMessageFolderAndSyncState(
        messageId: String,
        newFolderId: String,
        needsSync: Boolean
    )

    // Used by worker after successful API move to update local folderId and clear sync flags
    @Query("UPDATE messages SET folderId = :newFolderId, needsSync = 0, lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun updateFolderIdAndClearSyncStateOnSuccess(messageId: String, newFolderId: String)

    @Query("UPDATE messages SET folderId = :folderId, lastSyncError = :errorMessage, needsSync = :needsSync WHERE messageId = :messageId")
    suspend fun updateFolderIdSyncErrorAndNeedsSync(
        messageId: String,
        folderId: String,
        errorMessage: String,
        needsSync: Boolean
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

    @Query("UPDATE messages SET needsSync = 0, lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun clearSyncState(messageId: String)

    @Query("UPDATE messages SET needsSync = 0, lastSyncError = NULL, folderId = :newFolderId WHERE messageId = :messageId")
    suspend fun clearSyncStateAndSetFolder(messageId: String, newFolderId: String)

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

    @Query("UPDATE messages SET folderId = :newFolderId, needsSync = :needsSync, lastSyncError = :lastSyncError WHERE messageId = :messageId")
    suspend fun updateMessageFolderSyncStateAndError(
        messageId: String,
        newFolderId: String,
        needsSync: Boolean,
        lastSyncError: String?
    )

    // For worker to update folderId and clear sync state on successful API move
    @Query("UPDATE messages SET folderId = :newFolderId, needsSync = 0, lastSyncError = NULL WHERE messageId = :messageId")
    suspend fun updateFolderOnMoveSyncSuccess(messageId: String, newFolderId: String)

    // Draft operations
    @Query("SELECT * FROM messages WHERE accountId = :accountId AND isDraft = 1 ORDER BY timestamp DESC")
    fun getDraftsForAccount(accountId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET isDraft = :isDraft, needsSync = :needsSync WHERE messageId = :messageId")
    suspend fun updateDraftState(messageId: String, isDraft: Boolean, needsSync: Boolean)

    @Query(
        """
        UPDATE messages 
        SET subject = :subject, 
            snippet = :snippet, 
            recipientNames = :recipientNames, 
            recipientAddresses = :recipientAddresses,
            timestamp = :timestamp,
            needsSync = :needsSync,
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
        needsSync: Boolean,
        draftType: String?,
        draftParentId: String?
    )

    // Outbox operations
    @Query("SELECT * FROM messages WHERE accountId = :accountId AND isOutbox = 1 ORDER BY timestamp DESC")
    fun getOutboxForAccount(accountId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET isOutbox = :isOutbox, isDraft = 0, needsSync = :needsSync WHERE messageId = :messageId")
    suspend fun moveToOutbox(messageId: String, isOutbox: Boolean = true, needsSync: Boolean = true)

    @Query("UPDATE messages SET sendAttempts = sendAttempts + 1, lastSendError = :error WHERE messageId = :messageId")
    suspend fun incrementSendAttempts(messageId: String, error: String?)

    @Query(
        """
        UPDATE messages 
        SET isOutbox = 0, 
            needsSync = 0, 
            lastSendError = NULL, 
            folderId = :sentFolderId,
            sentTimestamp = :sentTimestamp
        WHERE messageId = :messageId
    """
    )
    suspend fun markAsSent(messageId: String, sentFolderId: String, sentTimestamp: Long)

    @Query("UPDATE messages SET lastSendError = :error, needsSync = 1 WHERE messageId = :messageId")
    suspend fun updateSendError(messageId: String, error: String)

    @Query("UPDATE messages SET lastSendError = NULL, needsSync = 1, sendAttempts = 0 WHERE messageId = :messageId")
    suspend fun prepareForRetry(messageId: String)

    @Query("SELECT * FROM messages WHERE needsSync = 1 AND (isDraft = 1 OR isOutbox = 1)")
    suspend fun getPendingSyncDraftsAndOutbox(): List<MessageEntity>
} 