package net.melisma.backend_microsoft.auth

interface MicrosoftAuthUserCredentials {
    /**
     * Retrieves the current valid access token for the active Microsoft account.
     * This might involve refreshing the token if it's expired.
     *
     * @return The access token string, or null if no token is available or an error occurs.
     */
    suspend fun getAccessToken(): String?

    /**
     * Retrieves the currently active Microsoft Account ID.
     *
     * @return The account ID string, or null if no account is active.
     */
    suspend fun getActiveAccountId(): String?
} 