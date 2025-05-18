package net.melisma.backend_google.auth

class GoogleNeedsReauthenticationException(
    val accountId: String,
    message: String = "Account $accountId needs re-authentication with Google.",
    cause: Throwable? = null
) : Exception(message, cause) 