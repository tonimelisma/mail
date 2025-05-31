package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["attachmentId"])
    ]
)
data class AttachmentEntity(
    @PrimaryKey
    val attachmentId: String,
    val messageId: String,
    val fileName: String,
    val size: Long,
    val contentType: String,
    val contentId: String? = null,
    val isInline: Boolean = false,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val downloadTimestamp: Long? = null,
    val downloadError: String? = null
)