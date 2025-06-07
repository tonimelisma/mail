package net.melisma.backend_microsoft.mapper

import com.microsoft.identity.client.IAccount
import net.melisma.core_data.model.Account // Domain/Core Account model

/**
 * Maps an MSAL [IAccount] object to the application's domain [Account] model.
 */
fun IAccount.toDomainAccount(): Account {
    // The 'name' claim might provide a fuller display name.
    // Username from IAccount can sometimes be an opaque ID depending on Azure AD config.
    val displayNameFromClaims = this.claims?.get("name") as? String
    val preferredUsername = this.claims?.get("preferred_username") as? String // Often the email

    // According to MSAL 6.0.0 Javadoc for IAccount.getId():
    // "Gets the id of the account. For the Microsoft Identity Platform: the OID of the account in its home tenant."
    // This makes IAccount.id (which calls getId()) a strong candidate for a stable identifier.
    // Fallback to oid claim if IAccount.id is null, though unlikely.
    val oidFromClaims = this.claims?.get("oid") as? String
    val accountId = this.id ?: oidFromClaims ?: ""

    // TODO: If app supports multiple tenants for the same user, consider combining oid + tid for true uniqueness if needed.
    // val tidClaim = this.claims?.get("tid") as? String
    // val trulyUniqueIdentifier = if (oidClaim != null && tidClaim != null) "$oidClaim.$tidClaim" else this.id ?: ""

    // Ensure emailAddress is non-null.
    // IAccount.username is often the UserPrincipalName (UPN), which is typically an email.
    // preferredUsername claim is also a good candidate for email.
    val email = preferredUsername ?: this.username

    return Account(
        id = accountId,
        displayName = displayNameFromClaims, // This can be null
        emailAddress = email,
        providerType = Account.PROVIDER_TYPE_MS,
        // needsReauthentication: This is complex. IAccount itself doesn't directly state this.
        // It's usually determined by a failed token attempt (MsalUiRequiredException).
        // For now, default to false. The repository or manager should handle logic
        // to update this based on auth outcomes.
        needsReauthentication = false // TODO: Determine this state more accurately
    )
}

/**
 * Maps a list of MSAL [IAccount] objects to a list of domain [Account] models.
 */
fun List<IAccount>.toDomainAccounts(): List<Account> {
    return this.map { it.toDomainAccount() }
}

// If you also have ManagedMicrosoftAccount and need to map it:
// import net.melisma.backend_microsoft.model.ManagedMicrosoftAccount
// fun ManagedMicrosoftAccount.toDomain(): Account {
//     return Account(
//         id = this.iAccount.id ?: this.iAccount.homeAccountId.identifier ?: "",
//         username = this.displayName ?: this.iAccount.username ?: "Unknown User",
//         providerType = Account.PROVIDER_TYPE_MS,
//         needsReauthentication = false // TODO: Determine this
//     )
// } 