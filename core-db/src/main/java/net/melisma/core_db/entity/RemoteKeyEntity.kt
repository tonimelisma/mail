package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "remote_keys")
data class RemoteKeyEntity(
    @PrimaryKey val folderId: String,
    val nextPageToken: String?,
    val prevPageToken: String? // Optional
) 