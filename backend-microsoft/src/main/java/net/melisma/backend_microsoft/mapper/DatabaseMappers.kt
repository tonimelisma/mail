package net.melisma.backend_microsoft.mapper

import net.melisma.core_data.model.Account
import net.melisma.core_db.entity.AccountEntity

/**
 * Maps an [AccountEntity] from the database to the domain [Account] model.
 */
fun AccountEntity.toDomainAccount(): Account {
    return Account(
        id = this.id,
        displayName = this.displayName,
        emailAddress = this.emailAddress,
        providerType = this.providerType,
        needsReauthentication = this.needsReauthentication,
        isLocalOnly = this.isLocalOnly
    )
} 