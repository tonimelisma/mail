package net.melisma.core_data.model

/**
 * A generic representation of a user account within the application.
 * This data class abstracts the provider-specific account details (like MSAL's IAccount).
 * It serves as the primary account model used across UI and ViewModel layers.
 *
 * @property id A unique identifier for the account, typically provided by the auth system (e.g., MSAL account ID).
 * @property username A user-friendly name or email address representing the account.
 * @property providerType An identifier string for the backend provider (e.g., "MS" for Microsoft, "GOOGLE" for Google). Used for routing logic.
 */
data class Account(
    val id: String,
    val displayName: String?,
    val emailAddress: String,
    val providerType: String,
    val needsReauthentication: Boolean = false,
    val isLocalOnly: Boolean = false
) {
    companion object {
        const val PROVIDER_TYPE_MS = "MS"
        const val PROVIDER_TYPE_GOOGLE = "GOOGLE"
        // Add other provider type constants here if needed
    }
}
