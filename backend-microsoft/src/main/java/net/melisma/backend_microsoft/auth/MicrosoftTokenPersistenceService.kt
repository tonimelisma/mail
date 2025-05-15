package net.melisma.backend_microsoft.auth

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.microsoft.identity.client.IAuthenticationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.security.SecureEncryptionService
import javax.inject.Inject
import javax.inject.Singleton

private const val ACCOUNT_TYPE_MICROSOFT = "net.melisma.mail.MICROSOFT"
private const val KEY_ACCESS_TOKEN = "msAccessToken"
private const val KEY_ID_TOKEN = "msIdToken" // MSAL IAccount can provide this
private const val KEY_ACCOUNT_ID_MSAL = "msalAccountId" // IAccount.getId()
private const val KEY_USERNAME = "msUsername" // IAccount.getUsername()
private const val KEY_TENANT_ID = "msTenantId" // IAccount.getTenantId()
private const val KEY_SCOPES = "msScopes"
private const val KEY_EXPIRES_ON_TIMESTAMP = "msExpiresOnTimestamp"
private const val KEY_DISPLAY_NAME = "msDisplayName"
// No KEY_REFRESH_TOKEN as MSAL manages it internally. We store IAccount identifiers.

@Singleton
class MicrosoftTokenPersistenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureEncryptionService: SecureEncryptionService,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "MsTokenPersistSvc"
    private val accountManager: AccountManager = AccountManager.get(context)

    data class PersistedMicrosoftAccount(
        val accountManagerName: String, // This will be IAccount.getId()
        val msalAccountId: String, // IAccount.getId() - can be derived from accountManagerName
        val username: String?, // Email/UPN
        val displayName: String?,
        val tenantId: String?
    )

    suspend fun saveAccountInfo(
        authResult: IAuthenticationResult
    ): Boolean = withContext(ioDispatcher) {
        val account = authResult.account
        val accountManagerName =
            account.id // Use IAccount.getId() as the unique name for AccountManager
        val msalAccountId = account.id
        val username = account.username
        val tenantId = account.tenantId
        val claims = account.claims
        val displayName = claims?.get("name") as? String ?: username // Fallback to username

        Log.d(
            TAG,
            "saveAccountInfo called for MSAL Account ID: ${msalAccountId.take(10)}..., Username: $username"
        )

        if (accountManagerName.isBlank()) {
            Log.e(TAG, "Cannot save account: MSAL Account ID (IAccount.getId()) is blank.")
            return@withContext false
        }

        val amAccount = android.accounts.Account(accountManagerName, ACCOUNT_TYPE_MICROSOFT)
        val accountAdded = accountManager.addAccountExplicitly(amAccount, null, null)

        if (accountAdded) {
            Log.i(TAG, "New Microsoft account added to AccountManager: $accountManagerName")
        } else {
            Log.d(TAG, "Microsoft account $accountManagerName already exists, updating data.")
        }

        try {
            // Encrypt and store Access Token
            authResult.accessToken?.let {
                secureEncryptionService.encrypt(it)?.let { encrypted ->
                    accountManager.setUserData(amAccount, KEY_ACCESS_TOKEN, encrypted)
                    Log.d(TAG, "Access token stored for $accountManagerName.")
                } ?: Log.e(TAG, "Failed to encrypt access token for $accountManagerName.")
            }

            // Encrypt and store ID Token (if available from IAccount)
            account.idToken?.let {
                secureEncryptionService.encrypt(it)?.let { encrypted ->
                    accountManager.setUserData(amAccount, KEY_ID_TOKEN, encrypted)
                    Log.d(TAG, "ID token stored for $accountManagerName.")
                } ?: Log.e(TAG, "Failed to encrypt ID token for $accountManagerName.")
            }

            // Store non-sensitive IAccount identifiers needed by MSAL to find the account
            accountManager.setUserData(
                amAccount,
                KEY_ACCOUNT_ID_MSAL,
                msalAccountId
            ) // IAccount.getId()
            accountManager.setUserData(amAccount, KEY_USERNAME, username) // IAccount.getUsername()
            accountManager.setUserData(amAccount, KEY_TENANT_ID, tenantId) // IAccount.getTenantId()

            authResult.scope?.joinToString(" ")?.let {
                accountManager.setUserData(amAccount, KEY_SCOPES, it)
            }
            authResult.expiresOn?.time?.let {
                accountManager.setUserData(amAccount, KEY_EXPIRES_ON_TIMESTAMP, it.toString())
            }
            displayName?.let { accountManager.setUserData(amAccount, KEY_DISPLAY_NAME, it) }

            Log.i(TAG, "MSAL account info saved successfully for: $accountManagerName")
            return@withContext true
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Error saving MSAL account info to AccountManager for $accountManagerName",
                e
            )
            if (accountAdded) { // Rollback if it was a new add
                Log.w(
                    TAG,
                    "Removing partially added MSAL account $accountManagerName due to save failure."
                )
                accountManager.removeAccount(amAccount, null, null, null)
            }
            return@withContext false
        }
    }

    suspend fun getPersistedAccountData(accountManagerName: String): PersistedMicrosoftAccount? =
        withContext(ioDispatcher) {
            val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            val amAccount =
                amAccounts.find { it.name == accountManagerName } ?: return@withContext null

            // These are the core identifiers MSAL needs
            val msalAccountId = accountManager.getUserData(amAccount, KEY_ACCOUNT_ID_MSAL)
                ?: return@withContext null
            val username = accountManager.getUserData(amAccount, KEY_USERNAME)
            // tenantId might be optional for some MSAL operations but good to have
            val tenantId = accountManager.getUserData(amAccount, KEY_TENANT_ID)
            val displayName = accountManager.getUserData(amAccount, KEY_DISPLAY_NAME)

            return@withContext PersistedMicrosoftAccount(
                accountManagerName = amAccount.name,
                msalAccountId = msalAccountId,
                username = username,
                displayName = displayName,
                tenantId = tenantId
            )
        }

    // Method to retrieve stored (encrypted) access token for a given account.
    // This might be useful if Ktor provider needs it directly, though typically MSAL handles providing tokens.
    suspend fun getAccessToken(accountManagerName: String): String? = withContext(ioDispatcher) {
        val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
        val amAccount = amAccounts.find { it.name == accountManagerName } ?: return@withContext null

        val encryptedToken =
            accountManager.getUserData(amAccount, KEY_ACCESS_TOKEN) ?: return@withContext null
        return@withContext secureEncryptionService.decrypt(encryptedToken)
    }


    suspend fun getAllPersistedMicrosoftAccounts(): List<PersistedMicrosoftAccount> =
        withContext(ioDispatcher) {
            val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            return@withContext amAccounts.mapNotNull { amAccount ->
                val msalAccountId = accountManager.getUserData(amAccount, KEY_ACCOUNT_ID_MSAL)
                if (msalAccountId.isNullOrBlank()) { // If essential msalAccountId is missing, skip.
                    Log.w(TAG, "Skipping account ${amAccount.name} due to missing MSAL account ID.")
                    null
                } else {
                    PersistedMicrosoftAccount(
                        accountManagerName = amAccount.name,
                        msalAccountId = msalAccountId,
                        username = accountManager.getUserData(amAccount, KEY_USERNAME),
                        displayName = accountManager.getUserData(amAccount, KEY_DISPLAY_NAME),
                        tenantId = accountManager.getUserData(amAccount, KEY_TENANT_ID)
                    )
                }
            }
        }

    suspend fun deleteAccount(accountManagerName: String): Boolean = withContext(ioDispatcher) {
        val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
        val amAccount = amAccounts.find { it.name == accountManagerName } ?: run {
            Log.w(
                TAG,
                "Microsoft account not found in AccountManager for deletion: $accountManagerName"
            )
            return@withContext false
        }

        // Use removeAccountExplicitly as we added it with addAccountExplicitly
        val removedSuccessfully = accountManager.removeAccountExplicitly(amAccount)

        if (removedSuccessfully) {
            Log.i(TAG, "Microsoft account removed from AccountManager: $accountManagerName")
            return@withContext true
        } else {
            Log.e(
                TAG,
                "Failed to remove Microsoft account from AccountManager: $accountManagerName"
            )
            return@withContext false
        }
    }
} 