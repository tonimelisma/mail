package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folder_sync_state")
data class FolderSyncStateEntity(
    @PrimaryKey val folderId: String,
    val nextPageToken: String?,
    val lastSyncedTimestamp: Long?
) 