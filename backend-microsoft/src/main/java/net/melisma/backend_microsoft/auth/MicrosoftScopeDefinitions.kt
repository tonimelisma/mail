package net.melisma.backend_microsoft.auth

object MicrosoftScopeDefinitions {
    // Standard OpenID Connect Scopes
    const val OPENID = "openid"
    const val PROFILE = "profile"
    const val OFFLINE_ACCESS = "offline_access"

    // Microsoft Graph API Scopes
    const val USER_READ = "User.Read" // Required for basic user profile
    const val MAIL_READ_WRITE = "Mail.ReadWrite" // Read and write mail
    const val MAIL_SEND = "Mail.Send"         // Send mail
    const val CALENDARS_READ_WRITE = "Calendars.ReadWrite"
    const val CONTACTS_READ_WRITE = "Contacts.ReadWrite"

    /**
     * The complete and sole list of scopes required for application functionality.
     * This list is used for interactive user consent and for subsequent silent token acquisitions.
     * If the user does not grant all of these scopes, the authentication should be treated as failed.
     */
    val RequiredScopes = listOf(
        USER_READ,
        MAIL_READ_WRITE,
        MAIL_SEND,
        CALENDARS_READ_WRITE,
        CONTACTS_READ_WRITE,
        OPENID,
        PROFILE,
        OFFLINE_ACCESS
    )

    // MinimumRequiredScopes has been removed to simplify scope management.
    // The application will now require all scopes defined in RequiredScopes.
} 