package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_data.model.EntitySyncStatus

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
        newSyncStatus: EntitySyncStatus,
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
        newSyncStatus: EntitySyncStatus,
        newLastSyncError: String?,
        newLastSyncAttemptTimestamp: Long
    )

    @Query("SELECT SUM(size_in_bytes) FROM message_bodies mb JOIN messages m ON mb.message_id = m.id WHERE m.accountId = :accountId")
    suspend fun getTotalBodiesSizeForAccount(accountId: String): Long?

    @Query("SELECT SUM(size_in_bytes) FROM message_bodies")
    suspend fun getTotalBodiesSize(): Long?

    // You might add queries to get bodies that haven't been fetched recently, etc.
    // For now, basic CRUD operations.
} 