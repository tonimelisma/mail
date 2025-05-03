package net.melisma.mail

/**
 * A generic representation of a user account within the application.
 * This data class abstracts the provider-specific account details (like MSAL's IAccount).
 *
 * @property id A unique identifier for the account, typically provided by the auth system.
 * @property username A user-friendly name or email address representing the account.
 * @property providerType An identifier for the backend provider (e.g., "MS", "GOOGLE"). Added for future use.
 */
data class Account(
    val id: String,
    val username: String,
    val providerType: String // Example: "MS" for Microsoft, "GOOGLE" for Google
)