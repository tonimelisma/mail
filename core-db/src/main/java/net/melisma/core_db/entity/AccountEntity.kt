package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.melisma.core_data.model.SyncStatus

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
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val lastSyncAttemptTimestamp: Long? = null,
    val lastSuccessfulSyncTimestamp: Long? = null,
    val lastSyncError: String? = null,
    val isLocalOnly: Boolean = false,
    val needsFullSync: Boolean = false
)