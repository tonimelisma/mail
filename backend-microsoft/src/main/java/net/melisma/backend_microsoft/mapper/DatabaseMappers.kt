package net.melisma.backend_microsoft.mapper

import net.melisma.core_data.model.Account
import net.melisma.core_db.entity.AccountEntity

/**
 * Maps an [AccountEntity] from the database to the domain [Account] model.
 */
fun AccountEntity.toDomainAccount(): Account {
    return Account(
        id = this.id,
        username = this.username, // AccountEntity.username should map to Account.username
        // emailAddress from AccountEntity is not directly in Account model but username often serves as email
        providerType = this.providerType,
        needsReauthentication = this.needsReauthentication
    )
} 