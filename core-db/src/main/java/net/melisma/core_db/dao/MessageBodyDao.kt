package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_data.model.SyncStatus

@Dao
interface MessageBodyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessageBody(messageBody: MessageBodyEntity)

    @Query("SELECT * FROM message_bodies WHERE message_id = :messageId")
    fun getMessageBody(messageId: String): Flow<MessageBodyEntity?>

    @Query("SELECT * FROM message_bodies WHERE message_id = :messageId")
    suspend fun getBodyForMessage(messageId: String): MessageBodyEntity?

    @Query("SELECT * FROM message_bodies WHERE message_id = :messageId")
    suspend fun getMessageBodyByIdSuspend(messageId: String): MessageBodyEntity?

    @Query("DELETE FROM message_bodies WHERE message_id = :messageId")
    suspend fun deleteMessageBody(messageId: String): Int

    @Query("SELECT * FROM message_bodies")
    suspend fun getAllMessageBodies(): List<MessageBodyEntity>

    @Query("""
        UPDATE message_bodies
        SET content = :newContent,
            content_type = :newContentType,
            size_in_bytes = :newSizeInBytes,
            last_fetched_ts = :newLastFetchedTimestamp,
            syncStatus = :newSyncStatus,
            lastSuccessfulSyncTimestamp = :newLastSuccessfulSyncTimestamp,
            lastSyncAttemptTimestamp = :newLastAttemptTimestamp,
            lastSyncError = NULL
        WHERE message_id = :messageId
    """)
    suspend fun updateBodyContentAndSyncState(
        messageId: String,
        newContent: String?,
        newContentType: String,
        newSizeInBytes: Long,
        newLastFetchedTimestamp: Long,
        newSyncStatus: SyncStatus,
        newLastSuccessfulSyncTimestamp: Long?,
        newLastAttemptTimestamp: Long
    )

    @Query("""
        UPDATE message_bodies
        SET syncStatus = :newSyncStatus,
            lastSyncError = :newLastSyncError,
            lastSyncAttemptTimestamp = :newLastSyncAttemptTimestamp
        WHERE message_id = :messageId
    """)
    suspend fun updateSyncStatusAndError(
        messageId: String,
        newSyncStatus: SyncStatus,
        newLastSyncError: String?,
        newLastSyncAttemptTimestamp: Long
    )

    // You might add queries to get bodies that haven't been fetched recently, etc.
    // For now, basic CRUD operations.
} 