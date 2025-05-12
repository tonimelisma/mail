// File: backend-google/src/main/java/net/melisma/backend_google/auth/GoogleTokenPersistenceService.kt
package net.melisma.backend_google.auth

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.security.SecureEncryptionService
import net.openid.appauth.TokenResponse
import org.json.JSONObject // For simple JWT parsing
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

// Define your account type string, ensure it's unique and matches authenticator.xml if you have one
private const val ACCOUNT_TYPE_GOOGLE = "net.melisma.mail.GOOGLE" // Example account type
private const val KEY_ACCESS_TOKEN = "accessToken"
private const val KEY_REFRESH_TOKEN = "refreshToken"
private const val KEY_ID_TOKEN = "idToken"
private const val KEY_TOKEN_TYPE = "tokenType"
private const val KEY_SCOPES = "scopes"
private const val KEY_EXPIRES_IN_TIMESTAMP = "expiresInTimestamp" // Store actual timestamp
private const val KEY_EMAIL = "email"
private const val KEY_DISPLAY_NAME = "displayName"


@Singleton
class GoogleTokenPersistenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureEncryptionService: SecureEncryptionService, // Correctly injected
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "GoogleTokenService"
    private val accountManager: AccountManager = AccountManager.get(context)

    init {
        Log.d(TAG, "GoogleTokenPersistenceService instance created/injected.")
    }

    data class PersistedGoogleAccount(
        val accountId: String,
        val email: String?,
        val displayName: String?
    )

    suspend fun saveTokens(
        accountId: String,
        tokenResponse: TokenResponse,
        email: String?,
        displayName: String?
    ): Boolean = withContext(ioDispatcher) {
        Log.d(
            TAG,
            "saveTokens called for accountId (Google User ID): ${accountId.take(5)}..., Email: $email, DisplayName: $displayName"
        )
        if (accountId.isBlank()) {
            Log.e(TAG, "Cannot save tokens: Account ID (Google User ID) is blank.")
            return@withContext false
        }

        var extractedEmail = email
        var extractedDisplayName = displayName

        tokenResponse.idToken?.let { idToken ->
            Log.d(
                TAG,
                "ID Token present, attempting to parse for email/displayName. ID Token (first 30): ${
                    idToken.take(30)
                }..."
            )
            try {
                val parts = idToken.split(".")
                if (parts.size == 3) {
                    val payloadBytes =
                        android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE)
                    val payloadJson = String(payloadBytes, StandardCharsets.UTF_8)
                    val jsonObject = JSONObject(payloadJson)

                    if (extractedEmail.isNullOrBlank() && jsonObject.has("email")) {
                        extractedEmail = jsonObject.getString("email")
                        Log.d(TAG, "Extracted email from ID token: $extractedEmail")
                    }
                    if (extractedDisplayName.isNullOrBlank() && jsonObject.has("name")) {
                        extractedDisplayName = jsonObject.getString("name")
                        Log.d(
                            TAG,
                            "Extracted displayName (name claim) from ID token: $extractedDisplayName"
                        )
                    } else if (extractedDisplayName.isNullOrBlank() && jsonObject.has("given_name") && jsonObject.has(
                            "family_name"
                        )
                    ) {
                        extractedDisplayName =
                            "${jsonObject.getString("given_name")} ${jsonObject.getString("family_name")}"
                        Log.d(
                            TAG,
                            "Extracted displayName (given_name + family_name) from ID token: $extractedDisplayName"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Failed to parse ID token for email/displayName, will use provided values or null.",
                    e
                )
            }
        }

        val account = android.accounts.Account(accountId, ACCOUNT_TYPE_GOOGLE)
        Log.d(
            TAG,
            "AccountManager account object created: Name=${account.name}, Type=${account.type}"
        )

        val accountAdded = accountManager.addAccountExplicitly(account, null, null)
        if (accountAdded) {
            Log.i(TAG, "New Google account added to AccountManager: $accountId")
        } else {
            Log.d(
                TAG,
                "Google account $accountId already exists in AccountManager, will update tokens."
            )
        }

        try {
            tokenResponse.accessToken?.let {
                // Corrected: Call 'encrypt' instead of 'encryptData'
                val encryptedToken: String? = secureEncryptionService.encrypt(it)
                if (encryptedToken != null) {
                    accountManager.setUserData(account, KEY_ACCESS_TOKEN, encryptedToken)
                    Log.d(TAG, "Access token stored for $accountId.")
                } else {
                    Log.e(TAG, "Failed to encrypt access token for $accountId.")
                    // Decide if this is a critical failure
                }
            }
            tokenResponse.refreshToken?.let {
                // Corrected: Call 'encrypt' instead of 'encryptData'
                val encryptedToken: String? = secureEncryptionService.encrypt(it)
                if (encryptedToken != null) {
                    accountManager.setUserData(account, KEY_REFRESH_TOKEN, encryptedToken)
                    Log.d(TAG, "Refresh token stored for $accountId.")
                } else {
                    Log.e(TAG, "Failed to encrypt refresh token for $accountId.")
                }
            }
            tokenResponse.idToken?.let {
                // Corrected: Call 'encrypt' instead of 'encryptData'
                val encryptedToken: String? = secureEncryptionService.encrypt(it)
                if (encryptedToken != null) {
                    accountManager.setUserData(account, KEY_ID_TOKEN, encryptedToken)
                    Log.d(TAG, "ID token stored for $accountId.")
                } else {
                    Log.e(TAG, "Failed to encrypt ID token for $accountId.")
                }
            }
            accountManager.setUserData(account, KEY_TOKEN_TYPE, tokenResponse.tokenType)
            tokenResponse.scope?.let { accountManager.setUserData(account, KEY_SCOPES, it) }

            tokenResponse.accessTokenExpirationTime?.let {
                accountManager.setUserData(account, KEY_EXPIRES_IN_TIMESTAMP, it.toString())
                Log.d(TAG, "Access token expiration timestamp ($it) stored for $accountId.")
            }

            extractedEmail?.let { accountManager.setUserData(account, KEY_EMAIL, it) }
            extractedDisplayName?.let { accountManager.setUserData(account, KEY_DISPLAY_NAME, it) }

            Log.i(TAG, "Tokens and user data saved successfully for Google account: $accountId")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving tokens to AccountManager for $accountId", e)
            if (accountAdded) {
                Log.w(TAG, "Removing partially added account $accountId due to token save failure.")
                accountManager.removeAccount(account, null, null, null)
            }
            return@withContext false
        }
    }

    suspend fun getTokens(accountId: String): AppAuthHelperService.GoogleTokenData? =
        withContext(ioDispatcher) {
            Log.d(TAG, "getTokens called for accountId: ${accountId.take(5)}...")
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
            val account = accounts.find { it.name == accountId }

            if (account == null) {
                Log.w(TAG, "No Google account found in AccountManager for ID: $accountId")
                return@withContext null
            }
            Log.d(TAG, "Found account in AccountManager: ${account.name}")

            try {
                val encryptedAccessToken = accountManager.getUserData(account, KEY_ACCESS_TOKEN)
                val encryptedRefreshToken = accountManager.getUserData(account, KEY_REFRESH_TOKEN)
                val encryptedIdToken = accountManager.getUserData(account, KEY_ID_TOKEN)
                val tokenType = accountManager.getUserData(account, KEY_TOKEN_TYPE)
                val scopesString = accountManager.getUserData(account, KEY_SCOPES)
                val expiresInTimestampString =
                    accountManager.getUserData(account, KEY_EXPIRES_IN_TIMESTAMP)

                if (encryptedAccessToken == null || tokenType == null) {
                    Log.w(TAG, "Access token or token type is missing for account $accountId.")
                    return@withContext null
                }

                // Corrected: Call 'decrypt' instead of 'decryptData'
                val accessToken: String? = secureEncryptionService.decrypt(encryptedAccessToken)
                if (accessToken == null) {
                    Log.e(TAG, "Failed to decrypt access token for account $accountId.")
                    return@withContext null
                }
                // accessToken is now String?, isNotBlank is a valid call
                if (accessToken.isNotBlank()) { // Check if isNotBlank is still an issue after type correction
                    Log.d(TAG, "Access token decrypted and is not blank for $accountId.")
                } else {
                    Log.w(TAG, "Access token decrypted but is blank for $accountId.")
                }


                // Corrected: Call 'decrypt' instead of 'decryptData'
                val refreshToken =
                    encryptedRefreshToken?.let { secureEncryptionService.decrypt(it) }
                if (encryptedRefreshToken != null && refreshToken == null) {
                    Log.w(
                        TAG,
                        "Failed to decrypt refresh token for account $accountId, but proceeding without it if not critical."
                    )
                } else if (refreshToken != null) {
                    Log.d(TAG, "Refresh token decrypted for $accountId.")
                }

                // Corrected: Call 'decrypt' instead of 'decryptData'
                val idToken = encryptedIdToken?.let { secureEncryptionService.decrypt(it) }
                if (encryptedIdToken != null && idToken == null) {
                    Log.w(TAG, "Failed to decrypt ID token for account $accountId.")
                } else if (idToken != null) {
                    Log.d(TAG, "ID token decrypted for $accountId.")
                }

                val scopes = scopesString?.split(" ") ?: emptyList()
                val expiresInTimestamp = expiresInTimestampString?.toLongOrNull() ?: 0L

                Log.i(
                    TAG,
                    "Tokens retrieved successfully for account $accountId. AccessToken Present: ${accessToken.isNotBlank()}, RefreshToken Present: ${refreshToken != null}, ExpiresAt: $expiresInTimestamp"
                )
                return@withContext AppAuthHelperService.GoogleTokenData(
                    accessToken = accessToken, // This is now String, not String? if decryption must succeed
                    refreshToken = refreshToken,
                    idToken = idToken,
                    tokenType = tokenType, // This should be String, not Any?
                    scopes = scopes,
                    expiresIn = expiresInTimestamp
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving or decrypting tokens for account $accountId", e)
                return@withContext null
            }
        }

    suspend fun getAccountInfo(accountId: String): Map<String, String?> =
        withContext(ioDispatcher) {
            Log.d(TAG, "getAccountInfo called for accountId: ${accountId.take(5)}...")
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
            val account = accounts.find { it.name == accountId }
            if (account == null) {
                Log.w(TAG, "getAccountInfo: Account not found for ID $accountId")
                return@withContext emptyMap()
            }
            val email = accountManager.getUserData(account, KEY_EMAIL)
            val displayName = accountManager.getUserData(account, KEY_DISPLAY_NAME)
            Log.d(TAG, "getAccountInfo for $accountId: Email=$email, DisplayName=$displayName")
            mapOf("email" to email, "displayName" to displayName)
        }

    suspend fun clearTokens(accountId: String, removeAccount: Boolean): Boolean =
        withContext(ioDispatcher) {
            Log.i(
                TAG,
                "clearTokens called for accountId: ${accountId.take(5)}..., removeAccount: $removeAccount"
            )
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
            val account = accounts.find { it.name == accountId }

            if (account == null) {
                Log.w(TAG, "Account $accountId not found in AccountManager for clearing tokens.")
                return@withContext false
            }

            if (removeAccount) {
            try {
                val removedSuccessfully = accountManager.removeAccountExplicitly(account)
                if (removedSuccessfully) {
                    Log.i(
                        TAG,
                        "Google account $accountId removed successfully from AccountManager."
                    )
                } else {
                    Log.e(TAG, "Failed to remove Google account $accountId from AccountManager.")
                }
                return@withContext removedSuccessfully
            } catch (e: Exception) {
                Log.e(TAG, "Exception while removing account $accountId from AccountManager", e)
                return@withContext false
            }
            } else {
                Log.d(TAG, "Clearing token data (but not account) for $accountId.")
                accountManager.setUserData(account, KEY_ACCESS_TOKEN, null)
                accountManager.setUserData(account, KEY_REFRESH_TOKEN, null)
                accountManager.setUserData(account, KEY_ID_TOKEN, null)
                accountManager.setUserData(account, KEY_TOKEN_TYPE, null)
                accountManager.setUserData(account, KEY_SCOPES, null)
                accountManager.setUserData(account, KEY_EXPIRES_IN_TIMESTAMP, null)
                Log.i(
                    TAG,
                    "Token data cleared for Google account $accountId (account itself retained)."
                )
                return@withContext true
            }
        }

    suspend fun getAllPersistedAccounts(): List<PersistedGoogleAccount> =
        withContext(ioDispatcher) {
            Log.d(TAG, "getAllPersistedAccounts called.")
        try {
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
            Log.d(TAG, "Found ${accounts.size} accounts of type $ACCOUNT_TYPE_GOOGLE.")
            return@withContext accounts.mapNotNull { acc ->
                val accountId = acc.name
                if (accountId.isNullOrBlank()) {
                    Log.w(TAG, "Found an account with blank name, skipping.")
                    null
                } else {
                    val email = accountManager.getUserData(acc, KEY_EMAIL)
                    val displayName = accountManager.getUserData(acc, KEY_DISPLAY_NAME)
                    Log.v(
                        TAG,
                        "Mapping persisted account: ID=${accountId.take(5)}..., Email=$email, Name=$displayName"
                    )
                    PersistedGoogleAccount(accountId, email, displayName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all persisted Google accounts", e)
            return@withContext emptyList()
        }
    }
}
