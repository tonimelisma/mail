// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt
package net.melisma.backend_microsoft.auth

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.security.SecureEncryptionService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

// Constants moved from MicrosoftTokenPersistenceService
private const val ACCOUNT_TYPE_MICROSOFT = "net.melisma.mail.MICROSOFT"
private const val KEY_ACCESS_TOKEN = "msAccessToken"
private const val KEY_ID_TOKEN = "msIdToken"
private const val KEY_ACCOUNT_ID_MSAL = "msalAccountId"
private const val KEY_USERNAME = "msUsername"
private const val KEY_TENANT_ID = "msTenantId"
private const val KEY_SCOPES = "msScopes"
private const val KEY_EXPIRES_ON_TIMESTAMP = "msExpiresOnTimestamp"
private const val KEY_DISPLAY_NAME = "msDisplayName"

// Data class moved from MicrosoftTokenPersistenceService (or defined here if not accessible)
// Assuming it's better to have it here or as a top-level class in this package.
// For simplicity, placing it here for now. If it needs to be public for MicrosoftAccountRepository,
// it should be a top-level public class in this package.
data class PersistedMicrosoftAccount(
    val accountManagerName: String, // This will be IAccount.getId()
    val msalAccountId: String, // IAccount.getId() - can be derived from accountManagerName
    val username: String?, // Email/UPN
    val displayName: String?,
    val tenantId: String?
)

// Sealed Result Classes
sealed class AddAccountResult {
    data class Success(val account: IAccount) : AddAccountResult()
    data class Error(val exception: MsalException) : AddAccountResult()
    object Cancelled : AddAccountResult()
    object NotInitialized : AddAccountResult()
}

sealed class RemoveAccountResult {
    object Success : RemoveAccountResult()
    data class Error(val exception: MsalException) : RemoveAccountResult()
    object NotInitialized : RemoveAccountResult()
    object AccountNotFound : RemoveAccountResult()
}

sealed class AcquireTokenResult {
    data class Success(val result: IAuthenticationResult) : AcquireTokenResult()
    data class Error(val exception: MsalException) : AcquireTokenResult()
    object Cancelled : AcquireTokenResult()
    object NotInitialized : AcquireTokenResult()
    object NoAccountProvided : AcquireTokenResult()
    object UiRequired : AcquireTokenResult()
}

// Listener Interface
interface AuthStateListener {
    fun onAuthStateChanged(
        isInitialized: Boolean,
        accounts: List<IAccount>,
        error: MsalException?
    )
}

sealed class AuthenticationResultWrapper {
    data class Success(val account: IAccount, val authenticationResult: IAuthenticationResult) :
        AuthenticationResultWrapper()

    data class Error(val exception: MsalException, val isUiRequired: Boolean = false) :
        AuthenticationResultWrapper()

    object Cancelled : AuthenticationResultWrapper()
}

sealed class SignOutResultWrapper {
    object Success : SignOutResultWrapper()
    data class Error(val exception: MsalException) : SignOutResultWrapper()
}

