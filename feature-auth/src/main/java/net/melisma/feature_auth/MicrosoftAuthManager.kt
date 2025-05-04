package net.melisma.feature_auth // Ensure this matches your module's package structure

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


// --- Sealed Result Classes for Auth Operations ---

/** Represents the outcome of attempting to add/authenticate an account. */
sealed class AddAccountResult {
    /** Successfully added or authenticated the account. */
    data class Success(val account: IAccount) : AddAccountResult()

    /** An MSAL error occurred during the process. */
    data class Error(val exception: MsalException) : AddAccountResult()

    /** The user cancelled the authentication flow. */
    object Cancelled : AddAccountResult()

    /** MSAL was not initialized when the operation was attempted. */
    object NotInitialized : AddAccountResult()
}

/** Represents the outcome of attempting to remove an account. */
sealed class RemoveAccountResult {
    /** Successfully removed the account. */
    object Success : RemoveAccountResult()

    /** An MSAL error occurred during removal. */
    data class Error(val exception: MsalException) : RemoveAccountResult()

    /** MSAL was not initialized when the operation was attempted. */
    object NotInitialized : RemoveAccountResult()

    /** The specified account to remove was not found in MSAL's cache. */
    object AccountNotFound : RemoveAccountResult()
}

/** Represents the outcome of attempting to acquire an access token. */
sealed class AcquireTokenResult {
    /** Successfully acquired the token. */
    data class Success(val result: IAuthenticationResult) : AcquireTokenResult()

    /** An MSAL error occurred during token acquisition. */
    data class Error(val exception: MsalException) : AcquireTokenResult()

    /** The user cancelled an interactive flow required for token acquisition. */
    object Cancelled : AcquireTokenResult()

    /** MSAL was not initialized when the operation was attempted. */
    object NotInitialized : AcquireTokenResult()

    /** The silent token request was made without specifying which account to use. */
    object NoAccountProvided : AcquireTokenResult() // Specific to silent calls

    /** Silent token acquisition failed, and user interaction (e.g., login) is required. */
    object UiRequired : AcquireTokenResult() // Specific to silent calls
}

// --- Listener Interface for Auth State Changes ---

/**
 * Listener interface for components interested in the overall authentication state
 * and the list of currently managed accounts.
 */
interface AuthStateListener {
    /**
     * Called when the MSAL initialization status changes or the list of known accounts updates.
     *
     * @param isInitialized True if MSAL is successfully initialized, false otherwise.
     * @param accounts The current list of [IAccount] objects known to MSAL.
     * @param error The [MsalException] if initialization failed, null otherwise.
     */
    fun onAuthStateChanged(
        isInitialized: Boolean,
        accounts: List<IAccount>, // Exposes MSAL's account type
        error: MsalException?
    )
}

/**
 * Manages interactions with the Microsoft Authentication Library (MSAL) for multi-account scenarios.
 * Handles initialization, loading accounts, adding/removing accounts, and acquiring access tokens.
 * Exposes the authentication state and account list via the [AuthStateListener].
 *
 * @param context Application context.
 * @param configResId The raw resource ID of the MSAL configuration JSON file (e.g., `R.raw.auth_config`).
 */
