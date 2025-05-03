package net.melisma.feature_auth // Ensure this matches your module's package structure

// MSAL imports
// Corrected import for RemoveAccountCallback based on docs (it's nested)
// Corrected import for LoadAccountsCallback (it's nested in IPublicClientApplication)
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
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication.RemoveAccountCallback
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication.LoadAccountsCallback
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException


// --- Sealed Result Classes ---
// SignInResult is less relevant now, using AddAccountResult instead
sealed class AddAccountResult {
    data class Success(val account: IAccount) : AddAccountResult() // Account added/authenticated
    data class Error(val exception: MsalException) : AddAccountResult()
    object Cancelled : AddAccountResult()
    object NotInitialized : AddAccountResult()
}

// Renamed from SignOutResult
sealed class RemoveAccountResult {
    object Success : RemoveAccountResult()
    data class Error(val exception: MsalException) : RemoveAccountResult()
    object NotInitialized : RemoveAccountResult()
    object AccountNotFound : RemoveAccountResult()
}

// AcquireTokenResult remains largely the same, but NoAccount might mean "no specific account provided"
sealed class AcquireTokenResult {
    data class Success(val result: IAuthenticationResult) : AcquireTokenResult()
    data class Error(val exception: MsalException) : AcquireTokenResult()
    object Cancelled : AcquireTokenResult()
    object NotInitialized : AcquireTokenResult()
    object NoAccountProvided : AcquireTokenResult() // Renamed for clarity
    object UiRequired : AcquireTokenResult()
}

// --- Listener Interface for UI updates ---
interface AuthStateListener {
    // Changed to report list of accounts
    fun onAuthStateChanged(
        isInitialized: Boolean,
        accounts: List<IAccount>,
        error: MsalException?
    )
}

