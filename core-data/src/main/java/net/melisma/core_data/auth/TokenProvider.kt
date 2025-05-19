package net.melisma.core_data.auth

import io.ktor.client.plugins.auth.providers.BearerTokens

/**
 * Interface for providing bearer tokens to Ktor's authentication plugin.
 * Implementations will be provider-specific (Microsoft, Google).
 */
interface TokenProvider {
    /**
     * Loads the current valid BearerTokens for the active account of this provider.
     * This might involve silent token acquisition or retrieval from cache.
     *
     * @return BearerTokens if successful and an active, authenticated account exists, null otherwise.
     * @throws NeedsReauthenticationException if user interaction is required to re-authenticate.
     * @throws TokenProviderException for other token acquisition errors.
     */
    suspend fun getBearerTokens(): BearerTokens?

    /**
     * Attempts to refresh the BearerTokens.
     * Ktor typically calls this after a 401 response.
     *
     * @param oldTokens The tokens that were rejected (optional, might not always be provided by Ktor).
     * @return New BearerTokens if refresh was successful, null otherwise.
     * @throws NeedsReauthenticationException if user interaction is required to re-authenticate.
     * @throws TokenProviderException for other token refresh errors.
     */
    suspend fun refreshBearerTokens(oldTokens: BearerTokens?): BearerTokens?
} 