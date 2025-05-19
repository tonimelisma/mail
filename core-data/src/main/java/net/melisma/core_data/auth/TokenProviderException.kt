package net.melisma.core_data.auth

class TokenProviderException(
    override val message: String,
    override val cause: Throwable? = null
) : RuntimeException(message, cause) 