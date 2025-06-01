package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.melisma.core_data.model.SyncStatus

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String, // Corresponds to Account.id
    val username: String, // User's name or primary identifier from Account.username
    val emailAddress: String, // Email address, often same as username
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