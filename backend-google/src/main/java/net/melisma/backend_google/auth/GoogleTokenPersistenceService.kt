package net.melisma.backend_google.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.security.SecureEncryptionService
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for securely storing and retrieving Google OAuth tokens using Android's AccountManager.
 * Tokens are encrypted before storage using the SecureEncryptionService.
 */
@Singleton
class GoogleTokenPersistenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionService: SecureEncryptionService,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "GoogleTokenService"

        // Account type for Google accounts in our app
        const val ACCOUNT_TYPE = "net.melisma.mail.GOOGLE_ACCOUNT"

        // User data keys for storing tokens and metadata in AccountManager
        private const val KEY_ENCRYPTED_ACCESS_TOKEN = "encrypted_access_token"
        private const val KEY_ENCRYPTED_REFRESH_TOKEN = "encrypted_refresh_token"
        private const val KEY_ENCRYPTED_ID_TOKEN = "encrypted_id_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_TOKEN_SCOPES = "token_scopes"
        private const val KEY_TOKEN_EXPIRY_TIME = "token_expiry_time"
        private const val KEY_EMAIL = "email"
        private const val KEY_DISPLAY_NAME = "display_name"

        // Token states
        const val TOKEN_STATE_VALID = 0
        const val TOKEN_STATE_EXPIRED = 1
        const val TOKEN_STATE_MISSING = 2
        const val TOKEN_STATE_ERROR = 3
    }

    private val accountManager: AccountManager by lazy {
        AccountManager.get(context)
    }

    // State flow to track token state
    private val _tokenState = MutableStateFlow(TOKEN_STATE_MISSING)
    val tokenState: StateFlow<Int> = _tokenState.asStateFlow()

    /**
     * Saves Google OAuth tokens and metadata to AccountManager.
     * Tokens are encrypted before storage.
     *
     * @param accountId The account identifier (typically user's Google ID or email)
     * @param tokenResponse The AppAuth token response containing tokens to save
     * @param email Optional email address associated with the account
     * @param displayName Optional display name for the account
     * @return True if tokens were saved successfully, false otherwise
     */
    suspend fun saveTokens(
        accountId: String,
        tokenResponse: TokenResponse,
        email: String? = null,
        displayName: String? = null
    ): Boolean = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "Saving tokens for account: $accountId")

            // Convert TokenResponse to GoogleTokenData for easier handling
            val tokenData = if (tokenResponse is AppAuthHelperService.GoogleTokenData) {
                tokenResponse
            } else {
                AppAuthHelperService.GoogleTokenData(
                    accessToken = tokenResponse.accessToken.orEmpty(),
                    refreshToken = tokenResponse.refreshToken,
                    idToken = tokenResponse.idToken,
                    tokenType = tokenResponse.tokenType.orEmpty(),
                    scopes = tokenResponse.scope?.split(" ") ?: emptyList(),
                    expiresIn = tokenResponse.accessTokenExpirationTime ?: 0
                )
            }

            // Verify we have an access token
            if (tokenData.accessToken.isBlank()) {
                Log.e(TAG, "Cannot save tokens: Access token is blank")
                _tokenState.value = TOKEN_STATE_ERROR
                return@withContext false
            }

            // Attempt to extract email from ID Token if available
            var extractedEmailFromIdToken: String? = null
            if (!tokenData.idToken.isNullOrBlank()) {
                try {
                    val parts = tokenData.idToken.split(".")
                    if (parts.size >= 2) { // We only need the payload part
                        val payloadJson = String(
                            android.util.Base64.decode(
                                parts[1],
                                android.util.Base64.URL_SAFE
                            ), Charset.forName("UTF-8")
                        )
                        val jsonObject = JSONObject(payloadJson)
                        extractedEmailFromIdToken = jsonObject.optString("email", null)
                        if (extractedEmailFromIdToken != null) {
                            Log.d(
                                TAG,
                                "Extracted email from AppAuth ID Token: $extractedEmailFromIdToken"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse email from AppAuth ID Token JWT", e)
                }
            }

            // Use extracted email if available, otherwise use the one passed in (which might be null from CredentialManager)
            val finalEmail = extractedEmailFromIdToken ?: email
            val finalDisplayName =
                displayName // Keep display name as passed in (likely from CredentialManager)

            // Create or get the account
            val account = getOrCreateAccount(accountId, finalEmail, finalDisplayName)

            // Encrypt and save the tokens
            val encryptedAccessToken = encryptionService.encrypt(tokenData.accessToken)
            val encryptedRefreshToken =
                tokenData.refreshToken?.let { encryptionService.encrypt(it) }
            val encryptedIdToken = tokenData.idToken?.let { encryptionService.encrypt(it) }

            if (encryptedAccessToken == null) {
                Log.e(TAG, "Failed to encrypt access token")
                _tokenState.value = TOKEN_STATE_ERROR
                return@withContext false
            }

            // Store the encrypted tokens and metadata
            accountManager.setUserData(account, KEY_ENCRYPTED_ACCESS_TOKEN, encryptedAccessToken)
            encryptedRefreshToken?.let {
                accountManager.setUserData(
                    account,
                    KEY_ENCRYPTED_REFRESH_TOKEN,
                    it
                )
            }
            encryptedIdToken?.let {
                accountManager.setUserData(
                    account,
                    KEY_ENCRYPTED_ID_TOKEN,
                    it
                )
            }
            accountManager.setUserData(account, KEY_TOKEN_TYPE, tokenData.tokenType)
            accountManager.setUserData(
                account,
                KEY_TOKEN_SCOPES,
                tokenData.scopes.joinToString(" ")
            )
            accountManager.setUserData(
                account,
                KEY_TOKEN_EXPIRY_TIME,
                tokenData.expiresIn.toString()
            )

            // Update additional account information if provided
            finalEmail?.let { accountManager.setUserData(account, KEY_EMAIL, it) }
            finalDisplayName?.let { accountManager.setUserData(account, KEY_DISPLAY_NAME, it) }

            _tokenState.value = TOKEN_STATE_VALID
            Log.d(TAG, "Tokens saved successfully for account: $accountId")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving tokens", e)
            _tokenState.value = TOKEN_STATE_ERROR
            return@withContext false
        }
    }

    /**
     * Retrieves the saved tokens for an account.
     *
     * @param accountId The account identifier
     * @return GoogleTokenData object containing the decrypted tokens, or null if tokens couldn't be retrieved
     */
    suspend fun getTokens(accountId: String): AppAuthHelperService.GoogleTokenData? =
        withContext(ioDispatcher) {
            try {
                Log.d(TAG, "Retrieving tokens for account: $accountId")

                // Get the account
                val account = findAccount(accountId) ?: run {
                    Log.e(TAG, "Account not found: $accountId")
                    _tokenState.value = TOKEN_STATE_MISSING
                    return@withContext null
                }

                // Get the encrypted tokens
                val encryptedAccessToken =
                    accountManager.getUserData(account, KEY_ENCRYPTED_ACCESS_TOKEN)
                val encryptedRefreshToken =
                    accountManager.getUserData(account, KEY_ENCRYPTED_REFRESH_TOKEN)
                val encryptedIdToken = accountManager.getUserData(account, KEY_ENCRYPTED_ID_TOKEN)

                if (encryptedAccessToken == null) {
                    Log.e(TAG, "Access token not found for account: $accountId")
                    _tokenState.value = TOKEN_STATE_MISSING
                    return@withContext null
                }

                // Decrypt the tokens
                val accessToken = encryptionService.decrypt(encryptedAccessToken)
                val refreshToken = encryptedRefreshToken?.let { encryptionService.decrypt(it) }
                val idToken = encryptedIdToken?.let { encryptionService.decrypt(it) }

                if (accessToken == null) {
                    Log.e(TAG, "Failed to decrypt access token")
                    _tokenState.value = TOKEN_STATE_ERROR
                    return@withContext null
                }

                // Get token metadata
                val tokenType = accountManager.getUserData(account, KEY_TOKEN_TYPE) ?: "Bearer"
                val scopesStr = accountManager.getUserData(account, KEY_TOKEN_SCOPES) ?: ""
                val expiryTimeStr =
                    accountManager.getUserData(account, KEY_TOKEN_EXPIRY_TIME) ?: "0"

                val scopes = if (scopesStr.isNotBlank()) scopesStr.split(" ") else emptyList()
                val expiryTime = expiryTimeStr.toLongOrNull() ?: 0L

                // Check if token is expired
                val currentTime = System.currentTimeMillis()
                if (expiryTime > 0 && currentTime >= expiryTime) {
                    Log.d(TAG, "Access token is expired")
                    _tokenState.value = TOKEN_STATE_EXPIRED
                } else {
                    _tokenState.value = TOKEN_STATE_VALID
                }

                return@withContext AppAuthHelperService.GoogleTokenData(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = idToken,
                    tokenType = tokenType,
                    scopes = scopes,
                    expiresIn = expiryTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving tokens", e)
                _tokenState.value = TOKEN_STATE_ERROR
                return@withContext null
            }
        }

    /**
     * Updates just the access token for an account.
     * Useful after refreshing an access token.
     *
     * @param accountId The account identifier
     * @param newAccessToken The new access token
     * @param newExpiryTime The new expiry time in milliseconds since epoch
     * @return True if the access token was updated successfully, false otherwise
     */
    suspend fun updateAccessToken(
        accountId: String,
        newAccessToken: String,
        newExpiryTime: Long?
    ): Boolean = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "Updating access token for account: $accountId")

            // Get the account
            val account = findAccount(accountId) ?: run {
                Log.e(TAG, "Account not found: $accountId")
                return@withContext false
            }

            // Encrypt the new access token
            val encryptedAccessToken = encryptionService.encrypt(newAccessToken)
            if (encryptedAccessToken == null) {
                Log.e(TAG, "Failed to encrypt new access token")
                return@withContext false
            }

            // Update the access token and expiry time
            accountManager.setUserData(account, KEY_ENCRYPTED_ACCESS_TOKEN, encryptedAccessToken)
            newExpiryTime?.let {
                accountManager.setUserData(account, KEY_TOKEN_EXPIRY_TIME, it.toString())
            }

            _tokenState.value = TOKEN_STATE_VALID
            Log.d(TAG, "Access token updated successfully for account: $accountId")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating access token", e)
            return@withContext false
        }
    }

    /**
     * Clears all tokens for an account.
     *
     * @param accountId The account identifier
     * @param removeAccount Whether to completely remove the account (true) or just clear the tokens (false)
     * @return True if tokens were cleared successfully, false otherwise
     */
    suspend fun clearTokens(accountId: String, removeAccount: Boolean = false): Boolean =
        withContext(ioDispatcher) {
            try {
                Log.d(TAG, "Clearing tokens for account: $accountId")

                // Get the account
                val account = findAccount(accountId) ?: run {
                    Log.e(TAG, "Account not found: $accountId")
                    return@withContext true // Account doesn't exist, so tokens are effectively cleared
                }

                if (removeAccount) {
                    // Remove the account completely
                    accountManager.removeAccountExplicitly(account)
                    Log.d(TAG, "Account removed: $accountId")
                } else {
                    // Just clear the tokens
                    accountManager.setUserData(account, KEY_ENCRYPTED_ACCESS_TOKEN, null)
                    accountManager.setUserData(account, KEY_ENCRYPTED_REFRESH_TOKEN, null)
                    accountManager.setUserData(account, KEY_ENCRYPTED_ID_TOKEN, null)
                    accountManager.setUserData(account, KEY_TOKEN_EXPIRY_TIME, "0")
                    Log.d(TAG, "Tokens cleared for account: $accountId")
                }

                _tokenState.value = TOKEN_STATE_MISSING
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing tokens", e)
                return@withContext false
            }
        }

    /**
     * Gets associated account information.
     *
     * @param accountId The account identifier
     * @return A map of account information (email, display name)
     */
    suspend fun getAccountInfo(accountId: String): Map<String, String?> =
        withContext(ioDispatcher) {
            try {
                val account =
                    findAccount(accountId) ?: return@withContext emptyMap<String, String?>()

                val email = accountManager.getUserData(account, KEY_EMAIL)
                val displayName = accountManager.getUserData(account, KEY_DISPLAY_NAME)

                return@withContext mapOf(
                    "email" to email,
                    "displayName" to displayName
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting account info", e)
                return@withContext emptyMap<String, String?>()
            }
        }

    /**
     * Lists all Google accounts registered in the app.
     *
     * @return List of account IDs
     */
    suspend fun listAccounts(): List<String> = withContext(ioDispatcher) {
        try {
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
            return@withContext accounts.map { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing accounts", e)
            return@withContext emptyList<String>()
        }
    }

    /**
     * Gets or creates an account in the AccountManager.
     *
     * @param accountId The account identifier (will be used as account name)
     * @param email Optional email to store with the account
     * @param displayName Optional display name to store with the account
     * @return The Account object
     */
    private fun getOrCreateAccount(
        accountId: String,
        email: String?,
        displayName: String?
    ): Account {
        // Look for existing account
        val account = findAccount(accountId)

        if (account != null) {
            return account
        }

        // Create a new account
        val newAccount = Account(accountId, ACCOUNT_TYPE)
        val result = accountManager.addAccountExplicitly(newAccount, null, null)

        if (!result) {
            Log.w(TAG, "Failed to add account explicitly, trying to find it again")
            return findAccount(accountId)
                ?: throw IllegalStateException("Failed to create account: $accountId")
        }

        Log.d(TAG, "Created new account: $accountId")
        return newAccount
    }

    /**
     * Finds an account by ID.
     *
     * @param accountId The account identifier
     * @return The Account object, or null if not found
     */
    private fun findAccount(accountId: String): Account? {
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
        return accounts.find { it.name == accountId }
    }
}