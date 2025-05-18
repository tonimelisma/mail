package net.melisma.backend_microsoft.auth

import android.accounts.AccountManager
import android.content.Context
import androidx.core.os.bundleOf
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.melisma.backend_microsoft.common.PersistenceErrorType
import net.melisma.backend_microsoft.model.PersistedMicrosoftAccount
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.security.SecureEncryptionService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import android.accounts.Account as AndroidAccount

@Singleton
class MicrosoftTokenPersistenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val secureEncryptionService: SecureEncryptionService
) {

    companion object {
        // Constants moved from MicrosoftAuthManager
        const val ACCOUNT_TYPE_MICROSOFT = "net.melisma.mail.MICROSOFT"
        private const val KEY_ACCESS_TOKEN = "msAccessToken"
        private const val KEY_ID_TOKEN = "msIdToken"
        private const val KEY_ACCOUNT_ID_MSAL = "msalAccountId" // Stores IAccount.getId()
        private const val KEY_USERNAME = "msUsername"
        private const val KEY_TENANT_ID = "msTenantId"
        private const val KEY_SCOPES = "msScopes" // If we decide to persist this
        private const val KEY_EXPIRES_ON_TIMESTAMP =
            "msExpiresOnTimestamp" // If we decide to persist this
        private const val KEY_DISPLAY_NAME = "msDisplayName"

        private const val TAG = "MsTokenPersistService"
    }

    suspend fun saveAccountInfo(
        msalAccount: IAccount,
        authResult: IAuthenticationResult?, // Can be null if only saving account identifiers without fresh tokens
        displayNameFromClaims: String?
    ): PersistenceResult<Unit> = withContext(Dispatchers.IO) {
        val accountManagerName = msalAccount.id ?: run {
            Timber.tag(TAG).e("Cannot save account: MSAL Account ID (IAccount.getId()) is null.")
            return@withContext PersistenceResult.Failure(
                PersistenceErrorType.INVALID_ARGUMENT,
                "MSAL Account ID (IAccount.getId()) is null."
            )
        }
        val msalAccountId = msalAccount.id!! // Already checked for null
        val username = msalAccount.username
        val tenantId = msalAccount.tenantId
        val displayName = displayNameFromClaims ?: username // Fallback to username

        Timber.tag(TAG)
            .d("Attempting to save account info for MSAL Account ID: ${msalAccountId.take(10)}..., Username: $username")

        try {
            val androidAccount = AndroidAccount(accountManagerName, ACCOUNT_TYPE_MICROSOFT)

            val userData = bundleOf()
            userData.putString(KEY_ACCOUNT_ID_MSAL, msalAccountId)
            userData.putString(KEY_USERNAME, username)
            userData.putString(KEY_TENANT_ID, tenantId)
            userData.putString(KEY_DISPLAY_NAME, displayName)

            authResult?.accessToken?.let { token ->
                secureEncryptionService.encrypt(token)?.let { encryptedToken ->
                    userData.putString(KEY_ACCESS_TOKEN, encryptedToken)
                } ?: Timber.tag(TAG).e("Failed to encrypt access token for $accountManagerName.")
                    .run {
                        return@withContext PersistenceResult.Failure(
                            PersistenceErrorType.ENCRYPTION_FAILED,
                            "Failed to encrypt access token for $accountManagerName."
                        )
                    }
            }

            // IAccount.getIdToken() might be stale, prefer from IAuthenticationResult if available
            val idTokenToStore = authResult?.idToken ?: msalAccount.idToken
            idTokenToStore?.let { token ->
                secureEncryptionService.encrypt(token)?.let { encryptedToken ->
                    userData.putString(KEY_ID_TOKEN, encryptedToken)
                } ?: Timber.tag(TAG).e("Failed to encrypt ID token for $accountManagerName.").run {
                    return@withContext PersistenceResult.Failure(
                        PersistenceErrorType.ENCRYPTION_FAILED,
                        "Failed to encrypt ID token for $accountManagerName."
                    )
                }
            }

            authResult?.scope?.joinToString(" ")?.let { scopes ->
                userData.putString(KEY_SCOPES, scopes)
            }
            authResult?.expiresOn?.time?.let { expires ->
                userData.putString(KEY_EXPIRES_ON_TIMESTAMP, expires.toString())
            }

            // Remove existing account to prevent issues with addAccountExplicitly if account already exists with different signature
            val removalResult = removeAccountFromManagerInternal(
                accountManagerName,
                clearOnlyUserData = false,
                calledDuringSave = true
            )
            if (removalResult is PersistenceResult.Failure && !calledDuringSave) { // Only fail if not called during save and proper removal failed
                Timber.tag(TAG)
                    .w("Failed to remove pre-existing account for $accountManagerName during save: ${removalResult.errorType}, but proceeding with add.")
                // Don't return failure here, allow addAccountExplicitly to proceed.
                // If addAccountExplicitly also fails, that will be the reported error.
            }

            if (accountManager.addAccountExplicitly(androidAccount, null, userData)) {
                Timber.tag(TAG)
                    .i("Successfully saved account info to AccountManager for: $accountManagerName")
                return@withContext PersistenceResult.Success(Unit)
            } else {
                Timber.tag(TAG)
                    .e("Failed to add Microsoft account explicitly to AccountManager for: $accountManagerName")
                return@withContext PersistenceResult.Failure(
                    PersistenceErrorType.STORAGE_FAILED,
                    "Failed to add Microsoft account explicitly to AccountManager for: $accountManagerName"
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error saving Microsoft account info for: $accountManagerName")
            return@withContext PersistenceResult.Failure(
                PersistenceErrorType.UNKNOWN_ERROR,
                "Error saving Microsoft account info for: $accountManagerName",
                e
            )
        }
    }

    suspend fun getPersistedAccount(accountManagerName: String): PersistenceResult<PersistedMicrosoftAccount> =
        withContext(Dispatchers.IO) {
            Timber.tag(TAG)
                .d("Attempting to retrieve PersistedMicrosoftAccount for name: $accountManagerName")
            val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            val account = accounts.firstOrNull { it.name == accountManagerName }

            if (account == null) {
                Timber.tag(TAG)
                    .w("No Microsoft account found in AccountManager for name: $accountManagerName")
                return@withContext PersistenceResult.Failure(
                    PersistenceErrorType.ACCOUNT_NOT_FOUND,
                    "No Microsoft account found in AccountManager for name: $accountManagerName"
                )
            }

            try {
                val msalAccountId =
                    accountManager.getUserData(account, KEY_ACCOUNT_ID_MSAL) ?: run {
                        Timber.tag(TAG)
                            .w("MSAL Account ID is null for ${account.name}. Cannot construct PersistedMicrosoftAccount.")
                        return@withContext PersistenceResult.Failure(
                            PersistenceErrorType.MISSING_DATA,
                            "MSAL Account ID is null for ${account.name}"
                        )
                    }
                val username = accountManager.getUserData(account, KEY_USERNAME)
                val displayName = accountManager.getUserData(account, KEY_DISPLAY_NAME)
                val tenantId = accountManager.getUserData(account, KEY_TENANT_ID)

                return@withContext PersistenceResult.Success(
                    PersistedMicrosoftAccount(
                        accountManagerName = account.name,
                        msalAccountId = msalAccountId,
                        username = username,
                        displayName = displayName,
                        tenantId = tenantId
                    )
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(
                    e,
                    "Error retrieving PersistedMicrosoftAccount for name: $accountManagerName"
                )
                return@withContext PersistenceResult.Failure(
                    PersistenceErrorType.UNKNOWN_ERROR,
                    "Error retrieving PersistedMicrosoftAccount for name: $accountManagerName",
                    e
                )
            }
        }

    suspend fun getAllPersistedAccounts(): List<PersistedMicrosoftAccount> =
        withContext(Dispatchers.IO) {
            Timber.tag(TAG).d("Attempting to retrieve all PersistedMicrosoftAccounts")
            val androidAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            if (androidAccounts.isEmpty()) {
                Timber.tag(TAG).d("No Microsoft accounts found in AccountManager.")
                return@withContext emptyList()
            }

            val persistedAccounts = mutableListOf<PersistedMicrosoftAccount>()
            for (androidAccount in androidAccounts) {
                when (val result = getPersistedAccount(androidAccount.name)) {
                    is PersistenceResult.Success -> persistedAccounts.add(result.data)
                    is PersistenceResult.Failure -> Timber.tag(TAG)
                        .w("Failed to get persisted account ${androidAccount.name} in getAll: ${result.errorType} - ${result.message}")
                }
            }
            Timber.tag(TAG)
                .d("Retrieved ${persistedAccounts.size} PersistedMicrosoftAccount objects.")
            return@withContext persistedAccounts
        }

    suspend fun getPersistedAccessToken(accountManagerName: String): PersistenceResult<String> =
        withContext(Dispatchers.IO) {
            val account = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
                .firstOrNull { it.name == accountManagerName }
            if (account == null) {
                Timber.tag(TAG).w("No account found for $accountManagerName to get access token.")
                return@withContext PersistenceResult.Failure(
                    PersistenceErrorType.ACCOUNT_NOT_FOUND,
                    "No account found for $accountManagerName to get access token."
                )
            }
            val encryptedToken = accountManager.getUserData(account, KEY_ACCESS_TOKEN) ?: run {
                return@withContext PersistenceResult.Failure(
                    PersistenceErrorType.MISSING_DATA,
                    "Encrypted access token not found for $accountManagerName."
                )
            }

            return@withContext try {
                secureEncryptionService.decrypt(encryptedToken)?.let {
                    PersistenceResult.Success(it)
                } ?: PersistenceResult.Failure(
                    PersistenceErrorType.DECRYPTION_FAILED,
                    "Failed to decrypt access token for $accountManagerName"
                ).also {
                    Timber.tag(TAG).e("Failed to decrypt access token for $accountManagerName")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Decryption error for access token for $accountManagerName")
                PersistenceResult.Failure(
                    PersistenceErrorType.DECRYPTION_FAILED,
                    "Decryption error for access token for $accountManagerName",
                    e
                )
            }
        }

    suspend fun getPersistedIdToken(accountManagerName: String): PersistenceResult<String> =
        withContext(Dispatchers.IO) {
            val account = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
                .firstOrNull { it.name == accountManagerName }
            if (account == null) {
                Timber.tag(TAG).w("No account found for $accountManagerName to get ID token.")
                return@withContext PersistenceResult.Failure(
                    PersistenceErrorType.ACCOUNT_NOT_FOUND,
                    "No account found for $accountManagerName to get ID token."
                )
            }
            val encryptedToken = accountManager.getUserData(account, KEY_ID_TOKEN) ?: run {
                return@withContext PersistenceResult.Failure(
                    PersistenceErrorType.MISSING_DATA,
                    "Encrypted ID token not found for $accountManagerName."
                )
            }
            return@withContext try {
                secureEncryptionService.decrypt(encryptedToken)?.let {
                    PersistenceResult.Success(it)
                } ?: PersistenceResult.Failure(
                    PersistenceErrorType.DECRYPTION_FAILED,
                    "Failed to decrypt ID token for $accountManagerName"
                ).also {
                    Timber.tag(TAG).e("Failed to decrypt ID token for $accountManagerName")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Decryption error for ID token for $accountManagerName")
                PersistenceResult.Failure(
                    PersistenceErrorType.DECRYPTION_FAILED,
                    "Decryption error for ID token for $accountManagerName",
                    e
                )
            }
        }

    suspend fun clearAccountData(
        accountManagerName: String,
        removeAccountFromManager: Boolean
    ): PersistenceResult<Unit> = withContext(Dispatchers.IO) {
        Timber.tag(TAG)
            .d("Clearing account data for $accountManagerName, remove: $removeAccountFromManager")
        return@withContext removeAccountFromManagerInternal(
            accountManagerName,
            clearOnlyUserData = !removeAccountFromManager
        )
    }

    private fun removeAccountFromManagerInternal(
        accountManagerName: String,
        clearOnlyUserData: Boolean,
        calledDuringSave: Boolean = false
    ): PersistenceResult<Unit> {
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
        val account = accounts.firstOrNull { it.name == accountManagerName }

        if (account == null) {
            if (!calledDuringSave) { // Don't warn if this is called pre-emptively during save
                Timber.tag(TAG)
                    .w("No Microsoft account found to clear/remove for name: $accountManagerName")
            }
            return PersistenceResult.Success(Unit) // No account means it's already "cleared" or "removed"
        }

        return try {
            if (clearOnlyUserData) {
                Timber.tag(TAG).d("Clearing user data for account: $accountManagerName")
                accountManager.setUserData(account, KEY_ACCESS_TOKEN, null)
                accountManager.setUserData(account, KEY_ID_TOKEN, null)
                accountManager.setUserData(account, KEY_ACCOUNT_ID_MSAL, null)
                accountManager.setUserData(account, KEY_USERNAME, null)
                accountManager.setUserData(account, KEY_TENANT_ID, null)
                accountManager.setUserData(account, KEY_DISPLAY_NAME, null)
                accountManager.setUserData(account, KEY_SCOPES, null)
                accountManager.setUserData(account, KEY_EXPIRES_ON_TIMESTAMP, null)
                // Add any other keys that might be persisted
            } else {
                // This is a full removal
                Timber.tag(TAG).d("Attempting to remove account explicitly: $accountManagerName")
                val removed = accountManager.removeAccountExplicitly(account)

                if (removed) {
                    Timber.tag(TAG)
                        .i("Successfully removed Microsoft account from AccountManager: $accountManagerName")
                } else {
                    Timber.tag(TAG)
                        .e("Failed to remove Microsoft account from AccountManager: $accountManagerName. Attempting to clear user data as fallback.")
                    // Fallback: if remove failed, at least try to clear its data
                    val clearFallbackResult = removeAccountFromManagerInternal(
                        accountManagerName,
                        clearOnlyUserData = true,
                        calledDuringSave = calledDuringSave
                    )
                    return if (clearFallbackResult is PersistenceResult.Success) {
                        PersistenceResult.Failure(
                            PersistenceErrorType.OPERATION_FAILED,
                            "Failed to remove account, but fallback data clear succeeded for $accountManagerName."
                        )
                    } else {
                        PersistenceResult.Failure(
                            PersistenceErrorType.OPERATION_FAILED,
                            "Failed to remove account and also failed to clear user data for $accountManagerName.",
                            clearFallbackResult.cause
                        )
                    }
                }
            }
            PersistenceResult.Success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG)
                .e(e, "Error during clear/remove for Microsoft account name: $accountManagerName")
            PersistenceResult.Failure(
                PersistenceErrorType.UNKNOWN_ERROR,
                "Error during clear/remove for Microsoft account name: $accountManagerName",
                e
            )
        }
    }
} 