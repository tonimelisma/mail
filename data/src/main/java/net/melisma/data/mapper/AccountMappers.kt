package net.melisma.data.mapper

import net.melisma.core_data.model.Account // This is the API/Domain model
import net.melisma.core_db.entity.AccountEntity

fun Account.toEntity(): AccountEntity {
    return AccountEntity(
        id = this.id,
        username = this.username, // Store username from Account model
        emailAddress = this.username, // Assuming username is suitable as emailAddress for entity
        providerType = this.providerType // providerType is already a String
        // Note: needsReauthentication from Account model is not stored in AccountEntity by default.
        // If offline re-auth status is needed, add it to AccountEntity.
    )
}

fun AccountEntity.toDomainAccount(): Account {
    return Account(
        id = this.id,
        username = this.username, // Use stored username
        providerType = this.providerType,
        // Default needsReauthentication to false when loading from DB.
        // Live re-authentication state is typically managed by an auth service.
        needsReauthentication = false
    )
} 