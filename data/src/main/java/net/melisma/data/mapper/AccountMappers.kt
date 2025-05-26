package net.melisma.data.mapper

import net.melisma.core_data.model.Account // This is the API/Domain model
import net.melisma.core_db.entity.AccountEntity

fun Account.toEntity(): AccountEntity {
    return AccountEntity(
        id = this.id,
        username = this.username,
        emailAddress = this.username, // Assuming username is the email address for now
        providerType = this.providerType,
        needsReauthentication = this.needsReauthentication
    )
}

fun AccountEntity.toDomainAccount(): Account {
    return Account(
        id = this.id,
        username = this.username,
        // emailAddress is not directly in the domain Account model, username usually serves this.
        providerType = this.providerType,
        needsReauthentication = this.needsReauthentication
    )
} 