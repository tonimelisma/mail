// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt
package net.melisma.backend_microsoft.auth

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
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
// UPDATED IMPORT for AuthConfigProvider to point to core-data
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
    authConfigProvider: AuthConfigProvider // Type is now imported from core_data
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
        Log.d(TAG, "Initializing MicrosoftAuthManager with config resource ID: $configResId")
        initializeMsal()
    }

    fun setAuthStateListener(listener: AuthStateListener?) {
        this.authStateListener = listener
        if (listener != null) { // Notify immediately if listener is set after initialization
            notifyListener()
        }
    }

    private fun notifyListener() {
        try {
            authStateListener?.onAuthStateChanged(isInitialized, accounts, initializationError)
            Log.d(
                TAG,
                "Notified Listener: isInitialized=$isInitialized, accountCount=${accounts.size}, errorPresent=${initializationError != null}"
            )
        } catch (e: Exception) {
            // Catching all exceptions from listener to prevent crashing the auth manager
            Log.e(TAG, "Error occurred within authStateListener callback", e)
        }
    }

    private fun updateAccountList(newAccounts: List<IAccount>) {
        // Ensure thread-safety if accounts can be modified from multiple threads,
        // though MSAL callbacks are typically on main thread.
        // For simplicity, direct assignment is used here.
        _accounts = newAccounts.toList() // Store a copy
        Log.d(TAG, "Account list updated. Count: ${accounts.size}")
        notifyListener() // Notify after updating the internal list
    }

    private fun initializeMsal() {
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            context.applicationContext,
            configResId,
            object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                    msalInstance = application
                    initializationError = null
                    isInitialized = true
                    Log.i(TAG, "MSAL instance (Multi-Account) created successfully.")
                    loadAccountsAsync()
                }

                override fun onError(exception: MsalException) {
                    msalInstance = null
                    initializationError = exception
                    isInitialized = false
                    updateAccountList(emptyList()) // Ensure accounts list is cleared on init error
                    Log.e(TAG, "MSAL Initialization Error.", exception)
                }
            })
    }

    private fun loadAccountsAsync() {
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.w(TAG, "loadAccountsAsync called but MSAL not ready or instance is null.")
            updateAccountList(emptyList()) // Ensure consistent state
            return
        }
        currentMsalInstance.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(result: List<IAccount>?) {
                Log.d(TAG, "Loaded accounts from MSAL cache. Count = ${result?.size ?: 0}")
                updateAccountList(result ?: emptyList())
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Error loading accounts from MSAL cache.", exception)
                // Optionally, set initializationError here too or handle differently
                updateAccountList(emptyList()) // Clear accounts on error
            }
        })
    }

    fun addAccount(
        activity: Activity,
        scopes: List<String>
    ): Flow<AddAccountResult> = callbackFlow {
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.e(TAG, "addAccount: MSAL not initialized or instance is null")
            trySend(AddAccountResult.NotInitialized)
            close()
            return@callbackFlow
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.i(
                    TAG,
                    "Interactive addAccount/signIn successful for ${authenticationResult.account.username}"
                )
                loadAccountsAsync() // Refresh account list
                trySend(AddAccountResult.Success(authenticationResult.account))
                close()
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Interactive addAccount/signIn error.", exception)
                trySend(AddAccountResult.Error(exception))
                close()
            }

            override fun onCancel() {
                Log.w(TAG, "Interactive addAccount/signIn cancelled by user.")
                trySend(AddAccountResult.Cancelled)
                close()
            }
        }

        val interactiveParameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)
            .build()

        currentMsalInstance.acquireToken(interactiveParameters)
        awaitClose {
            Log.d(
                TAG,
                "addAccount Flow for scopes [${scopes.joinToString()}] cancelled or completed"
            )
        }
    }

    fun removeAccount(
        accountToRemove: IAccount? // Renamed for clarity
    ): Flow<RemoveAccountResult> = callbackFlow {
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.e(TAG, "removeAccount: MSAL not initialized or instance is null")
            trySend(RemoveAccountResult.NotInitialized)
            close()
            return@callbackFlow
        }

        if (accountToRemove == null) {
            Log.e(TAG, "removeAccount: Account to remove is null")
            trySend(RemoveAccountResult.AccountNotFound)
            close()
            return@callbackFlow
        }

        currentMsalInstance.removeAccount(
            accountToRemove,
            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() {
                    Log.i(TAG, "Account removal successful for ${accountToRemove.username}.")
                    loadAccountsAsync() // Refresh account list
                    trySend(RemoveAccountResult.Success)
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Account removal error for ${accountToRemove.username}.", exception)
                    trySend(RemoveAccountResult.Error(exception))
                    close()
                }
            })
        awaitClose {
            Log.d(
                TAG,
                "removeAccount Flow for ${accountToRemove.username} cancelled or completed"
            )
        }
    }

    fun acquireTokenSilent(
        accountToAuth: IAccount?, // Renamed for clarity
        scopes: List<String>
    ): Flow<AcquireTokenResult> = callbackFlow {
        val currentMsalInstance = msalInstance
        if (!isInitialized || currentMsalInstance == null) {
            Log.e(TAG, "acquireTokenSilent: MSAL not initialized or instance is null")
            trySend(AcquireTokenResult.NotInitialized)
            close()
            return@callbackFlow
        }

        if (accountToAuth == null) {
            Log.e(TAG, "acquireTokenSilent: Account is null")
            trySend(AcquireTokenResult.NoAccountProvided)
            close()
            return@callbackFlow
        }

        val authority = accountToAuth.authority
        if (authority.isNullOrBlank()) {
            Log.e(
                TAG,
                "acquireTokenSilent: Authority is null or blank for ${accountToAuth.username}"
            )
            // It's better to inform UI is required if authority is missing, as it's a setup issue.
            trySend(AcquireTokenResult.UiRequired)
            close()
            return@callbackFlow
        }

        val silentParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(accountToAuth)
            .fromAuthority(authority) // MSAL requires authority for silent calls
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Silent token acquisition successful for ${accountToAuth.username}.")
                    trySend(AcquireTokenResult.Success(authenticationResult))
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.w(
                        TAG,
                        "Silent token acquisition error for ${accountToAuth.username}.",
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
                "acquireTokenSilent Flow for ${accountToAuth.username} scopes [${scopes.joinToString()}] cancelled or completed"
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
}
