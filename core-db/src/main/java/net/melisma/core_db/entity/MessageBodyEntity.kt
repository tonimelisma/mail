package net.melisma.core_db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.EntitySyncStatus

@Entity(
    tableName = "message_bodies",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE // If a message is deleted, its body should also be deleted
        )
    ],
    indices = [
        Index(value = ["message_id"], unique = true),
        Index(value = ["syncStatus"])
    ]
)
data class MessageBodyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "message_id")
    val messageId: String, // Foreign key to MessageEntity's id

    @ColumnInfo(name = "content_type", defaultValue = "TEXT") // "TEXT" or "HTML"
    val contentType: String = "TEXT",

    @ColumnInfo(name = "content", typeAffinity = ColumnInfo.TEXT)
    val content: String?, // Full message body content

    @ColumnInfo(name = "size_in_bytes", defaultValue = "0")
    val sizeInBytes: Long = 0L, // Size of the content string in bytes

    @ColumnInfo(name = "last_fetched_ts", defaultValue = "0")
    val lastFetchedTimestamp: Long = 0L, // When was this body last fetched/updated

    // Potentially add a 'isLoading' or 'error' field if granular control over body sync is needed
    // For now, assuming that if a body exists, it's considered 'fetched'.
    // UI can decide to re-fetch based on age or user action.

    // Sync Metadata
    val syncStatus: EntitySyncStatus = EntitySyncStatus.SYNCED,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    val lastSyncError: String? = null,
    val isLocalOnly: Boolean = false,
    val needsFullSync: Boolean = false
) 