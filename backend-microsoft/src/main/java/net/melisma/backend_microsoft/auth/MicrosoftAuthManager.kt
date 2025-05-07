package net.melisma.backend_microsoft.auth // Ensure this is the correct package from Step 1

// Standard MSAL imports follow
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
import net.melisma.backend_microsoft.di.AuthConfigProvider


// --- Sealed Result Classes (No changes needed here) ---
sealed class AddAccountResult { /* ... content as before ... */
    data class Success(val account: IAccount) : AddAccountResult()
    data class Error(val exception: MsalException) : AddAccountResult()
    object Cancelled : AddAccountResult()
    object NotInitialized : AddAccountResult()
}

sealed class RemoveAccountResult { /* ... content as before ... */
    object Success : RemoveAccountResult()
    data class Error(val exception: MsalException) : RemoveAccountResult()
    object NotInitialized : RemoveAccountResult()
    object AccountNotFound : RemoveAccountResult()
}

sealed class AcquireTokenResult { /* ... content as before ... */
    data class Success(val result: IAuthenticationResult) : AcquireTokenResult()
    data class Error(val exception: MsalException) : AcquireTokenResult()
    object Cancelled : AcquireTokenResult()
    object NotInitialized : AcquireTokenResult()
    object NoAccountProvided : AcquireTokenResult()
    object UiRequired : AcquireTokenResult()
}

// --- Listener Interface (No changes needed here) ---
interface AuthStateListener { /* ... content as before ... */
    fun onAuthStateChanged(
        isInitialized: Boolean,
        accounts: List<IAccount>,
        error: MsalException?
    )
}

/**
 * Manages interactions with MSAL. Now receives its config resource ID via AuthConfigProvider.
 *
 * @param context Application context.
 * @param authConfigProvider Provider instance that supplies the MSAL config resource ID.
 */
