package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.EntitySyncStatus

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        ),
        // Folder relationship now handled through MessageFolderJunction table
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["threadId"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    var messageId: String?, // Remote ID, nullable for local-only messages
    val accountId: String,
    // Folder linkage stored in MessageFolderJunction
    var threadId: String?,
    var subject: String?,
    var snippet: String?, // Corresponds to bodyPreview
    var body: String? = null, // Full body, only populated on demand or for drafts/outbox
    var senderName: String?,
    var senderAddress: String?,
    var recipientNames: List<String>?, // Needs TypeConverter
    var recipientAddresses: List<String>?, // Needs TypeConverter
    var timestamp: Long, // Unix timestamp, UTC, derived from receivedDateTime
    var sentTimestamp: Long?, // Unix timestamp, UTC, derived from sentDateTime. Added for alignment.
    var isRead: Boolean,
    var isStarred: Boolean = false,
    var hasAttachments: Boolean = false,
    var isLocallyDeleted: Boolean = false, // Added for optimistic deletion
    // var needsSync: Boolean = false, // Replaced by syncStatus
    var lastSyncError: String? = null, // Reused for sync metadata
    // Draft and Outbox support
    var isDraft: Boolean = false,
    var isOutbox: Boolean = false, // Added for outbox functionality
    var draftType: String? = null, // "NEW", "REPLY", "FORWARD"
    var draftParentId: String? = null, // For reply/forward chains
    var sendAttempts: Int = 0,
    var lastSendError: String? = null,
    var scheduledSendTime: Long? = null, // For future: scheduled sending

    // Sync Metadata
    var syncStatus: EntitySyncStatus = EntitySyncStatus.SYNCED,
    var lastSyncAttemptTimestamp: Long? = null,
    var lastSuccessfulSyncTimestamp: Long?,
    // lastSyncError is already present above
    var isLocalOnly: Boolean = false,
    var needsFullSync: Boolean = false,
    var lastAccessedTimestamp: Long? = null // Added for cache eviction policy
) 