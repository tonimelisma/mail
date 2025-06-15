package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.EntitySyncStatus

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["remoteAttachmentId"])
    ]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageId: String,
    val accountId: String,
    val remoteAttachmentId: String?,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val contentId: String? = null,
    val isInline: Boolean = false,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val downloadTimestamp: Long? = null,

    // Sync Metadata
    val syncStatus: EntitySyncStatus = EntitySyncStatus.SYNCED,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    val lastSyncError: String? = null, // Unified error tracking field
    val isLocalOnly: Boolean = false,
    val needsFullSync: Boolean = false
)