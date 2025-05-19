package net.melisma.core_data.auth

class NeedsReauthenticationException(
    val accountIdToReauthenticate: String?,
    override val message: String,
    override val cause: Throwable? = null
) : RuntimeException(message, cause) 