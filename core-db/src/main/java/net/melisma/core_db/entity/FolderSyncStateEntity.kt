package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folder_sync_state")
data class FolderSyncStateEntity(
    @PrimaryKey val folderId: String,
    val nextPageToken: String?,
    val lastSyncedTimestamp: Long?,
    val deltaToken: String? = null,
    val historyId: String? = null,
    /** Timestamp of the oldest message for which we have a provably continuous history from `now`. Null if continuity is unknown. */
    val continuousHistoryToTimestamp: Long? = null
) 