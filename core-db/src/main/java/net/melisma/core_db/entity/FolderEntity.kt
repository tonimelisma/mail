package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.SyncStatus

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
    indices = [Index(value = ["accountId"])]
)
data class FolderEntity(
    @PrimaryKey val id: String, // Unique local ID for the folder
    val accountId: String,
    val remoteId: String?, // ID used by the remote server (e.g., Gmail Label ID, Outlook Folder ID)
    val name: String?,
    val type: String?, // e.g., "inbox", "sent", "drafts", "user"
    val unreadCount: Int? = 0,
    val totalCount: Int? = 0,
    val canHaveChildren: Boolean? = false,
    val parentFolderId: String? = null, // Local DB ID of parent folder
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    val lastSyncError: String? = null,
    val lastFullContentSyncTimestamp: Long? = null, // For MessageRemoteMediator: last time all pages were fetched
    val nextPageToken: String? = null // For MessageRemoteMediator: token for the next page of messages
) 