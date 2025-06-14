package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.EntitySyncStatus

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["emailAddress"], unique = true)]
)
data class AccountEntity(
    @PrimaryKey val id: String, // Corresponds to Account.id
    val displayName: String?, // User's display name
    val emailAddress: String, // Email address
    val providerType: String, // e.g., "GOOGLE", "MICROSOFT", from Account.providerType
    val needsReauthentication: Boolean = false, // Added to sync with domain model

    // Sync Metadata
    val syncStatus: EntitySyncStatus = EntitySyncStatus.SYNCED,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    val lastSyncError: String? = null,
    val isLocalOnly: Boolean = false,
    val needsFullSync: Boolean = false,

    // Sync metadata specific to folder list synchronization
    val lastFolderListSyncTimestamp: Long? = null,
    val lastFolderListSyncError: String? = null,
    val folderListSyncToken: String? = null, // Token for delta syncing the folder list

    // Sync metadata for other potential top-level sync errors not covered by folder list or general status
    val lastGenericSyncError: String? = null
)