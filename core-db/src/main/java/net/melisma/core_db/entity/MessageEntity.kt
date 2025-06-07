package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.SyncStatus

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE // Or SET_NULL if messages can exist without a folder after deletion
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["folderId"]),
        Index(value = ["threadId"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val messageId: String?, // Remote ID, nullable for local-only messages
    val accountId: String,
    val folderId: String,
    val threadId: String?,
    val subject: String?,
    val snippet: String?, // Corresponds to bodyPreview
    val body: String?, // Full body, stored separately in MessageBodyEntity but can be denormalized here for simplicity
    val senderName: String?,
    val senderAddress: String?,
    val recipientNames: List<String>?, // Needs TypeConverter
    val recipientAddresses: List<String>?, // Needs TypeConverter
    val timestamp: Long, // Unix timestamp, UTC, derived from receivedDateTime
    val sentTimestamp: Long?, // Unix timestamp, UTC, derived from sentDateTime. Added for alignment.
    val isRead: Boolean,
    val isStarred: Boolean, // Assuming this will be handled
    val hasAttachments: Boolean, // Assuming this will be handled
    val isLocallyDeleted: Boolean = false, // Added for optimistic deletion
    // val needsSync: Boolean = false, // Replaced by syncStatus
    val lastSyncError: String? = null, // Reused for sync metadata
    // Draft and Outbox support
    val isDraft: Boolean = false,
    val isOutbox: Boolean = false,
    val draftType: String? = null, // "NEW", "REPLY", "FORWARD"
    val draftParentId: String? = null, // For reply/forward chains
    val sendAttempts: Int = 0,
    val lastSendError: String? = null,
    val scheduledSendTime: Long? = null, // For future: scheduled sending

    // Sync Metadata
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    // lastSyncError is already present above
    val isLocalOnly: Boolean = false,
    val needsFullSync: Boolean = false,
    val lastAccessedTimestamp: Long? = null // Added for cache eviction policy
) 