class MicrosoftAuthManager(
    val context: Context,
    // <<< CHANGED: Inject the provider interface instead of the raw Int ID
    authConfigProvider: AuthConfigProvider
) {
    private val TAG = "MicrosoftAuthManager"

    // <<< CHANGED: Get the resource ID from the provider
    @RawRes
    private val configResId: Int = authConfigProvider.getMsalConfigResId()

    // MSAL instance, listener, state properties (no changes needed here)
    private var msalInstance: IMultipleAccountPublicClientApplication? = null
    private var authStateListener: AuthStateListener? = null
    var isInitialized: Boolean = false
        private set(value) { /* ... content as before ... */
            field = value
            notifyListener()
        }
    var initializationError: MsalException? = null
        private set(value) { /* ... content as before ... */
            field = value
            notifyListener()
        }
    private var _accounts: List<IAccount> = emptyList()
    val accounts: List<IAccount>
        get() = _accounts

    // --- Initialization ---
    init {
        Log.d(TAG, "Initializing MicrosoftAuthManager...")
        // The initializeMsal call now uses the configResId field set above
        initializeMsal()
    }

    // --- Listener Methods (No changes needed) ---
    fun setAuthStateListener(listener: AuthStateListener?) { /* ... content as before ... */
        this.authStateListener = listener
        notifyListener()
    }

    private fun notifyListener() { /* ... content as before ... */
        try {
            authStateListener?.onAuthStateChanged(isInitialized, _accounts, initializationError)
            Log.d(
                TAG,
                "Notified Listener: isInitialized=$isInitialized, accountCount=${_accounts.size}, errorPresent=${initializationError != null}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred within authStateListener callback", e)
        }
    }

    private fun updateAccountList(newAccounts: List<IAccount>) { /* ... content as before ... */
        if (_accounts != newAccounts) {
            _accounts = newAccounts
            Log.d(TAG, "Account list updated. Count: ${_accounts.size}")
            notifyListener()
        }
    }

    // --- MSAL Interaction Methods (No changes needed in the logic) ---
    private fun initializeMsal() { /* ... content as before, uses the configResId field ... */
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            context.applicationContext,
            configResId, // Uses the field derived from AuthConfigProvider
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
                    updateAccountList(emptyList())
                    Log.e(TAG, "MSAL Initialization Error.", exception)
                }
            })
    }

    private fun loadAccountsAsync() { /* ... content as before ... */
        if (!isInitialized || msalInstance == null) {
            Log.w(TAG, "loadAccountsAsync called but MSAL not ready.")
            updateAccountList(emptyList())
            return
        }
        msalInstance?.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(result: List<IAccount>?) {
                Log.d(TAG, "Loaded accounts from MSAL cache. Count = ${result?.size ?: 0}")
                updateAccountList(result ?: emptyList())
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Error loading accounts from MSAL cache.", exception)
                updateAccountList(emptyList())
            }
        })
    }

    /**
     * Adds a new account by triggering the interactive sign-in flow.
     * Returns a Flow of AddAccountResult that will emit exactly one value and then complete.
     *
     * @param activity The activity to start the authentication flow from
     * @param scopes The scopes to request access for
     * @return Flow<AddAccountResult> emitting exactly one result value
     */
    fun addAccount(
        activity: Activity,
        scopes: List<String>
    ): Flow<AddAccountResult> = callbackFlow {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "addAccount: MSAL not initialized")
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
                loadAccountsAsync()
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

        msalInstance?.acquireToken(interactiveParameters)

        // Wait for the channel to close when the Flow is cancelled
        awaitClose {
            Log.d(TAG, "addAccount Flow cancelled or completed")
            // No direct cancellation mechanism in MSAL interactive calls
        }
    }

    /**
     * Legacy callback-based method for backward compatibility.
     * Prefer using the Flow-based version when possible.
     */
    fun addAccount(
        activity: Activity,
        scopes: List<String>,
        callback: (AddAccountResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "addAccount (callback): MSAL not ready.")
            callback(AddAccountResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.i(
                    TAG,
                    "Interactive addAccount/signIn successful for ${authenticationResult.account.username}"
                )
                loadAccountsAsync()
                callback(AddAccountResult.Success(authenticationResult.account))
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Interactive addAccount/signIn error.", exception)
                callback(AddAccountResult.Error(exception))
            }

            override fun onCancel() {
                Log.w(TAG, "Interactive addAccount/signIn cancelled by user.")
                callback(AddAccountResult.Cancelled)
            }
        }

        val interactiveParameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)
            .build()

        msalInstance?.acquireToken(interactiveParameters)
    }

    /**
     * Removes an account.
     * Returns a Flow of RemoveAccountResult that will emit exactly one value and then complete.
     *
     * @param account The account to remove
     * @return Flow<RemoveAccountResult> emitting exactly one result value
     */
    fun removeAccount(
        account: IAccount?
    ): Flow<RemoveAccountResult> = callbackFlow {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "removeAccount: MSAL not initialized")
            trySend(RemoveAccountResult.NotInitialized)
            close()
            return@callbackFlow
        }

        if (account == null) {
            Log.e(TAG, "removeAccount: Account is null")
            trySend(RemoveAccountResult.AccountNotFound)
            close()
            return@callbackFlow
        }

        msalInstance?.removeAccount(
            account,
            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() {
                    Log.i(TAG, "Account removal successful for ${account.username}.")
                    loadAccountsAsync()
                    trySend(RemoveAccountResult.Success)
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Account removal error for ${account.username}.", exception)
                    trySend(RemoveAccountResult.Error(exception))
                    close()
                }
            })

        // Wait for the channel to close when the Flow is cancelled
        awaitClose {
            Log.d(TAG, "removeAccount Flow cancelled or completed for ${account.username}")
            // No cancellation mechanism available for removeAccount
        }
    }

    /**
     * Legacy callback-based method for backward compatibility.
     * Prefer using the Flow-based version when possible.
     */
    fun removeAccount(
        account: IAccount?,
        callback: (RemoveAccountResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "removeAccount (callback): MSAL not ready.")
            callback(RemoveAccountResult.NotInitialized)
            return
        }

        if (account == null) {
            Log.e(TAG, "removeAccount (callback): Account is null.")
            callback(RemoveAccountResult.AccountNotFound)
            return
        }

        msalInstance?.removeAccount(
            account,
            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() {
                    Log.i(TAG, "Account removal successful for ${account.username}.")
                    loadAccountsAsync()
                    callback(RemoveAccountResult.Success)
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Account removal error for ${account.username}.", exception)
                    callback(RemoveAccountResult.Error(exception))
                }
            })
    }

    /**
     * Acquires a token silently for the provided account and scopes.
     * Returns a Flow of AcquireTokenResult that will emit exactly one value and then complete.
     *
     * @param account The account to acquire the token for
     * @param scopes The scopes to request access for
     * @return Flow<AcquireTokenResult> emitting exactly one token result or error
     */
    fun acquireTokenSilent(
        account: IAccount?,
        scopes: List<String>
    ): Flow<AcquireTokenResult> = callbackFlow {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "acquireTokenSilent: MSAL not initialized")
            trySend(AcquireTokenResult.NotInitialized)
            close()
            return@callbackFlow
        }

        if (account == null) {
            Log.e(TAG, "acquireTokenSilent: Account is null")
            trySend(AcquireTokenResult.NoAccountProvided)
            close()
            return@callbackFlow
        }

        val authority = account.authority
        if (authority.isNullOrBlank()) {
            Log.e(TAG, "acquireTokenSilent: Authority is null or blank for ${account.username}")
            trySend(
                AcquireTokenResult.Error(
                    MsalUiRequiredException("invalid_authority", "Authority is null or blank")
                )
            )
            close()
            return@callbackFlow
        }

        val silentParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(authority)
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Silent token acquisition successful for ${account.username}.")
                    trySend(AcquireTokenResult.Success(authenticationResult))
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.w(TAG, "Silent token acquisition error for ${account.username}.", exception)
                    if (exception is MsalUiRequiredException) {
                        trySend(AcquireTokenResult.UiRequired)
                    } else {
                        trySend(AcquireTokenResult.Error(exception))
                    }
                    close()
                }
            })
            .build()

        msalInstance?.acquireTokenSilentAsync(silentParameters)

        // Wait for the channel to close when the Flow is cancelled
        awaitClose {
            Log.d(TAG, "acquireTokenSilent Flow cancelled or completed for ${account.username}")
            // No cancellation mechanism available in MSAL silent API, but we can clean up here if needed
        }
    }

    /**
     * Legacy callback-based method for backward compatibility.
     * Prefer using the Flow-based version when possible.
     */
    fun acquireTokenSilent(
        account: IAccount?,
        scopes: List<String>,
        callback: (AcquireTokenResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "acquireTokenSilent (callback): MSAL not initialized")
            callback(AcquireTokenResult.NotInitialized)
            return
        }

        if (account == null) {
            Log.e(TAG, "acquireTokenSilent (callback): Account is null")
            callback(AcquireTokenResult.NoAccountProvided)
            return
        }

        val authority = account.authority
        if (authority.isNullOrBlank()) {
            Log.e(
                TAG,
                "acquireTokenSilent (callback): Authority is null or blank for ${account.username}"
            )
            callback(
                AcquireTokenResult.Error(
                    MsalUiRequiredException("invalid_authority", "Authority is null or blank")
                )
            )
            return
        }

        val silentParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(authority)
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Silent token acquisition successful for ${account.username}.")
                    callback(AcquireTokenResult.Success(authenticationResult))
                }

                override fun onError(exception: MsalException) {
                    Log.w(TAG, "Silent token acquisition error for ${account.username}.", exception)
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

    /**
     * Acquires a token interactively for the provided account and scopes.
     * Returns a Flow of AcquireTokenResult that will emit exactly one value and then complete.
     *
     * @param activity The activity to start the authentication flow from
     * @param account The account to acquire the token for (optional - can be null for new accounts)
     * @param scopes The scopes to request access for
     * @return Flow<AcquireTokenResult> emitting exactly one token result or error
     */
    fun acquireTokenInteractive(
        activity: Activity,
        account: IAccount?,
        scopes: List<String>
    ): Flow<AcquireTokenResult> = callbackFlow {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "acquireTokenInteractive: MSAL not initialized")
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
                loadAccountsAsync()
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

        if (account != null) {
            builder.withLoginHint(account.username)
        }

        val interactiveParameters = builder.build()
        msalInstance?.acquireToken(interactiveParameters)

        // Wait for the channel to close when the Flow is cancelled
        awaitClose {
            Log.d(TAG, "acquireTokenInteractive Flow cancelled or completed")
            // No direct cancellation mechanism in MSAL, but we could potentially
            // set a flag to ignore callbacks if we wanted to enhance this later
        }
    }

    /**
     * Legacy callback-based method for backward compatibility.
     * Prefer using the Flow-based version when possible.
     */
    fun acquireTokenInteractive(
        activity: Activity,
        account: IAccount?,
        scopes: List<String>,
        callback: (AcquireTokenResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "acquireTokenInteractive (callback): MSAL not initialized")
            callback(AcquireTokenResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.i(
                    TAG,
                    "Interactive token acquisition successful for ${authenticationResult.account.username}"
                )
                loadAccountsAsync()
                callback(AcquireTokenResult.Success(authenticationResult))
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Interactive token acquisition error.", exception)
                callback(AcquireTokenResult.Error(exception))
            }

            override fun onCancel() {
                Log.w(TAG, "Interactive token acquisition cancelled by user.")
                callback(AcquireTokenResult.Cancelled)
            }
        }

        val builder = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)

        if (account != null) {
            builder.withLoginHint(account.username)
        }

        val interactiveParameters = builder.build()
        msalInstance?.acquireToken(interactiveParameters)
    }
}