@Singleton
class MicrosoftAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authConfigProvider: AuthConfigProvider,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val secureEncryptionService: SecureEncryptionService,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "MicrosoftAuthManager"
    private val PERSISTENCE_TAG = "MsAuthManagerPersist"
    private var mMultipleAccountApp: IMultipleAccountPublicClientApplication? = null
    private val _isMsalInitialized = MutableStateFlow(false)
    val isMsalInitialized: StateFlow<Boolean> = _isMsalInitialized.asStateFlow()
    private var initializationException: MsalException? = null

    // Initialize AccountManager
    private val accountManager: AccountManager by lazy { AccountManager.get(context) }

    companion object {
        val MICROSOFT_SCOPES = listOf("User.Read", "Mail.Read", "offline_access")
    }

    init {
        Timber.tag(TAG).d("MicrosoftAuthManager created. Initialization will be triggered.")
        initializeMsalClient() 
    }

    private fun initializeMsalClient() {
        externalScope.launch {
            try {
                Timber.tag(TAG).d("Attempting to create IMultipleAccountPublicClientApplication.")
                mMultipleAccountApp = suspendCancellableCoroutine { continuation ->
                    PublicClientApplication.createMultipleAccountPublicClientApplication(
                        context.applicationContext,
                        authConfigProvider.getMsalConfigResId(),
                        object :
                            IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                            override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                                Timber.tag(TAG)
                                    .i("MSAL IMultipleAccountPublicClientApplication created successfully.")
                                initializationException = null
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.success(application))
                                }
                            }

                            override fun onError(exception: MsalException) {
                                Timber.tag(TAG).e(
                                    exception,
                                    "MSAL IMultipleAccountPublicClientApplication creation failed."
                                )
                                initializationException = exception
                                if (continuation.isActive) {
                                    continuation.resumeWithException(exception)
                                }
                            }
                        }
                    )
                    continuation.invokeOnCancellation {
                        Timber.tag(TAG)
                            .w("MSAL initialization coroutine cancelled during PublicClientApplication.create.")
                    }
                }
                _isMsalInitialized.value = true
                Timber.tag(TAG)
                    .i("MSAL client initialized successfully after coroutine completion. mMultipleAccountApp is set.")
            } catch (e: MsalException) {
                Timber.tag(TAG).e(e, "MSAL initialization coroutine failed with MsalException.")
                initializationException = e
                mMultipleAccountApp = null
                _isMsalInitialized.value = false
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "MSAL initialization coroutine failed with generic Exception.")
                initializationException = MsalClientException(
                    MsalClientException.UNKNOWN_ERROR,
                    "Generic exception during MSAL init: ${e.message}",
                    e
                )
                mMultipleAccountApp = null
                _isMsalInitialized.value = false
            }
        }
    }

    fun getInitializationError(): MsalException? = initializationException

    fun signInInteractive(
        activity: Activity,
        scopes: List<String>
    ): Flow<AuthenticationResultWrapper> = callbackFlow {
        Timber.tag(TAG)
            .d("signInInteractive: called for activity: ${activity.localClassName}, scopes: $scopes")
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("signInInteractive: MSAL client not initialized or app is null.")
            trySend(
                AuthenticationResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.INVALID_PARAMETER,
                        "MSAL client not initialized."
                    )
                )
            )
            close()
            return@callbackFlow
        }

        val acquireTokenParameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Timber.tag(TAG).i(
                        "signInInteractive: Success. Account: ${authenticationResult.account.username}, ID: ${
                            authenticationResult.account.id?.take(5)
                        }..."
                    )
                    // Persist account info to AccountManager
                    externalScope.launch {
                        val saved = saveAccountInfoToAccountManager(authenticationResult)
                        if (saved) {
                            Timber.tag(PERSISTENCE_TAG)
                                .i("Account info saved to AccountManager for ${authenticationResult.account.username}")
                        } else {
                            Timber.tag(PERSISTENCE_TAG)
                                .e("Failed to save account info to AccountManager for ${authenticationResult.account.username}")
                        }
                    }

                    trySend(
                        AuthenticationResultWrapper.Success(
                            authenticationResult.account,
                            authenticationResult
                        )
                    )
                    close()
                }

                override fun onError(exception: MsalException) {
                    Timber.tag(TAG).e(exception, "signInInteractive: Error.")
                    trySend(
                        AuthenticationResultWrapper.Error(
                            exception,
                            exception is MsalUiRequiredException
                        )
                    )
                    close()
                }

                override fun onCancel() {
                    Timber.tag(TAG).i("signInInteractive: Cancelled by user.")
                    trySend(AuthenticationResultWrapper.Cancelled)
                    close()
                }
            })
            .build()

        try {
            Timber.tag(TAG).d("signInInteractive: Calling MSAL currentApp.acquireToken().")
            currentApp.acquireToken(acquireTokenParameters)
        } catch (e: MsalException) {
            Timber.tag(TAG).e(
                e,
                "signInInteractive: MsalException during acquireToken call itself (rare for interactive)."
            )
            trySend(AuthenticationResultWrapper.Error(e, e is MsalUiRequiredException))
            close()
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e,
                "signInInteractive: Generic Exception during acquireToken call itself (rare for interactive)."
            )
            trySend(
                AuthenticationResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "Generic exception: ${e.message}",
                        e
                    )
                )
            )
            close()
        }

        awaitClose {
            Timber.tag(TAG).d("signInInteractive callbackFlow closed for scopes: $scopes")
        }
    }

    fun acquireTokenSilent(
        account: IAccount,
        scopes: List<String>
    ): Flow<AuthenticationResultWrapper> = callbackFlow {
        Timber.tag(TAG)
            .d("acquireTokenSilent: called for account: ${account.username}, scopes: $scopes")
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("acquireTokenSilent: MSAL client not initialized or app is null.")
            trySend(
                AuthenticationResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.INVALID_PARAMETER,
                        "MSAL client not initialized."
                    )
                )
            )
            close()
            return@callbackFlow
        }

        val authority = currentApp.configuration.defaultAuthority.authorityURL.toString()
        Timber.tag(TAG)
            .d("acquireTokenSilent: Using authority: $authority for account ${account.username}")

        val silentTokenParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(authority) // It is important to provide authority for silent calls
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Timber.tag(TAG)
                        .i("acquireTokenSilent: Success. Account: ${authenticationResult.account.username}, Token Expires: ${authenticationResult.expiresOn}")
                    trySend(
                        AuthenticationResultWrapper.Success(
                            authenticationResult.account,
                            authenticationResult
                        )
                    )
                    close()
                }

                override fun onError(exception: MsalException) {
                    Timber.tag(TAG).e(
                        exception,
                        "acquireTokenSilent: Error for account ${account.username}. ErrorCode: ${exception.errorCode}"
                    )
                    trySend(
                        AuthenticationResultWrapper.Error(
                            exception,
                            exception is MsalUiRequiredException
                        )
                    )
                    close()
                }
            })
            .build()

        try {
            Timber.tag(TAG)
                .d("acquireTokenSilent: Calling MSAL currentApp.acquireTokenSilentAsync().")
            currentApp.acquireTokenSilentAsync(silentTokenParameters)
        } catch (e: MsalException) {
            Timber.tag(TAG).e(
                e,
                "acquireTokenSilent: MsalException during acquireTokenSilentAsync call itself for account ${account.username}."
            )
            trySend(AuthenticationResultWrapper.Error(e, e is MsalUiRequiredException))
            close()
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e,
                "acquireTokenSilent: Generic Exception during acquireTokenSilentAsync call itself for account ${account.username}."
            )
            trySend(
                AuthenticationResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "Generic exception: ${e.message}",
                        e
                    )
                )
            )
            close()
        }
        awaitClose {
            Timber.tag(TAG)
                .d("acquireTokenSilent callbackFlow closed for account: ${account.username}, scopes: $scopes")
        }
    }

    fun getAccounts(): Flow<List<IAccount>> = callbackFlow {
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("getAccounts: MSAL not initialized or app instance is null.")
            trySend(emptyList()) 
            close()
            return@callbackFlow
        }

        Timber.tag(TAG)
            .d("getAccounts: Calling MSAL getAccounts (via IPublicClientApplication.LoadAccountsCallback).")
        currentApp.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(result: List<IAccount>?) {
                val accounts = result ?: emptyList()
                Timber.tag(TAG).i("getAccounts: Success, loaded ${accounts.size} accounts.")
                trySend(accounts)
                close()
            }

            override fun onError(exception: MsalException) {
                Timber.tag(TAG).e(exception, "getAccounts: Error loading accounts.")
                trySend(emptyList()) // Or handle error differently, e.g., emit exception
                close()
            }
        })
        awaitClose { Timber.tag(TAG).d("getAccounts callbackFlow closed.") }
    }

    fun getAccount(accountId: String): Flow<IAccount?> = callbackFlow {
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG)
                .w("getAccount for $accountId: MSAL not initialized or app instance is null.")
            trySend(null)
            close()
            return@callbackFlow
        }

        Timber.tag(TAG)
            .d("getAccount: Calling getAccount for ID (obfuscated): ${accountId.take(5)}...")
        currentApp.getAccount(
            accountId,
            object : IMultipleAccountPublicClientApplication.GetAccountCallback {
            override fun onTaskCompleted(result: IAccount?) {
                if (result != null) {
                    Timber.tag(TAG).i("getAccount: Success, found account ${result.username}")
                } else {
                    Timber.tag(TAG)
                        .i("getAccount: Account with ID ${accountId.take(5)}... not found.")
                }
                trySend(result)
                close()
            }

            override fun onError(exception: MsalException) {
                Timber.tag(TAG).e(
                    exception,
                    "getAccount: Error for ID ${accountId.take(5)}... (${exception.errorCode})"
                )
                trySend(null) // Send null on error as per Flow<IAccount?>
                close()
            }
        })
        awaitClose {
            Timber.tag(TAG).d("getAccount callbackFlow closed for ID ${accountId.take(5)}...")
        }
    }

    fun getAccountById(accountId: String): Flow<IAccount?> = flow {
        Timber.tag(TAG)
            .d("getAccountById: Attempting to get account for ID (synchronous MSAL call): $accountId")
        val account = mMultipleAccountApp?.getAccount(accountId)
        emit(account)
    }.flowOn(Dispatchers.IO)

    fun signOut(account: IAccount): Flow<SignOutResultWrapper> = callbackFlow {
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG)
                .w("signOut for ${account.username}: MSAL not initialized or app instance is null.")
            trySend(
                SignOutResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.INVALID_PARAMETER,
                        "MSAL client not initialized or instance is null."
                    )
                )
            )
            close()
            return@callbackFlow
        }

        Timber.tag(TAG)
            .d("signOut: Calling removeAccount for ${account.username} (ID: ${account.id}).")
        currentApp.removeAccount(
            account,
            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() {
                    Timber.tag(TAG)
                        .i("signOut: Success, account ${account.username} removed from MSAL.")

                    // Remove account from AccountManager
                    externalScope.launch {
                        account.id?.let { accountId ->
                            val deleted = deleteAccountFromAccountManager(accountId)
                            if (deleted) {
                                Timber.tag(PERSISTENCE_TAG)
                                    .i("Account info deleted from AccountManager for ${account.username}")
                            } else {
                                Timber.tag(PERSISTENCE_TAG)
                                    .w("Failed to delete account info from AccountManager for ${account.username}")
                            }
                        } ?: Timber.tag(PERSISTENCE_TAG)
                            .w("Account ID was null, cannot delete from AccountManager for ${account.username}")
                    }
                    trySend(SignOutResultWrapper.Success)
                    close()
                }

                override fun onError(exception: MsalException) {
                    Timber.tag(TAG).e(
                        exception,
                        "signOut: Error removing account ${account.username} from MSAL."
                    )
                    trySend(SignOutResultWrapper.Error(exception))
                    close()
                }
            })
        awaitClose {
            Timber.tag(TAG).d("signOut callbackFlow closed for account ${account.username}")
        }
    }

    // --- Start of methods moved/adapted from MicrosoftTokenPersistenceService ---

    private suspend fun saveAccountInfoToAccountManager(
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

        Timber.tag(PERSISTENCE_TAG)
            .d("saveAccountInfoToAccountManager called for MSAL Account ID: ${msalAccountId?.take(10)}..., Username: $username")

        if (accountManagerName.isNullOrBlank()) { // Check for null or blank
            Timber.tag(PERSISTENCE_TAG)
                .e("Cannot save account: MSAL Account ID (IAccount.getId()) is blank.")
            return@withContext false
        }

        val amAccount = android.accounts.Account(accountManagerName, ACCOUNT_TYPE_MICROSOFT)
        val accountAdded = accountManager.addAccountExplicitly(amAccount, null, null)

        if (accountAdded) {
            Timber.tag(PERSISTENCE_TAG)
                .i("New Microsoft account added to AccountManager: $accountManagerName")
        } else {
            Timber.tag(PERSISTENCE_TAG)
                .d("Microsoft account $accountManagerName already exists, updating data.")
        }

        try {
            // Encrypt and store Access Token
            authResult.accessToken?.let {
                secureEncryptionService.encrypt(it)?.let { encrypted ->
                    accountManager.setUserData(amAccount, KEY_ACCESS_TOKEN, encrypted)
                    Timber.tag(PERSISTENCE_TAG).d("Access token stored for $accountManagerName.")
                } ?: Timber.tag(PERSISTENCE_TAG)
                    .e("Failed to encrypt access token for $accountManagerName.")
            }

            // Encrypt and store ID Token (if available from IAccount)
            account.idToken?.let {
                secureEncryptionService.encrypt(it)?.let { encrypted ->
                    accountManager.setUserData(amAccount, KEY_ID_TOKEN, encrypted)
                    Timber.tag(PERSISTENCE_TAG).d("ID token stored for $accountManagerName.")
                } ?: Timber.tag(PERSISTENCE_TAG)
                    .e("Failed to encrypt ID token for $accountManagerName.")
            }

            // Store non-sensitive IAccount identifiers needed by MSAL to find the account
            accountManager.setUserData(
                amAccount,
                KEY_ACCOUNT_ID_MSAL,
                msalAccountId
            )
            accountManager.setUserData(amAccount, KEY_USERNAME, username)
            accountManager.setUserData(amAccount, KEY_TENANT_ID, tenantId)

            authResult.scope?.joinToString(" ")?.let {
                accountManager.setUserData(amAccount, KEY_SCOPES, it)
            }
            authResult.expiresOn?.time?.let {
                accountManager.setUserData(amAccount, KEY_EXPIRES_ON_TIMESTAMP, it.toString())
            }
            displayName?.let { accountManager.setUserData(amAccount, KEY_DISPLAY_NAME, it) }

            Timber.tag(PERSISTENCE_TAG)
                .i("MSAL account info saved successfully to AccountManager for: $accountManagerName")
            return@withContext true
        } catch (e: Exception) {
            Timber.tag(PERSISTENCE_TAG).e(
                e,
                "Error saving MSAL account info to AccountManager for $accountManagerName"
            )
            if (accountAdded) { // Rollback if it was a new add
                Timber.tag(PERSISTENCE_TAG).w(
                    "Removing partially added MSAL account $accountManagerName from AccountManager due to save failure."
                )
                // accountManager.removeAccount(amAccount, null, null, null) -> This is deprecated
                // Use removeAccountExplicitly for consistency if available, or the modern equivalent.
                // For now, using the deprecated one as per original code context if that was it.
                // However, if addAccountExplicitly was used, removeAccountExplicitly is better.
                accountManager.removeAccountExplicitly(amAccount) // Use this as we used addAccountExplicitly
            }
            return@withContext false
        }
    }

    private suspend fun deleteAccountFromAccountManager(accountManagerName: String): Boolean =
        withContext(ioDispatcher) {
            val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            val amAccount = amAccounts.find { it.name == accountManagerName } ?: run {
                Timber.tag(PERSISTENCE_TAG).w(
                    "Microsoft account not found in AccountManager for deletion: $accountManagerName"
                )
                return@withContext false
            }

            val removedSuccessfully = accountManager.removeAccountExplicitly(amAccount)

            if (removedSuccessfully) {
                Timber.tag(PERSISTENCE_TAG)
                    .i("Microsoft account removed from AccountManager: $accountManagerName")
                return@withContext true
            } else {
                Timber.tag(PERSISTENCE_TAG).e(
                    "Failed to remove Microsoft account from AccountManager: $accountManagerName"
                )
                return@withContext false
            }
        }

    suspend fun getPersistedAccountData(accountManagerName: String): PersistedMicrosoftAccount? =
        withContext(ioDispatcher) {
            val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            val amAccount =
                amAccounts.find { it.name == accountManagerName } ?: run {
                    Timber.tag(PERSISTENCE_TAG)
                        .w("AccountManager: No account found for name $accountManagerName during getPersistedAccountData")
                    return@withContext null
                }


            val msalAccountId = accountManager.getUserData(amAccount, KEY_ACCOUNT_ID_MSAL)
                ?: run {
                    Timber.tag(PERSISTENCE_TAG)
                        .w("AccountManager: msalAccountId is null for ${amAccount.name}, cannot construct PersistedMicrosoftAccount.")
                    return@withContext null
                }
            val username = accountManager.getUserData(amAccount, KEY_USERNAME)
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

    suspend fun getAccessTokenFromAccountManager(accountManagerName: String): String? =
        withContext(ioDispatcher) {
            val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            val amAccount =
                amAccounts.find { it.name == accountManagerName } ?: return@withContext null

            val encryptedToken =
                accountManager.getUserData(amAccount, KEY_ACCESS_TOKEN) ?: return@withContext null
            return@withContext secureEncryptionService.decrypt(encryptedToken)
        }

    suspend fun getAllPersistedMicrosoftAccounts(): List<PersistedMicrosoftAccount> =
        withContext(ioDispatcher) {
            val amAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE_MICROSOFT)
            return@withContext amAccounts.mapNotNull { amAccount ->
                val msalAccountId = accountManager.getUserData(amAccount, KEY_ACCOUNT_ID_MSAL)
                if (msalAccountId.isNullOrBlank()) {
                    Timber.tag(PERSISTENCE_TAG)
                        .w("Skipping account ${amAccount.name} due to missing or blank MSAL account ID.")
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

    // --- End of methods moved/adapted from MicrosoftTokenPersistenceService ---
}
