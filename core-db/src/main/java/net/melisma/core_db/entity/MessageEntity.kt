package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    @PrimaryKey val messageId: String,
    val accountId: String,
    val folderId: String,
    val threadId: String?,
    val subject: String?,
    val snippet: String?, // Corresponds to bodyPreview
    val senderName: String?,
    val senderAddress: String?,
    val recipientNames: List<String>?, // Needs TypeConverter
    val recipientAddresses: List<String>?, // Needs TypeConverter
    val timestamp: Long, // Unix timestamp, UTC, derived from receivedDateTime
    val sentTimestamp: Long?, // Unix timestamp, UTC, derived from sentDateTime. Added for alignment.
    val isRead: Boolean,
    val isStarred: Boolean, // Assuming this will be handled
    val hasAttachments: Boolean, // Assuming this will be handled
    val needsSync: Boolean = false,
    val lastSyncError: String? = null
) 