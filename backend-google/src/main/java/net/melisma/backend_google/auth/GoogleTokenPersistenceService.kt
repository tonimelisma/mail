package net.melisma.backend_google.auth

import android.accounts.AccountManager
import android.content.Context
import androidx.core.os.bundleOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val activeGoogleAccountHolder: ActiveGoogleAccountHolder
) {

    companion object {
        const val ACCOUNT_TYPE_GOOGLE = "net.melisma.mail.GOOGLE"
        private const val KEY_AUTH_STATE_JSON = "authStateJson"
        private const val KEY_USER_EMAIL = "userEmail"
        private const val KEY_USER_DISPLAY_NAME = "userDisplayName"
        private const val KEY_USER_PHOTO_URL = "userPhotoUrl"
    }

    suspend fun saveTokens(
        accountId: String,
        email: String?,
        displayName: String?,
        photoUrl: String?,
        tokenResponse: TokenResponse
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.d("Attempting to save tokens for Google account ID: %s, Email: %s", accountId, email)
        try {
            val authState = AuthState(AppAuthHelperService.serviceConfig)
            authState.update(tokenResponse, null)

            val authStateJson = authState.jsonSerializeString()
            val encryptedAuthStateJson = secureEncryptionService.encrypt(authStateJson)
            if (encryptedAuthStateJson == null) {
                Timber.e("Failed to encrypt AuthState for account ID: %s", accountId)
                return@withContext false
            }

            val androidAccount = AndroidAccount(accountId, ACCOUNT_TYPE_GOOGLE)
            val userData = bundleOf(
                KEY_AUTH_STATE_JSON to encryptedAuthStateJson,
                KEY_USER_EMAIL to email,
                KEY_USER_DISPLAY_NAME to displayName,
                KEY_USER_PHOTO_URL to photoUrl
            )

            removeAccountFromManager(accountId)

            if (accountManager.addAccountExplicitly(androidAccount, null, userData)) {
                Timber.i("Successfully saved tokens and account info for Google ID: %s", accountId)
                activeGoogleAccountHolder.setActiveAccountId(accountId)
                return@withContext true
            } else {
                Timber.e(
                    "Failed to add Google account explicitly to AccountManager for ID: %s",
                    accountId
                )
                return@withContext false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error saving Google tokens for account ID: %s", accountId)
            return@withContext false
        }
    }

    suspend fun getAuthState(accountId: String): AuthState? = withContext(Dispatchers.IO) {
        Timber.d("Attempting to retrieve AuthState for Google account ID: %s", accountId)
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
        val account = accounts.firstOrNull { it.name == accountId }

        if (account == null) {
            Timber.w("No Google account found in AccountManager for ID: %s", accountId)
            return@withContext null
        }

        try {
            val encryptedAuthStateJson = accountManager.getUserData(account, KEY_AUTH_STATE_JSON)
            if (encryptedAuthStateJson == null) {
                Timber.e("No AuthState JSON found in AccountManager for ID: %s", accountId)
                return@withContext null
            }

            val authStateJson = secureEncryptionService.decrypt(encryptedAuthStateJson)
            if (authStateJson == null) {
                Timber.e("Failed to decrypt AuthState JSON for ID: %s", accountId)
                return@withContext null
            }
            Timber.d("Successfully retrieved and decrypted AuthState for ID: %s", accountId)
            return@withContext AuthState.jsonDeserializeString(authStateJson)
        } catch (e: Exception) {
            Timber.e(e, "Error retrieving/deserializing AuthState for Google ID: %s", accountId)
            return@withContext null
        }
    }

    suspend fun clearTokens(accountId: String, removeAccount: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            Timber.d(
                "Attempting to clear tokens for Google account ID: %s, Remove account: %s",
                accountId,
                removeAccount
            )
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
            val account = accounts.firstOrNull { it.name == accountId }

            if (account == null) {
                Timber.w("No Google account found to clear for ID: %s", accountId)
                return@withContext true
            }

            return@withContext try {
            if (removeAccount) {
                val removed = accountManager.removeAccountExplicitly(account)
                if (removed) {
                    Timber.i(
                        "Successfully removed Google account from AccountManager: %s",
                        accountId
                    )
                } else {
                    Timber.e("Failed to remove Google account from AccountManager: %s", accountId)
                    clearUserDataForAccount(account)
                    return@withContext false
                }
            } else {
                clearUserDataForAccount(account)
                Timber.i(
                    "Successfully cleared user data for Google account: %s (account not removed)",
                    accountId
                )
            }
                if (activeGoogleAccountHolder.getActiveAccountIdValue() == accountId) {
                    activeGoogleAccountHolder.setActiveAccountId(null)
            }
                true
            } catch (e: Exception) {
                Timber.e(e, "Error clearing tokens for Google account ID: %s", accountId)
                false
            }
        }

    private fun clearUserDataForAccount(account: AndroidAccount) {
        accountManager.setUserData(account, KEY_AUTH_STATE_JSON, null)
        accountManager.setUserData(account, KEY_USER_EMAIL, null)
        accountManager.setUserData(account, KEY_USER_DISPLAY_NAME, null)
        accountManager.setUserData(account, KEY_USER_PHOTO_URL, null)
    }

    private fun removeAccountFromManager(accountId: String) {
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
        accounts.firstOrNull { it.name == accountId }?.let {
            try {
                Timber.d(
                    "Removing existing account from manager before saving new state: %s",
                    accountId
                )
                accountManager.removeAccountExplicitly(it)
            } catch (e: SecurityException) {
                Timber.e(
                    e,
                    "SecurityException while removing pre-existing account: %s. This might happen if app permissions changed or account authenticator is missing.",
                    accountId
                )
            }
        }
    }

    suspend fun getUserInfo(accountId: String): UserInfo? = withContext(Dispatchers.IO) {
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
        val account = accounts.firstOrNull { it.name == accountId }
        if (account == null) {
            Timber.w("No Google account found to retrieve user info for ID: %s", accountId)
            return@withContext null
        }
        try {
            val email = accountManager.getUserData(account, KEY_USER_EMAIL)
            val displayName = accountManager.getUserData(account, KEY_USER_DISPLAY_NAME)
            val photoUrl = accountManager.getUserData(account, KEY_USER_PHOTO_URL)
            return@withContext UserInfo(accountId, email, displayName, photoUrl)
        } catch (e: Exception) {
            Timber.e(e, "Error retrieving user info for Google ID: %s", accountId)
            return@withContext null
        }
    }

    suspend fun getAllGoogleUserInfos(): List<UserInfo> = withContext(Dispatchers.IO) {
        Timber.d("Attempting to retrieve UserInfo for all Google accounts")
        val androidAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
        if (androidAccounts.isEmpty()) {
            Timber.d("No Google accounts found in AccountManager.")
            return@withContext emptyList()
        }

        val userInfos = mutableListOf<UserInfo>()
        for (androidAccount in androidAccounts) {
            getUserInfo(androidAccount.name)?.let {
                userInfos.add(it)
            }
        }
        Timber.d("Retrieved ${userInfos.size} UserInfo objects.")
        return@withContext userInfos
    }

    suspend fun updateAuthState(accountId: String, newAuthState: AuthState): Boolean =
        withContext(Dispatchers.IO) {
            Timber.d("Attempting to update AuthState for Google account ID: %s", accountId)
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)
            val account = accounts.firstOrNull { it.name == accountId }

            if (account == null) {
                Timber.w(
                    "No Google account found in AccountManager to update AuthState for ID: %s",
                    accountId
                )
                return@withContext false
            }

            try {
                val encryptedAuthStateJson =
                    secureEncryptionService.encrypt(newAuthState.jsonSerializeString())
                if (encryptedAuthStateJson == null) {
                    Timber.e(
                        "Failed to encrypt AuthState for account ID during update: %s",
                        accountId
                    )
                    return@withContext false
                }

                val existingEmail = accountManager.getUserData(account, KEY_USER_EMAIL)
                val existingDisplayName = accountManager.getUserData(account, KEY_USER_DISPLAY_NAME)
                val existingPhotoUrl = accountManager.getUserData(account, KEY_USER_PHOTO_URL)

                val userData = bundleOf(
                    KEY_AUTH_STATE_JSON to encryptedAuthStateJson,
                    KEY_USER_EMAIL to existingEmail,
                    KEY_USER_DISPLAY_NAME to existingDisplayName,
                    KEY_USER_PHOTO_URL to existingPhotoUrl
                )

                removeAccountFromManager(accountId)

                val androidAccount = AndroidAccount(accountId, ACCOUNT_TYPE_GOOGLE)
                if (accountManager.addAccountExplicitly(androidAccount, null, userData)) {
                    Timber.i(
                        "Successfully updated AuthState (via re-add) for Google ID: %s",
                        accountId
                    )
                    activeGoogleAccountHolder.setActiveAccountId(accountId)
                    return@withContext true
                } else {
                    Timber.e(
                        "Failed to re-add Google account during AuthState update for ID: %s",
                        accountId
                    )
                    return@withContext false
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating/saving AuthState for Google ID: %s", accountId)
                return@withContext false
            }
        }

    data class UserInfo(
        val id: String,
        val email: String?,
        val displayName: String?,
        val photoUrl: String?
    )
} 