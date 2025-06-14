package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "message_folder_junction",
    primaryKeys = ["messageId", "folderId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["folderId"])
    ]
)
data class MessageFolderJunction(
    val messageId: String,
    val folderId: String
) 