// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt
package net.melisma.backend_microsoft.auth

// UPDATED IMPORT for AuthConfigProvider to point to core-data
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.RawRes
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import net.melisma.core_data.di.AuthConfigProvider


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

/**
 * Manages interactions with MSAL. Receives its config resource ID via AuthConfigProvider.
 *
 * @param context Application context.
 * @param authConfigProvider Provider instance that supplies the MSAL config resource ID.
 */
class MicrosoftAuthManager(
    private val context: Context, // Made private as it's only used internally
    authConfigProvider: AuthConfigProvider, // Type is now imported from core_data
    private val tokenPersistenceService: MicrosoftTokenPersistenceService, // Added
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default) // Added for launching persistence tasks
) {
    private val TAG = "MicrosoftAuthManager"

    @RawRes
    private val configResId: Int = authConfigProvider.getMsalConfigResId()

    private var msalInstance: IMultipleAccountPublicClientApplication? = null
    private var authStateListener: AuthStateListener? = null

    var isInitialized: Boolean = false
        private set(value) {
            field = value
            notifyListener()
        }
    var initializationError: MsalException? = null
        private set(value) {
            field = value
            notifyListener()
        }

    private var _accounts: List<IAccount> = emptyList()
    val accounts: List<IAccount>
        get() = _accounts.toList() // Return a copy for immutability

    init {
        Log.d(TAG, "MicrosoftAuthManager: Initializing with config resource ID: $configResId")
        initializeMsal()
    }

    fun setAuthStateListener(listener: AuthStateListener?) {
        Log.d(
            TAG,
            "MicrosoftAuthManager: setAuthStateListener called, listener is ${listener != null}"
        )
        this.authStateListener = listener
        if (listener != null) { // Notify immediately if listener is set after initialization
            Log.d(TAG, "MicrosoftAuthManager: Notifying listener immediately after setting")
            notifyListener()
        }
    }

    private fun notifyListener() {
        try {
            Log.d(TAG, "MicrosoftAuthManager: Notifying auth state listener")
            authStateListener?.onAuthStateChanged(isInitialized, accounts, initializationError)
            Log.d(
                TAG,
                "MicrosoftAuthManager: Notified Listener: isInitialized=$isInitialized, accountCount=${accounts.size}, errorPresent=${initializationError != null}"
            )
        } catch (e: Exception) {
            // Catching all exceptions from listener to prevent crashing the auth manager
            Log.e(TAG, "MicrosoftAuthManager: Error occurred within authStateListener callback", e)
        }
    }

    private fun updateAccountList(newAccounts: List<IAccount>) {
        Log.d(TAG, "MicrosoftAuthManager: Updating account list with ${newAccounts.size} accounts")
        // Ensure thread-safety if accounts can be modified from multiple threads,
        // though MSAL callbacks are typically on main thread.
        // For simplicity, direct assignment is used here.
        _accounts = newAccounts.toList() // Store a copy
        Log.d(TAG, "MicrosoftAuthManager: Account list updated. New count: ${accounts.size}")

        if (newAccounts.isNotEmpty()) {
            Log.d(
                TAG,
                "MicrosoftAuthManager: Accounts present: ${newAccounts.joinToString { it.username ?: "Unknown" }}"
            )
        }

        Log.d(TAG, "MicrosoftAuthManager: Notifying listeners about account list update")
        notifyListener() // Notify after updating the internal list
    }

    private fun initializeMsal() {
        Log.d(TAG, "MicrosoftAuthManager: Initializing MSAL with config resource ID: $configResId")
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            context.applicationContext,
            configResId,
            object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                    Log.i(
                        TAG,
                        "MicrosoftAuthManager: MSAL instance (Multi-Account) created successfully."
                    )
                    msalInstance = application
                    initializationError = null
                    isInitialized = true
                    Log.d(
                        TAG,
                        "MicrosoftAuthManager: MSAL initialization state set to: isInitialized=true, error=null"
                    )
                    Log.d(
                        TAG,
                        "MicrosoftAuthManager: Loading accounts from MSAL cache and persistence"
                    )
                    loadAccountsAsync()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "MicrosoftAuthManager: MSAL Initialization Error", exception)
                    Log.e(TAG, "MicrosoftAuthManager: Error details: ${exception.message}")
                    msalInstance = null
                    initializationError = exception
                    isInitialized = false
                    Log.d(
                        TAG,
                        "MicrosoftAuthManager: MSAL initialization state set to: isInitialized=false, error=${exception.errorCode}"
                    )
                    Log.d(
                        TAG,
                        "MicrosoftAuthManager: Clearing accounts list due to initialization error"
                    )
                    updateAccountList(emptyList()) // Ensure accounts list is cleared on init error
                }
            })
    }

    private fun loadAccountsAsync() {
        Log.d(TAG, "MicrosoftAuthManager: loadAccountsAsync called")
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.w(
                TAG,
                "MicrosoftAuthManager: loadAccountsAsync called but MSAL not ready or instance is null."
            )
            Log.d(
                TAG,
                "MicrosoftAuthManager: isInitialized=$isInitialized, msalInstance=${msalInstance != null}"
            )
            Log.d(
                TAG,
                "MicrosoftAuthManager: Updating account list to empty list to ensure consistent state"
            )
            updateAccountList(emptyList()) // Ensure consistent state
            return
        }

        // First, load accounts known to MSAL
        Log.d(
            TAG,
            "MicrosoftAuthManager: Calling MSAL getAccounts to load accounts from MSAL cache"
        )
        currentMsalInstance.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(msalAccounts: List<IAccount>?) {
                val freshMsalAccounts = msalAccounts ?: emptyList()
                Log.d(
                    TAG,
                    "MicrosoftAuthManager: Loaded ${freshMsalAccounts.size} accounts from MSAL cache."
                )

                coroutineScope.launch {
                    // Then, load accounts from our AccountManager persistence
                    val persistedAmAccounts =
                        tokenPersistenceService.getAllPersistedMicrosoftAccounts()
                    Log.d(
                        TAG,
                        "MicrosoftAuthManager: Loaded ${persistedAmAccounts.size} accounts from TokenPersistenceService."
                    )

                    val combinedAccounts = mutableListOf<IAccount>()
                    val msalAccountMap = freshMsalAccounts.associateBy { it.id }
                    combinedAccounts.addAll(freshMsalAccounts)

                    persistedAmAccounts.forEach { persisted ->
                        if (!msalAccountMap.containsKey(persisted.msalAccountId)) {
                            // This account is in our persistence but not in MSAL's current list.
                            // This might happen if MSAL cache was cleared but our AccountManager entry remains.
                            // We can try to get an IAccount object for it.
                            // For simplicity here, we are not re-creating IAccount objects from persisted data alone
                            // as MSAL's IAccount objects are live and tied to its cache.
                            // Instead, we rely on MSAL to provide the IAccount objects.
                            // If an account is ONLY in persistence, it implies user needs to effectively sign in again
                            // if MSAL cannot find it.
                            // However, having it in persistence helps to remember the user *had* an account.
                            Log.w(
                                TAG,
                                "Account ${persisted.username} (ID: ${persisted.msalAccountId}) found in persistence but not in active MSAL cache. It might require re-authentication via interactive flow if needed."
                            )
                        }
                    }
                    // The primary source of truth for active IAccount objects is MSAL.
                    // The persistence layer helps remember accounts across app sessions/reinstalls
                    // and informs MSAL which accounts to try for silent auth.
                    updateAccountList(freshMsalAccounts)
                }
            }

            override fun onError(exception: MsalException) {
                Log.e(
                    TAG,
                    "MicrosoftAuthManager: Error loading accounts from MSAL cache",
                    exception
                )
                Log.e(TAG, "MicrosoftAuthManager: Error details: ${exception.message}")
                updateAccountList(emptyList()) // Clear accounts on error
            }
        })
    }

    fun addAccount(
        activity: Activity,
        scopes: List<String>
    ): Flow<AddAccountResult> = callbackFlow {
        Log.d(TAG, "MicrosoftAuthManager: addAccount called with scopes: ${scopes.joinToString()}")
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.e(
                TAG,
                "MicrosoftAuthManager: addAccount failed - MSAL not initialized or instance is null"
            )
            Log.d(
                TAG,
                "MicrosoftAuthManager: isInitialized=$isInitialized, msalInstance=${msalInstance != null}"
            )
            trySend(AddAccountResult.NotInitialized)
            close()
            return@callbackFlow
        }

        Log.d(
            TAG,
            "MicrosoftAuthManager: Setting up authentication callback for interactive sign-in"
        )
        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.i(
                    TAG,
                    "MicrosoftAuthManager: Interactive addAccount/signIn successful for ${authenticationResult.account.username}"
                )
                Log.d(
                    TAG,
                    "MicrosoftAuthManager: Auth result contains account ID: ${authenticationResult.account.id}"
                )
                // Persist the account info
                coroutineScope.launch {
                    val saved = tokenPersistenceService.saveAccountInfo(authenticationResult)
                    if (saved) {
                        Log.i(
                            TAG,
                            "Successfully saved account ${authenticationResult.account.username} to AccountManager."
                        )
                    } else {
                        Log.e(
                            TAG,
                            "Failed to save account ${authenticationResult.account.username} to AccountManager."
                        )
                    }
                }
                Log.d(TAG, "MicrosoftAuthManager: Refreshing account list after successful sign-in")
                loadAccountsAsync() // Refresh account list (MSAL + Persistence)
                Log.d(
                    TAG,
                    "MicrosoftAuthManager: Sending success result with account: ${authenticationResult.account.username}"
                )
                trySend(AddAccountResult.Success(authenticationResult.account))
                close()
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "MicrosoftAuthManager: Interactive addAccount/signIn error", exception)
                Log.e(
                    TAG,
                    "MicrosoftAuthManager: Error details: ${exception.message}, Error code: ${exception.errorCode}"
                )
                trySend(AddAccountResult.Error(exception))
                close()
            }

            override fun onCancel() {
                Log.w(TAG, "MicrosoftAuthManager: Interactive addAccount/signIn cancelled by user")
                trySend(AddAccountResult.Cancelled)
                close()
            }
        }

        Log.d(TAG, "MicrosoftAuthManager: Building AcquireTokenParameters for interactive sign-in")
        val interactiveParameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)
            .build()

        Log.d(TAG, "MicrosoftAuthManager: Initiating interactive sign-in with MSAL")
        currentMsalInstance.acquireToken(interactiveParameters)

        awaitClose {
            Log.d(
                TAG,
                "MicrosoftAuthManager: addAccount Flow for scopes [${scopes.joinToString()}] cancelled or completed"
            )
        }
    }

    fun removeAccount(
        accountToRemove: IAccount? // Renamed for clarity
    ): Flow<RemoveAccountResult> = callbackFlow {
        Log.d(
            TAG,
            "MicrosoftAuthManager: removeAccount called with account: ${accountToRemove?.username}"
        )
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.e(
                TAG,
                "MicrosoftAuthManager: removeAccount failed - MSAL not initialized or instance is null"
            )
            Log.d(
                TAG,
                "MicrosoftAuthManager: isInitialized=$isInitialized, msalInstance=${msalInstance != null}"
            )
            trySend(RemoveAccountResult.NotInitialized)
            close()
            return@callbackFlow
        }

        if (accountToRemove == null) {
            Log.e(TAG, "MicrosoftAuthManager: removeAccount failed - Account to remove is null")
            trySend(RemoveAccountResult.AccountNotFound)
            close()
            return@callbackFlow
        }

        Log.d(
            TAG,
            "MicrosoftAuthManager: Attempting to remove account ID: ${accountToRemove.id}, username: ${accountToRemove.username}"
        )
        currentMsalInstance.removeAccount(
            accountToRemove,
            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() {
                    Log.i(
                        TAG,
                        "MicrosoftAuthManager: Account removal successful from MSAL for ${accountToRemove.username}"
                    )
                    // Remove from our persistence as well
                    coroutineScope.launch {
                        val deleted = tokenPersistenceService.deleteAccount(accountToRemove.id)
                        if (deleted) {
                            Log.i(
                                TAG,
                                "Successfully deleted account ${accountToRemove.username} (ID: ${accountToRemove.id}) from AccountManager."
                            )
                        } else {
                            Log.w(
                                TAG,
                                "Failed to delete account ${accountToRemove.username} (ID: ${accountToRemove.id}) from AccountManager or not found."
                            )
                        }
                    }
                    Log.d(TAG, "MicrosoftAuthManager: Refreshing account list after removal")
                    loadAccountsAsync() // Refresh account list
                    Log.d(TAG, "MicrosoftAuthManager: Sending success result for account removal")
                    trySend(RemoveAccountResult.Success)
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.e(
                        TAG,
                        "MicrosoftAuthManager: Account removal error for ${accountToRemove.username}",
                        exception
                    )
                    Log.e(
                        TAG,
                        "MicrosoftAuthManager: Error details: ${exception.message}, Error code: ${exception.errorCode}"
                    )
                    trySend(RemoveAccountResult.Error(exception))
                    close()
                }
            })
        awaitClose {
            Log.d(
                TAG,
                "MicrosoftAuthManager: removeAccount Flow for ${accountToRemove.username} cancelled or completed"
            )
        }
    }

    fun acquireTokenSilent(
        accountToAuth: IAccount?, // Renamed for clarity
        scopes: List<String>
    ): Flow<AcquireTokenResult> = callbackFlow {
        Log.d(
            TAG,
            "acquireTokenSilent called for account: ${accountToAuth?.username}, scopes: ${scopes.joinToString()}"
        )
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.e(TAG, "acquireTokenSilent: MSAL not initialized or instance is null")
            trySend(AcquireTokenResult.NotInitialized)
            close()
            return@callbackFlow
        }

        if (accountToAuth == null) {
            // Attempt to get account from persistence if not provided directly
            coroutineScope.launch {
                val persistedAccounts = tokenPersistenceService.getAllPersistedMicrosoftAccounts()
                if (persistedAccounts.isNotEmpty()) {
                    // Logic to select an account if multiple are persisted (e.g., active one)
                    // For now, try the first one. This logic might need refinement.
                    val firstPersisted = persistedAccounts.first()
                    Log.d(
                        TAG,
                        "No explicit account provided for silent auth, attempting with first persisted MSAL account: ${firstPersisted.username} (ID: ${firstPersisted.msalAccountId})"
                    )

                    // Need to get an IAccount object from MSAL using persisted info
                    currentMsalInstance?.getAccount(
                        firstPersisted.msalAccountId,
                        object : IMultipleAccountPublicClientApplication.GetAccountCallback {
                            override fun onTaskCompleted(result: IAccount?) {
                                if (result != null) {
                                    Log.d(
                                        TAG,
                                        "Successfully retrieved IAccount '${result.username}' from MSAL using persisted ID."
                                    )
                                    // Re-dispatch to acquireTokenSilent with the found account
                                    launch { // ensure this suspend call is in a coroutine
                                        acquireTokenSilent(
                                            result,
                                            scopes
                                        ).collect { trySend(it); close() }
                                    }
                                } else {
                                    Log.w(
                                        TAG,
                                        "Could not find account in MSAL using persisted ID ${firstPersisted.msalAccountId}. UI interaction likely required."
                                    )
                                    trySend(AcquireTokenResult.UiRequired) // Or NoAccountProvided / specific error
                                    close()
                                }
                            }

                            override fun onError(exception: MsalException?) {
                                Log.e(
                                    TAG,
                                    "Error getting account from MSAL using persisted ID ${firstPersisted.msalAccountId}",
                                    exception
                                )
                                val specificError = exception ?: MsalClientException(
                                    "get_account_failed_no_details",
                                    "Failed to retrieve account from MSAL using persisted ID; no specific error details provided by MSAL."
                                )
                                val resultToSend: AcquireTokenResult =
                                    AcquireTokenResult.Error(specificError)
                                trySend(resultToSend)
                                close()
                            }
                        })
                } else {
                    Log.e(
                        TAG,
                        "acquireTokenSilent: Account is null and no persisted accounts found."
                    )
                    trySend(AcquireTokenResult.NoAccountProvided)
                    close()
                }
            }
        }

        // If accountToAuth was not null, or if the above block did not return,
        // proceed with the normal silent token acquisition for the (now hopefully non-null) accountToAuth
        val currentAccount = accountToAuth ?: run {
            // This case should ideally be covered by the block above if accountToAuth started as null.
            // If we reach here with accountToAuth as null, it means the persistence check failed to yield an account
            // and did not send a result/close the flow, which would be a logic error in the block above.
            // However, to be safe and satisfy nullability for `forAccount`:
            Log.e(
                TAG,
                "acquireTokenSilent: accountToAuth is still null after persistence check. This indicates a logic issue or no account available."
            )
            trySend(AcquireTokenResult.NoAccountProvided)
            close()
            return@callbackFlow // Return from callbackFlow if account is definitively null here.
        }

        val authority = currentAccount.authority
        if (authority.isNullOrBlank()) {
            Log.e(
                TAG,
                "acquireTokenSilent: Authority is null or blank for ${currentAccount.username}"
            )
            // It's better to inform UI is required if authority is missing, as it's a setup issue.
            trySend(AcquireTokenResult.UiRequired)
            close()
            return@callbackFlow
        }

        val silentParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(currentAccount)
            .fromAuthority(authority) // MSAL requires authority for silent calls
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(
                        TAG,
                        "Silent token acquisition successful for ${currentAccount.username}."
                    )
                    trySend(AcquireTokenResult.Success(authenticationResult))
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.w(
                        TAG,
                        "Silent token acquisition error for ${currentAccount.username}.",
                        exception
                    )
                    if (exception is MsalUiRequiredException) {
                        trySend(AcquireTokenResult.UiRequired)
                    } else {
                        trySend(AcquireTokenResult.Error(exception))
                    }
                    close()
                }
            })
            .build()

        currentMsalInstance.acquireTokenSilentAsync(silentParameters)
        awaitClose {
            Log.d(
                TAG,
                "acquireTokenSilent Flow for ${currentAccount.username} scopes [${scopes.joinToString()}] cancelled or completed"
            )
        }
    }

    fun acquireTokenInteractive(
        activity: Activity,
        accountToAuth: IAccount?, // Renamed for clarity, can be null for initial sign-in
        scopes: List<String>
    ): Flow<AcquireTokenResult> = callbackFlow {
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.e(TAG, "acquireTokenInteractive: MSAL not initialized or instance is null")
            trySend(AcquireTokenResult.NotInitialized)
            close()
            return@callbackFlow
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.i(
                    TAG,
                    "Interactive token acquisition successful for ${authenticationResult.account.username}"
                )
                loadAccountsAsync() // Refresh account list
                trySend(AcquireTokenResult.Success(authenticationResult))
                close()
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Interactive token acquisition error.", exception)
                trySend(AcquireTokenResult.Error(exception))
                close()
            }

            override fun onCancel() {
                Log.w(TAG, "Interactive token acquisition cancelled by user.")
                trySend(AcquireTokenResult.Cancelled)
                close()
            }
        }

        val builder = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)

        // Provide login hint if account is known, helps MSAL pre-fill username
        accountToAuth?.username?.takeIf { it.isNotBlank() }?.let {
            builder.withLoginHint(it)
        }

        val interactiveParameters = builder.build()
        currentMsalInstance.acquireToken(interactiveParameters)
        awaitClose {
            Log.d(
                TAG,
                "acquireTokenInteractive Flow for scopes [${scopes.joinToString()}] cancelled or completed"
            )
        }
    }

    fun getPersistedActiveAccount(): Flow<IAccount?> = callbackFlow {
        Log.d(TAG, "getPersistedActiveAccount called")
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.w(TAG, "MSAL not initialized, cannot get persisted active account.")
            trySend(null)
            close()
            return@callbackFlow
        }

        coroutineScope.launch {
            val persistedAccounts = tokenPersistenceService.getAllPersistedMicrosoftAccounts()
            if (persistedAccounts.isEmpty()) {
                Log.d(TAG, "No Microsoft accounts found in persistence.")
                trySend(null)
                close()
            } else {
                // Simple strategy: use the first persisted account.
                // TODO: Implement a strategy for multiple accounts (e.g., using ActiveMicrosoftAccountHolder)
                val firstPersisted = persistedAccounts.first()
                Log.d(
                    TAG,
                    "Attempting to use persisted MSAL account: ${firstPersisted.username} (ID: ${firstPersisted.msalAccountId})"
                )

                currentMsalInstance.getAccount(
                    firstPersisted.msalAccountId,
                    object : IMultipleAccountPublicClientApplication.GetAccountCallback {
                        override fun onTaskCompleted(result: IAccount?) {
                            if (result != null) {
                                Log.i(
                                    TAG,
                                    "Successfully retrieved IAccount '${result.username}' from MSAL using persisted ID."
                                )
                                updateAccountList(listOf(result)) // Update manager's internal list
                                trySend(result)
                            } else {
                                Log.w(
                                    TAG,
                                    "Could not find account in MSAL cache using persisted ID ${firstPersisted.msalAccountId}. It may have been removed from MSAL or require re-login."
                                )
                                // Optionally, try to remove from our persistence if MSAL doesn't know it anymore
                                // launch { tokenPersistenceService.deleteAccount(firstPersisted.msalAccountId) }
                                trySend(null)
                            }
                            close()
                        }

                        override fun onError(exception: MsalException?) {
                            Log.e(
                                TAG,
                                "Error getting account from MSAL using persisted ID ${firstPersisted.msalAccountId}",
                                exception
                            )
                            trySend(null) // This flow returns IAccount?, so null is a valid emission for error/not found
                            close()
                        }
                    })
            }
        }
        awaitClose { Log.d(TAG, "getPersistedActiveAccount flow closed") }
    }
}
