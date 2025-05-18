package net.melisma.backend_microsoft.model

/**
 * Data class representing the information about a Microsoft account that is persisted
 * by the application in Android's AccountManager.
 *
 * This data is typically used to re-identify accounts known to MSAL and to store
 * supplementary information the app might need.
 */
data class PersistedMicrosoftAccount(
    /** The unique name used for this account in Android's AccountManager. Typically IAccount.getId(). */
    val accountManagerName: String,

    /** The MSAL specific account ID (e.g., IAccount.getId()). Often the same as accountManagerName. */
    val msalAccountId: String,

    /** The username associated with the account (e.g., email, UPN). From IAccount.getUsername(). */
    val username: String?,

    /** The display name for the account, potentially derived from claims or username. */
    val displayName: String?,

    /** The tenant ID associated with the account. From IAccount.getTenantId(). */
    val tenantId: String?
    // Consider adding homeAccountId if it's distinct from msalAccountId and useful for MSAL operations.
) 