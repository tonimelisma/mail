package net.melisma.data.mapper

import net.melisma.core_data.model.Account // This is the API/Domain model
import net.melisma.core_db.entity.AccountEntity

fun Account.toEntity(): AccountEntity {
    // Note: Sync metadata fields (syncStatus, lastSyncAttemptTimestamp, etc.)
    // are not part of the Account domain model and will retain their existing values
    // in the AccountEntity if this is an update, or use defaults if this is a new insert.
    return AccountEntity(
        id = this.id,
        displayName = this.displayName,
        emailAddress = this.emailAddress,
        providerType = this.providerType,
        needsReauthentication = this.needsReauthentication,
        isLocalOnly = this.isLocalOnly // Map from domain to entity
        // syncStatus, lastSyncAttemptTimestamp, etc., are intentionally not set here
        // as they are managed by sync processes or have defaults in AccountEntity.
    )
}

fun AccountEntity.toDomainAccount(): Account {
    return Account(
        id = this.id,
        displayName = this.displayName,
        emailAddress = this.emailAddress,
        providerType = this.providerType,
        needsReauthentication = this.needsReauthentication,
        isLocalOnly = this.isLocalOnly // Map from entity to domain
    )
}