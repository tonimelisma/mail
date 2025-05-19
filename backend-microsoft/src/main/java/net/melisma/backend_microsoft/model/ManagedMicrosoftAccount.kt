package net.melisma.backend_microsoft.model

import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult

/**
 * A data class that holds the raw MSAL IAccount object along with
 * supplementary details that might have been persisted by MicrosoftTokenPersistenceService,
 * such as a display name or tenant ID, offering a richer account object for internal use
 * within the backend-microsoft module, primarily by MicrosoftAuthManager.
 */
data class ManagedMicrosoftAccount(
    val iAccount: IAccount,
    val displayName: String?,
    val tenantId: String? // From PersistedMicrosoftAccount
) {
    companion object {
        fun fromIAccount(
            msalAccount: IAccount,
            authResult: IAuthenticationResult? // Optional: authResult can provide more claims
        ): ManagedMicrosoftAccount {
            // Attempt to get display name from claims if authResult is available,
            // otherwise, it might be null or fall back to username if IAccount has it.
            val nameFromClaims = authResult?.account?.claims?.get("name") as? String
            return ManagedMicrosoftAccount(
                iAccount = msalAccount,
                displayName = nameFromClaims
                    ?: msalAccount.username, // Fallback or use what IAccount provides
                tenantId = msalAccount.tenantId
            )
        }

        fun fromPersisted(
            persistedAccount: PersistedMicrosoftAccount,
            msalAccount: IAccount // The corresponding live IAccount object
        ): ManagedMicrosoftAccount {
            return ManagedMicrosoftAccount(
                iAccount = msalAccount, // Use the live IAccount
                displayName = persistedAccount.displayName
                    ?: msalAccount.username, // Prefer persisted, fallback to live username
                tenantId = persistedAccount.tenantId
                    ?: msalAccount.tenantId // Prefer persisted, fallback to live tenantId
            )
        }
    }
} 