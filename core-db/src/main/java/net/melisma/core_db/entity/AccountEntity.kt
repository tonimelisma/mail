package net.melisma.core_db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String, // Corresponds to Account.id
    val username: String, // User's name or primary identifier from Account.username
    val emailAddress: String, // Email address, often same as username
    val providerType: String, // e.g., "GOOGLE", "MICROSOFT", from Account.providerType
    val needsReauthentication: Boolean = false // Added to sync with domain model
) 