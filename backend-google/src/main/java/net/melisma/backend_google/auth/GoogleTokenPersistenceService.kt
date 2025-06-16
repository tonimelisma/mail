package net.melisma.backend_google.auth

import android.accounts.AccountManager
import android.content.Context
import androidx.core.os.bundleOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.melisma.backend_google.common.GooglePersistenceErrorType
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.security.SecureEncryptionService
import net.openid.appauth.AuthState
import net.openid.appauth.TokenResponse
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import android.accounts.Account as AndroidAccount

@Singleton
class GoogleTokenPersistenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val secureEncryptionService: SecureEncryptionService,
    private val appAuthHelperService: AppAuthHelperService
) {

    companion object {
        const val ACCOUNT_TYPE_GOOGLE = "net.melisma.mail.GOOGLE"
        private const val KEY_AUTH_STATE_JSON = "authStateJson"
        private const val KEY_USER_EMAIL = "userEmail"
        private const val KEY_USER_DISPLAY_NAME = "userDisplayName"
        private const val KEY_USER_PHOTO_URL = "userPhotoUrl"
        private const val TAG = "GoogleTokenPersist"
    }

    suspend fun saveTokens(
        accountId: String,
        email: String?,
        displayName: String?,
        photoUrl: String?,
        authResponse: net.openid.appauth.AuthorizationResponse,
        tokenResponse: TokenResponse
    ): PersistenceResult<Unit> = withContext(Dispatchers.IO) {
        Timber.tag(TAG)
            .d("Attempting to save tokens for Google account ID: %s, Email: %s", accountId, email)
        try {
            val authState = AuthState(
                authResponse,
                tokenResponse,
                null /* No exception on successful exchange */
            )

            Timber.tag(TAG).d(
                "saveTokens - AccountID: %s - Constructed AuthState Config JSON: %s",
                accountId,
                authState.authorizationServiceConfiguration?.toJsonString()
            )
            Timber.tag(TAG).d(
                "saveTokens - AccountID: %s - Constructed AuthState Last Auth Resp Config JSON: %s",
                accountId,
                authState.lastAuthorizationResponse?.request?.configuration?.toJsonString()
            )
            val authStateJson = authState.jsonSerializeString()
            Timber.tag(TAG).d(
                "saveTokens - AccountID: %s - Serialized AuthState JSON: %s",
                accountId,
                authStateJson
            )

            val encryptedAuthStateJson = secureEncryptionService.encrypt(authStateJson)
            if (encryptedAuthStateJson == null) {
                Timber.tag(TAG).e("Failed to encrypt AuthState for account ID: %s", accountId)
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.ENCRYPTION_FAILED,
                    "Failed to encrypt AuthState for account ID: $accountId"
                )
            }

            val androidAccount = AndroidAccount(accountId, ACCOUNT_TYPE_GOOGLE)
            val userData = bundleOf(
                KEY_AUTH_STATE_JSON to encryptedAuthStateJson,
                KEY_USER_EMAIL to email,
                KEY_USER_DISPLAY_NAME to displayName,
                KEY_USER_PHOTO_URL to photoUrl
            )

            removeAccountFromManagerInternal(
                accountId,
                clearOnlyUserData = false,
                calledDuringSave = true
            )

            if (accountManager.addAccountExplicitly(androidAccount, null, userData)) {
                Timber.tag(TAG)
                    .i("Successfully saved tokens and account info for Google ID: %s", accountId)
                return@withContext PersistenceResult.Success(Unit)
            } else {
                Timber.tag(TAG).e(
                    "Failed to add Google account explicitly to AccountManager for ID: %s",
                    accountId
                )
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.STORAGE_FAILED,
                    "Failed to add Google account explicitly for ID: $accountId"
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error saving Google tokens for account ID: %s", accountId)
            return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                GooglePersistenceErrorType.UNKNOWN_ERROR,
                "Error saving Google tokens for account ID: $accountId",
                e
            )
        }
    }

    suspend fun getAuthState(accountId: String): PersistenceResult<AuthState> =
        withContext(Dispatchers.IO) {
            Timber.tag(TAG)
                .d("Attempting to retrieve AuthState for Google account ID: %s", accountId)
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
        val account = accounts.firstOrNull { it.name == accountId }

        if (account == null) {
            Timber.tag(TAG).w("No Google account found in AccountManager for ID: %s", accountId)
            return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                GooglePersistenceErrorType.ACCOUNT_NOT_FOUND,
                "No Google account found for ID: $accountId"
            )
        }

        try {
            val encryptedAuthStateJson = accountManager.getUserData(account, KEY_AUTH_STATE_JSON)
            if (encryptedAuthStateJson == null) {
                Timber.tag(TAG).e("No AuthState JSON found in AccountManager for ID: %s", accountId)
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.MISSING_AUTH_STATE_JSON,
                    "No AuthState JSON found for ID: $accountId"
                )
            }

            val authStateJson = secureEncryptionService.decrypt(encryptedAuthStateJson)
            if (authStateJson == null) {
                Timber.tag(TAG).e("Failed to decrypt AuthState JSON for ID: %s", accountId)
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.DECRYPTION_FAILED,
                    "Failed to decrypt AuthState JSON for ID: $accountId"
                )
            }

            try {
                val deserializedAuthState = AuthState.jsonDeserialize(authStateJson)

                // ******** DEBUG LOGGING - REFRESH TOKEN ********
                val username = accountManager.getUserData(account, KEY_USER_EMAIL) ?: "N/A"
                val refreshToken = deserializedAuthState.refreshToken
                Timber.tag("TOKEN_LOG").d(
                    "Account: Google (%s), Refresh Token: %s",
                    username,
                    refreshToken
                )
                // ************************************************

                Timber.tag(TAG).d(
                    "getAuthState - AccountID: %s - Deserialized AuthState ID: %s",
                    accountId,
                    System.identityHashCode(deserializedAuthState)
                )
                Timber.tag(TAG).d(
                    "getAuthState - AccountID: %s - Deserialized AuthState JSON: %s",
                    accountId,
                    deserializedAuthState.jsonSerializeString()
                )
                Timber.tag(TAG).d(
                    "getAuthState - AccountID: %s - Deserialized AuthState Config JSON: %s",
                    accountId,
                    deserializedAuthState.authorizationServiceConfiguration?.toJsonString()
                )
                Timber.tag(TAG).d(
                    "getAuthState - AccountID: %s - Deserialized AuthState Last Auth Resp Config JSON: %s",
                    accountId,
                    deserializedAuthState.lastAuthorizationResponse?.request?.configuration?.toJsonString()
                )
                Timber.tag(TAG)
                    .d("Successfully retrieved and decrypted AuthState for ID: %s", accountId)
                return@withContext PersistenceResult.Success(deserializedAuthState)
            } catch (jsonEx: org.json.JSONException) {
                Timber.tag(TAG)
                    .e(jsonEx, "Failed to deserialize AuthState JSON for ID: %s. JSON was: %s", accountId, authStateJson)
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.AUTH_STATE_DESERIALIZATION_FAILED,
                    "Failed to deserialize AuthState for ID: $accountId",
                    jsonEx
                )
            }

        } catch (e: Exception) {
            Timber.tag(TAG)
                .e(e, "Error retrieving/deserializing AuthState for Google ID: %s", accountId)
            return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                GooglePersistenceErrorType.UNKNOWN_ERROR,
                "Error retrieving/deserializing AuthState for ID: $accountId",
                e
            )
        }
    }

    suspend fun clearTokens(
        accountId: String,
        removeAccountFromManagerFlag: Boolean = true
    ): PersistenceResult<Unit> =
        withContext(Dispatchers.IO) {
            Timber.tag(TAG).d(
                "Attempting to clear tokens for Google account ID: %s, Remove account: %s",
                accountId,
                removeAccountFromManagerFlag
            )
            return@withContext removeAccountFromManagerInternal(
                accountId,
                clearOnlyUserData = !removeAccountFromManagerFlag,
                calledDuringSave = false
            )
        }

    private fun removeAccountFromManagerInternal(
        accountId: String,
        clearOnlyUserData: Boolean,
        calledDuringSave: Boolean = false
    ): PersistenceResult<Unit> {
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
        val account = accounts.firstOrNull { it.name == accountId }

        if (account == null) {
            if (!calledDuringSave) {
                Timber.tag(TAG).w("No Google account found to clear/remove for ID: %s", accountId)
            }
            return PersistenceResult.Success(Unit)
        }

        return try {
            if (clearOnlyUserData) {
                Timber.tag(TAG).d("Clearing user data for Google account: %s", accountId)
                clearUserDataForAccountInternal(account)
            } else {
                Timber.tag(TAG).d("Attempting to remove Google account explicitly: %s", accountId)
                val removed = accountManager.removeAccountExplicitly(account)
                if (removed) {
                    Timber.tag(TAG)
                        .i("Successfully removed Google account from AccountManager: %s", accountId)
                } else {
                    Timber.tag(TAG).e(
                        "Failed to remove Google account from AccountManager: %s. Attempting to clear user data as fallback.",
                        accountId
                    )
                    clearUserDataForAccountInternal(account)
                    return PersistenceResult.Failure<GooglePersistenceErrorType>(
                        GooglePersistenceErrorType.STORAGE_FAILED,
                        "Failed to remove account, fallback data clear attempted for $accountId."
                    )
                }
            }
            PersistenceResult.Success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during clear/remove for Google account ID: %s", accountId)
            PersistenceResult.Failure<GooglePersistenceErrorType>(
                GooglePersistenceErrorType.UNKNOWN_ERROR,
                "Error during clear/remove for account ID: $accountId",
                e
            )
        }
    }

    private fun clearUserDataForAccountInternal(account: AndroidAccount) {
        Timber.tag(TAG).d("Clearing all user data fields for account: ${account.name}")
        accountManager.setUserData(account, KEY_AUTH_STATE_JSON, null)
        accountManager.setUserData(account, KEY_USER_EMAIL, null)
        accountManager.setUserData(account, KEY_USER_DISPLAY_NAME, null)
        accountManager.setUserData(account, KEY_USER_PHOTO_URL, null)
    }

    suspend fun getUserInfo(accountId: String): PersistenceResult<UserInfo> =
        withContext(Dispatchers.IO) {
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
        val account = accounts.firstOrNull { it.name == accountId }
        if (account == null) {
            Timber.tag(TAG).w("No Google account found to retrieve user info for ID: %s", accountId)
            return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                GooglePersistenceErrorType.ACCOUNT_NOT_FOUND,
                "No Google account found for UserInfo for ID: $accountId"
            )
        }
        try {
            val email = accountManager.getUserData(account, KEY_USER_EMAIL)
            val displayName = accountManager.getUserData(account, KEY_USER_DISPLAY_NAME)
            val photoUrl = accountManager.getUserData(account, KEY_USER_PHOTO_URL)
            return@withContext PersistenceResult.Success(
                UserInfo(
                    account.name,
                    email,
                    displayName,
                    photoUrl
                )
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error retrieving user info for Google ID: %s", accountId)
            return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                GooglePersistenceErrorType.UNKNOWN_ERROR,
                "Error retrieving UserInfo for ID: $accountId",
                e
            )
        }
    }

    suspend fun getAllGoogleUserInfos(): PersistenceResult<List<UserInfo>> =
        withContext(Dispatchers.IO) {
            Timber.tag(TAG).d("Attempting to retrieve UserInfo for all Google accounts")
            try {
                val androidAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
                if (androidAccounts.isEmpty()) {
                    Timber.tag(TAG).d("No Google accounts found in AccountManager.")
                    return@withContext PersistenceResult.Success(emptyList())
                }

                val userInfos = mutableListOf<UserInfo>()
                for (androidAccount in androidAccounts) {
                    when (val userInfoResult = getUserInfo(androidAccount.name)) {
                        is PersistenceResult.Success -> userInfos.add(userInfoResult.data)
                        is PersistenceResult.Failure<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val failure =
                                userInfoResult as PersistenceResult.Failure<GooglePersistenceErrorType>
                            Timber.tag(TAG)
                                .w(
                                    "Failed to get UserInfo for account ${androidAccount.name} in getAll: ${failure.errorType.name} - ${failure.message}",
                                    failure.cause
                                )
                        }
                    }
                }
                Timber.tag(TAG).d("Retrieved ${userInfos.size} UserInfo objects.")
                return@withContext PersistenceResult.Success(userInfos)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error retrieving all Google UserInfos")
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.UNKNOWN_ERROR,
                    "Error retrieving all UserInfos",
                    e
                )
            }
    }

    suspend fun updateAuthState(
        accountId: String,
        newAuthState: AuthState
    ): PersistenceResult<Unit> =
        withContext(Dispatchers.IO) {
            Timber.tag(TAG).d("Attempting to update AuthState for Google account ID: %s", accountId)
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
            val account = accounts.firstOrNull { it.name == accountId }

            if (account == null) {
                Timber.tag(TAG).w(
                    "No Google account found in AccountManager to update AuthState for ID: %s",
                    accountId
                )
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.ACCOUNT_NOT_FOUND,
                    "No Google account found to update AuthState for ID: $accountId"
                )
            }

            try {
                val newAuthStateJson = newAuthState.jsonSerializeString()
                val encryptedAuthStateJson = secureEncryptionService.encrypt(newAuthStateJson)

                if (encryptedAuthStateJson == null) {
                    Timber.tag(TAG).e(
                        "Failed to encrypt AuthState for account ID during update: %s",
                        accountId
                    )
                    Timber.tag(TAG).d(
                        "updateAuthState - AccountID: %s - AuthState to encrypt JSON: %s",
                        accountId,
                        newAuthStateJson
                    )
                    return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                        GooglePersistenceErrorType.ENCRYPTION_FAILED,
                        "Failed to encrypt AuthState during update for ID: $accountId"
                    )
                }
                Timber.tag(TAG).d(
                    "updateAuthState - AccountID: %s - Encrypted AuthState to save: %s",
                    accountId,
                    encryptedAuthStateJson
                )

                val existingEmail = accountManager.getUserData(account, KEY_USER_EMAIL)
                val existingDisplayName = accountManager.getUserData(account, KEY_USER_DISPLAY_NAME)
                val existingPhotoUrl = accountManager.getUserData(account, KEY_USER_PHOTO_URL)

                val userData = bundleOf(
                    KEY_AUTH_STATE_JSON to encryptedAuthStateJson,
                    KEY_USER_EMAIL to existingEmail,
                    KEY_USER_DISPLAY_NAME to existingDisplayName,
                    KEY_USER_PHOTO_URL to existingPhotoUrl
                )

                removeAccountFromManagerInternal(
                    accountId,
                    clearOnlyUserData = false,
                    calledDuringSave = true
                )

                val androidAccount = AndroidAccount(accountId, ACCOUNT_TYPE_GOOGLE)
                if (accountManager.addAccountExplicitly(androidAccount, null, userData)) {
                    Timber.tag(TAG).i(
                        "Successfully updated AuthState (via re-add) for Google ID: %s",
                        accountId
                    )
                    return@withContext PersistenceResult.Success(Unit)
                } else {
                    Timber.tag(TAG).e(
                        "Failed to re-add Google account during AuthState update for ID: %s",
                        accountId
                    )
                    return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                        GooglePersistenceErrorType.TOKEN_UPDATE_FAILED,
                        "Failed to re-add account during AuthState update for ID: $accountId"
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error updating/saving AuthState for Google ID: %s", accountId)
                return@withContext PersistenceResult.Failure<GooglePersistenceErrorType>(
                    GooglePersistenceErrorType.UNKNOWN_ERROR,
                    "Error updating AuthState for ID: $accountId",
                    e
                )
            }
        }

    data class UserInfo(
        val id: String,
        val email: String?,
        val displayName: String?,
        val photoUrl: String?
    )

    /**
     * !!! THIS IS FOR ANDROID_TESTS ONLY !!!
     * Bypasses the complex AuthState creation and saves a raw refresh token.
     * The production code can still deserialize this minimal JSON to get the token.
     */
    internal suspend fun saveRawRefreshTokenForTest(
        accountId: String,
        email: String,
        refreshToken: String
    ): PersistenceResult<Unit> = withContext(Dispatchers.IO) {
        Timber.tag(TAG).w("!!! USING TEST-ONLY METHOD saveRawRefreshTokenForTest !!!")
        try {
            // Create a minimal fake AuthState JSON that only contains the refresh token
            val fakeAuthStateJson = """
                {
                    "refreshToken": "$refreshToken"
                }
            """.trimIndent()

            val encryptedAuthStateJson = secureEncryptionService.encrypt(fakeAuthStateJson)
            if (encryptedAuthStateJson == null) {
                Timber.tag(TAG).e("Failed to encrypt fake AuthState for test account: $accountId")
                return@withContext PersistenceResult.Failure(
                    GooglePersistenceErrorType.ENCRYPTION_FAILED,
                    "Failed to encrypt fake AuthState for test account"
                )
            }

            val androidAccount = AndroidAccount(accountId, ACCOUNT_TYPE_GOOGLE)
            val userData = bundleOf(
                KEY_AUTH_STATE_JSON to encryptedAuthStateJson,
                KEY_USER_EMAIL to email
            )

            // Ensure no old account exists
            removeAccountFromManagerInternal(accountId, clearOnlyUserData = false, calledDuringSave = true)

            if (accountManager.addAccountExplicitly(androidAccount, null, userData)) {
                Timber.tag(TAG).i("Successfully saved RAW refresh token for test account: $accountId")
                PersistenceResult.Success(Unit)
            } else {
                Timber.tag(TAG).e("Failed to add test account explicitly.")
                PersistenceResult.Failure(
                    GooglePersistenceErrorType.STORAGE_FAILED,
                    "Failed to add test account"
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in saveRawRefreshTokenForTest")
            PersistenceResult.Failure(GooglePersistenceErrorType.UNKNOWN_ERROR, e.message, e)
        }
    }
} 