class MicrosoftAuthManager(
    val context: Context,
    @RawRes private val configResId: Int // Resource ID provided by the consuming module (app)
) {
    private val TAG = "MicrosoftAuthManager"

    // MSAL's primary client application object for multi-account apps.
    private var msalInstance: IMultipleAccountPublicClientApplication? = null

    // Listener interested in auth state changes (typically the AccountRepository).
    private var authStateListener: AuthStateListener? = null

    // --- Public properties reflecting the current state ---

    /** True if the MSAL instance has been successfully created and initialized. */
    var isInitialized: Boolean = false
        private set(value) {
            field = value
            notifyListener() // Notify listener whenever initialization state changes
        }

    /** Holds the exception if MSAL initialization failed, otherwise null. */
    var initializationError: MsalException? = null
        private set(value) {
            field = value
            notifyListener() // Notify listener if an error occurs/clears
        }

    // Backing field for the list of accounts known to MSAL.
    private var _accounts: List<IAccount> = emptyList()

    /** The current list of MSAL [IAccount] objects managed by this instance. */
    val accounts: List<IAccount>
        get() = _accounts // Read-only public access

    // --- Initialization ---
    init {
        Log.d(TAG, "Initializing MicrosoftAuthManager...")
        initializeMsal() // Start initialization immediately on creation
    }

    /** Sets or clears the listener for authentication state changes. */
    fun setAuthStateListener(listener: AuthStateListener?) {
        this.authStateListener = listener
        // Immediately notify the new listener with the current state.
        notifyListener()
    }

    /** Notifies the registered listener (if any) of the current auth state and account list. */
    private fun notifyListener() {
        // Use try-catch as listener implementation might throw exceptions.
        try {
            // Provide the current initialization status, account list, and any init error.
            authStateListener?.onAuthStateChanged(isInitialized, _accounts, initializationError)
            Log.d(
                TAG,
                "Notified Listener: isInitialized=$isInitialized, accountCount=${_accounts.size}, errorPresent=${initializationError != null}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred within authStateListener callback", e)
        }
    }

    /** Updates the internal account list and notifies the listener if the list has changed. */
    private fun updateAccountList(newAccounts: List<IAccount>) {
        // Simple reference check; more robust would be comparing contents if order isn't guaranteed.
        if (_accounts != newAccounts) {
            _accounts = newAccounts
            Log.d(TAG, "Account list updated. Count: ${_accounts.size}")
            notifyListener() // Notify that the account list has potentially changed
        }
    }

    /** Creates the MSAL [IMultipleAccountPublicClientApplication] instance asynchronously. */
    private fun initializeMsal() {
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            context.applicationContext, // Use application context
            configResId, // Pass the resource ID from the constructor
            object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                    msalInstance = application
                    initializationError = null // Clear any previous error
                    isInitialized = true // Set state (triggers notification)
                    Log.i(TAG, "MSAL instance (Multi-Account) created successfully.")
                    loadAccountsAsync() // Load accounts once initialized
                }

                override fun onError(exception: MsalException) {
                    msalInstance = null
                    initializationError = exception // Set error state (triggers notification)
                    isInitialized = false // Set state (triggers notification)
                    updateAccountList(emptyList()) // Clear accounts list on init error
                    Log.e(TAG, "MSAL Initialization Error.", exception)
                }
            })
    }

    /** Loads all accounts known to MSAL asynchronously. */
    private fun loadAccountsAsync() {
        if (!isInitialized || msalInstance == null) {
            Log.w(TAG, "loadAccountsAsync called but MSAL not ready.")
            updateAccountList(emptyList()) // Ensure state reflects no loaded accounts
            return
        }
        // Use the specific callback for getAccounts
        msalInstance?.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(result: List<IAccount>?) {
                Log.d(TAG, "Loaded accounts from MSAL cache. Count = ${result?.size ?: 0}")
                // Update internal list and notify listener
                updateAccountList(result ?: emptyList())
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Error loading accounts from MSAL cache.", exception)
                // Clear internal list and notify listener on error
                updateAccountList(emptyList())
            }
        })
    }

    // --- Public API for Account Management ---

    /**
     * Initiates an interactive flow to add a new account or sign in an existing one.
     *
     * @param activity The [Activity] required to launch the interactive web/broker flow.
     * @param scopes The list of OAuth2 permission scopes required (e.g., "User.Read", "Mail.Read").
     * @param callback Lambda function to receive the [AddAccountResult] (Success, Error, Cancelled, NotInitialized).
     */
    fun addAccount(activity: Activity, scopes: List<String>, callback: (AddAccountResult) -> Unit) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "addAccount called but MSAL not ready.")
            callback(AddAccountResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.i(
                    TAG,
                    "Interactive addAccount/signIn successful for ${authenticationResult.account.username}"
                )
                // Refresh the stored account list after a successful addition/sign-in.
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

        // Build parameters for the interactive token acquisition.
        // For adding an account, we typically don't provide an account hint.
        val interactiveParameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)
            // .withLoginHint(null) // Explicitly null or omitted for adding new account
            // .withPrompt(Prompt.SELECT_ACCOUNT) // Optional prompt behavior
            .build()
        // Start the interactive flow.
        msalInstance?.acquireToken(interactiveParameters)
    }

    /**
     * Removes the specified account from MSAL's cache and potentially the device broker.
     *
     * @param account The [IAccount] object representing the account to remove. Must be obtained from the `accounts` list.
     * @param callback Lambda function to receive the [RemoveAccountResult] (Success, Error, NotInitialized, AccountNotFound).
     */
    fun removeAccount(account: IAccount?, callback: (RemoveAccountResult) -> Unit) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "removeAccount called but MSAL not ready.")
            callback(RemoveAccountResult.NotInitialized)
            return
        }
        if (account == null) {
            Log.e(TAG, "removeAccount called with a null account.")
            callback(RemoveAccountResult.AccountNotFound)
            return
        }

        // Use the specific callback for removeAccount
        msalInstance?.removeAccount(
            account,
            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
            override fun onRemoved() {
                Log.i(TAG, "Account removal successful for ${account.username}.")
                // Refresh the stored account list after removal.
                loadAccountsAsync()
                callback(RemoveAccountResult.Success)
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Account removal error for ${account.username}.", exception)
                callback(RemoveAccountResult.Error(exception))
            }
        })
    }

    // --- Public API for Token Acquisition ---

    /**
     * Attempts to acquire an access token silently for the specified account and scopes.
     * This will check the cache and potentially use a refresh token without user interaction.
     *
     * @param account The specific [IAccount] to acquire the token for. Cannot be null.
     * @param scopes The list of OAuth2 permission scopes required for the token.
     * @param callback Lambda function to receive the [AcquireTokenResult] (Success, Error, UiRequired, etc.).
     */
    fun acquireTokenSilent(
        account: IAccount?, // Account is strictly required for silent calls
        scopes: List<String>,
        callback: (AcquireTokenResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.w(TAG, "acquireTokenSilent called but MSAL not ready.")
            callback(AcquireTokenResult.NotInitialized)
            return
        }
        if (account == null) {
            // This should ideally not happen if called correctly, indicates programming error.
            Log.e(TAG, "acquireTokenSilent called with a null account.")
            callback(AcquireTokenResult.NoAccountProvided)
            return
        }

        // Authority is typically derived from the account object itself.
        val authority = account.authority
        if (authority.isNullOrBlank()) {
            Log.e(TAG, "Could not determine authority from account: ${account.username}")
            // Use INVALID_PARAMETER based on provided documentation
            callback(
                AcquireTokenResult.Error(
                    MsalClientException(
                        MsalClientException.INVALID_PARAMETER,
                        "Account authority is missing or invalid."
                    )
                )
            )
            return
        }

        // Build parameters for the silent token acquisition.
        val silentParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(authority)
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    // Successfully obtained token silently
                    Log.d(TAG, "Silent token acquisition successful for ${account.username}.")
                    callback(AcquireTokenResult.Success(authenticationResult))
                }

                override fun onError(exception: MsalException) {
                    Log.w(TAG, "Silent token acquisition error for ${account.username}.", exception)
                    // Check if the error specifically requires user interaction
                    if (exception is MsalUiRequiredException) {
                        callback(AcquireTokenResult.UiRequired)
                    } else {
                        callback(AcquireTokenResult.Error(exception))
                    }
                }
            })
            .build()
        // Start the asynchronous silent token request.
        msalInstance?.acquireTokenSilentAsync(silentParameters)
    }

    /**
     * Initiates an interactive flow to acquire an access token.
     * This should typically be called only after a silent request fails with [MsalUiRequiredException].
     * It can also be used for step-up authentication or consent gathering.
     *
     * @param activity The [Activity] required to launch the interactive web/broker flow.
     * @param account The specific [IAccount] to acquire the token for (optional, used as a hint).
     * @param scopes The list of OAuth2 permission scopes required for the token.
     * @param callback Lambda function to receive the [AcquireTokenResult] (Success, Error, Cancelled, etc.).
     */
    fun acquireTokenInteractive(
        activity: Activity,
        account: IAccount?, // Can be null, or provided as a hint
        scopes: List<String>,
        callback: (AcquireTokenResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.e(TAG, "acquireTokenInteractive called but MSAL not ready.")
            callback(AcquireTokenResult.NotInitialized)
            return
        }

        // Standard AuthenticationCallback used for interactive flows.
        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.i(
                    TAG,
                    "Interactive token acquisition successful for ${authenticationResult.account.username}"
                )
                // Reload accounts as interactive flow might change state (e.g., new account added).
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

        // Build parameters for the interactive flow.
        val builder = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)

        // If an account is provided, use it as a hint to potentially pre-fill username
        // or streamline the flow if the user needs to re-authenticate that specific account.
        if (account != null) {
            builder.withLoginHint(account.username)
            // Note: MSAL might have other ways to target a specific account for interactive re-auth,
            // check library specifics if simply providing a hint isn't sufficient.
            // builder.forAccount(account) // Check if this exists/is appropriate for interactive
        }

        val interactiveParameters = builder.build()
        // Start the interactive flow.
        msalInstance?.acquireToken(interactiveParameters)
    }

    // TODO: Future integration with Android AccountManager for system-level account storage.
}
