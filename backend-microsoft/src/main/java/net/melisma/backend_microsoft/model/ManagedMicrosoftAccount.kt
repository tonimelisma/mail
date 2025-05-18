package net.melisma.backend_microsoft.model

import com.microsoft.identity.client.IAccount

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
) 