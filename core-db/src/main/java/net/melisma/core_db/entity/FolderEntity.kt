package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_data.model.WellKnownFolderType

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE // If an account is deleted, its folders are also deleted
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["accountId", "remoteId"], unique = true),
        Index(value = ["wellKnownType"])
    ]
)
data class FolderEntity(
    @PrimaryKey val id: String, // Unique local ID for the folder
    val accountId: String,
    val remoteId: String?, // ID used by the remote server (e.g., Gmail Label ID, Outlook Folder ID)
    val name: String?,
    val wellKnownType: WellKnownFolderType?,
    val unreadCount: Int? = 0,
    val totalCount: Int? = 0,
    val canHaveChildren: Boolean? = false,
    val parentFolderId: String? = null, // Local DB ID of parent folder
    val syncStatus: EntitySyncStatus = EntitySyncStatus.SYNCED,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    val lastSyncError: String? = null,
    val lastFullContentSyncTimestamp: Long? = null, // For MessageRemoteMediator: last time all pages were fetched
    val nextPageToken: String? = null, // For MessageRemoteMediator: token for the next page of messages
    val messageListSyncToken: String? = null // Token for delta syncing the message list within this folder
) 