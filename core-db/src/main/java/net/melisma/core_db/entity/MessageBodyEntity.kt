package net.melisma.core_db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.SyncStatus

@Entity(
    tableName = "message_bodies",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE // If a message is deleted, its body should also be deleted
        )
    ],
    indices = [Index(value = ["message_id"], unique = true)] // Each message has one body
)
data class MessageBodyEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String, // Foreign key to MessageEntity's id

    @ColumnInfo(name = "content_type", defaultValue = "TEXT") // "TEXT" or "HTML"
    val contentType: String = "TEXT",

    @ColumnInfo(name = "content", typeAffinity = ColumnInfo.TEXT)
    val content: String?, // Full message body content

    @ColumnInfo(name = "last_fetched_ts", defaultValue = "0")
    val lastFetchedTimestamp: Long = 0L, // When was this body last fetched/updated

    // Potentially add a 'isLoading' or 'error' field if granular control over body sync is needed
    // For now, assuming that if a body exists, it's considered 'fetched'.
    // UI can decide to re-fetch based on age or user action.

    // Sync Metadata
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    val lastSyncError: String? = null,
    val isLocalOnly: Boolean = false,
    val needsFullSync: Boolean = false
) 