class MicrosoftAuthManager(
    val context: Context,
    @RawRes private val configResId: Int
) {
    private val TAG = "MicrosoftAuthManager"

    // Changed to IMultipleAccountPublicClientApplication
    private var msalInstance: IMultipleAccountPublicClientApplication? = null
    private var authStateListener: AuthStateListener? = null

    // --- Regular properties ---
    var isInitialized: Boolean = false
        private set(value) {
            field = value
            notifyListener() // Notify listener when initialization state changes
        }
    var initializationError: MsalException? = null
        private set(value) {
            field = value
            notifyListener() // Notify listener when initialization state changes
        }

    // Backing field for the accounts list
    private var _accounts: List<IAccount> = emptyList()
    val accounts: List<IAccount>
        get() = _accounts
    // ---

    init {
        Log.d(TAG, "Initializing...")
        initializeMsal()
    }

    fun setAuthStateListener(listener: AuthStateListener?) {
        this.authStateListener = listener
        // Immediately notify with current state upon registration
        notifyListener()
    }

    private fun notifyListener() {
        // Ensure listener calls happen on the main thread if UI updates are involved (Coroutines recommended later)
        try {
            authStateListener?.onAuthStateChanged(isInitialized, _accounts, initializationError)
            Log.d(
                TAG,
                "Notified Listener: isInitialized=$isInitialized, accountCount=${_accounts.size}, error=$initializationError"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in authStateListener", e)
        }
    }

    // Helper to update internal account list and notify
    private fun updateAccountList(newAccounts: List<IAccount>) {
        // Simple check for change; more robust check might compare account IDs/usernames if order changes
        if (_accounts != newAccounts) {
            _accounts = newAccounts
            notifyListener()
        }
    }

    private fun initializeMsal() {
        // Use createMultipleAccountPublicClientApplication
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            context.applicationContext,
            configResId,
            // Use the correct listener type based on docs
            object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                    msalInstance = application
                    initializationError = null
                    isInitialized = true // This triggers the setter and notifyListener()
                    Log.d(TAG, "MSAL instance (Multi-Account) created successfully.")
                    loadAccountsAsync() // Load initial account state
                }

                override fun onError(exception: MsalException) {
                    msalInstance = null
                    initializationError = exception // Triggers setter
                    isInitialized = false // Triggers setter
                    updateAccountList(emptyList()) // Clear accounts on init error
                    Log.e(TAG, "MSAL Init Error.", exception)
                }
            })
    }

    // Renamed from loadAccountAsync, uses getAccounts with the correct callback
    private fun loadAccountsAsync() {
        if (!isInitialized || msalInstance == null) {
            Log.w(TAG, "loadAccountsAsync called before MSAL initialized.")
            updateAccountList(emptyList()) // Ensure state reflects no accounts loaded
            return
        }
        // Use getAccounts with LoadAccountsCallback
        msalInstance?.getAccounts(object : LoadAccountsCallback {
            // Correct callback method names based on docs
            override fun onTaskCompleted(result: List<IAccount>?) {
                Log.d(TAG, "Async Accounts loaded: Count = ${result?.size ?: 0}")
                updateAccountList(result ?: emptyList())
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "MSAL Load Accounts Async Error", exception)
                updateAccountList(emptyList()) // Clear accounts on error
            }
        })
    }

    // --- Add Account (Replaces SignIn) ---
    fun addAccount(activity: Activity, scopes: List<String>, callback: (AddAccountResult) -> Unit) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "addAccount called before MSAL initialized or init failed.")
            callback(AddAccountResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.d(TAG, "addAccount success. Account: ${authenticationResult.account.username}")
                // Important: Refresh the account list after adding
                loadAccountsAsync() // Reload accounts to update internal list
                callback(AddAccountResult.Success(authenticationResult.account))
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "addAccount Error", exception)
                callback(AddAccountResult.Error(exception))
            }

            override fun onCancel() {
                Log.d(TAG, "addAccount cancelled by user.")
                callback(AddAccountResult.Cancelled)
            }
        }

        // Use acquireToken for adding accounts in multi-account mode
        val interactiveParameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            // .withLoginHint(null) // Optional
            .withCallback(authCallback)
            .build()
        msalInstance?.acquireToken(interactiveParameters)
    }

    // --- Remove Account (Replaces SignOut) ---
    // Uses the correct callback type and methods
    fun removeAccount(account: IAccount?, callback: (RemoveAccountResult) -> Unit) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "removeAccount called before MSAL initialized or init failed.")
            callback(RemoveAccountResult.NotInitialized)
            return
        }
        if (account == null) {
            Log.e(TAG, "removeAccount called with null account.")
            callback(RemoveAccountResult.AccountNotFound) // Or a different error
            return
        }

        // Use the correct RemoveAccountCallback nested interface
        msalInstance?.removeAccount(account, object : RemoveAccountCallback {
            // Correct method name based on docs
            override fun onRemoved() {
                Log.d(TAG, "removeAccount success for ${account.username}.")
                // Important: Refresh the account list after removing
                loadAccountsAsync() // Reload accounts to update internal list
                callback(RemoveAccountResult.Success)
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "removeAccount Error for ${account.username}", exception)
                callback(RemoveAccountResult.Error(exception))
            }
        })
    }

    // --- Acquire Token Silently (Now requires an IAccount) ---
    fun acquireTokenSilent(
        account: IAccount?, // Account is now required
        scopes: List<String>,
        callback: (AcquireTokenResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.w(TAG, "acquireTokenSilent: MSAL instance not initialized.")
            callback(AcquireTokenResult.NotInitialized)
            return
        }
        if (account == null) {
            Log.w(TAG, "acquireTokenSilent: No account provided.")
            callback(AcquireTokenResult.NoAccountProvided) // Use specific result
            return
        }

        // Authority can often be derived from the account object itself
        val authority = account.authority

        if (authority.isNullOrBlank()) {
            Log.e(
                TAG,
                "acquireTokenSilent: Could not get authority from account ${account.username}."
            )
            callback(
                AcquireTokenResult.Error(
                    MsalClientException(
                        MsalClientException.MALFORMED_URL, // Or a more specific error
                        "Authority URL is null or empty for the provided account."
                    )
                )
            )
            return
        }

        val silentParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(authority) // Use authority from account
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "acquireTokenSilent success for ${account.username}.")
                    callback(AcquireTokenResult.Success(authenticationResult))
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "acquireTokenSilent Error for ${account.username}", exception)
                    if (exception is MsalUiRequiredException) {
                        callback(AcquireTokenResult.UiRequired)
                    } else {
                        callback(AcquireTokenResult.Error(exception))
                    }
                }
            })
            .build()
        msalInstance?.acquireTokenSilentAsync(silentParameters)
    }

    // --- Acquire Token Interactively (Can accept an IAccount hint) ---
    fun acquireTokenInteractive(
        activity: Activity,
        account: IAccount?, // Account can be provided as a hint
        scopes: List<String>,
        callback: (AcquireTokenResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "acquireTokenInteractive called before MSAL initialized or init failed.")
            callback(AcquireTokenResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.d(
                    TAG,
                    "acquireTokenInteractive success. Account: ${authenticationResult.account.username}"
                )
                // Refresh the account list as interaction might change things
                loadAccountsAsync()
                callback(AcquireTokenResult.Success(authenticationResult))
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "acquireTokenInteractive Error", exception)
                callback(AcquireTokenResult.Error(exception))
            }

            override fun onCancel() {
                Log.d(TAG, "acquireTokenInteractive cancelled by user.")
                callback(AcquireTokenResult.Cancelled)
            }
        }

        val builder = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)

        // Provide account as a hint if available
        // Note: MSAL docs for acquireToken often don't show a 'forAccount' method on the
        // interactive parameters builder directly. Using loginHint is common.
        // If interactive flow needs to target a specific *existing* account for re-auth,
        // review MSAL v6 specific guidance or examples for the best approach.
        // For *adding* a new account, no specific account hint is typically needed.
        if (account != null) {
            // builder.forAccount(account) // Check if this exists/works in v6 interactive builder
            builder.withLoginHint(account.username) // Common way to hint
        }

        val interactiveParameters = builder.build()
        msalInstance?.acquireToken(interactiveParameters)
    }

    // TODO: Integrate with AccountManager (Future)
}