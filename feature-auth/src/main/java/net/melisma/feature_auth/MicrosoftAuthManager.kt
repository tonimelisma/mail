package net.melisma.feature_auth // Ensure this matches your module's package structure

// MSAL imports
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.RawRes
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException

// --- Sealed Result Classes (Keep As Is for callbacks) ---
sealed class SignInResult {
    data class Success(val account: IAccount) : SignInResult()
    data class Error(val exception: MsalException) : SignInResult()
    object Cancelled : SignInResult()
    object NotInitialized : SignInResult()
}

sealed class SignOutResult {
    object Success : SignOutResult()
    data class Error(val exception: MsalException) : SignOutResult()
    object NotInitialized : SignOutResult()
}

sealed class AcquireTokenResult {
    data class Success(val result: IAuthenticationResult) : AcquireTokenResult()
    data class Error(val exception: MsalException) : AcquireTokenResult()
    object Cancelled : AcquireTokenResult()
    object NotInitialized : AcquireTokenResult()
    object NoAccount : AcquireTokenResult()
    object UiRequired : AcquireTokenResult()
}

// --- Listener Interface for UI updates ---
interface AuthStateListener {
    fun onAuthStateChanged(isInitialized: Boolean, account: IAccount?, error: MsalException?)
}

class MicrosoftAuthManager(
    val context: Context, // Keep public for now for Toast access
    @RawRes private val configResId: Int
) {

    private var msalInstance: ISingleAccountPublicClientApplication? = null
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

    // Backing field for the account, updated only via async callbacks
    private var _currentAccount: IAccount? = null
    val currentAccount: IAccount?
        get() = _currentAccount
    // ---

    init {
        Log.d("MicrosoftAuthManager", "Initializing...")
        initializeMsal()
    }

    fun setAuthStateListener(listener: AuthStateListener?) {
        this.authStateListener = listener
        // Immediately notify with current state upon registration
        notifyListener()
    }

    private fun notifyListener() {
        // Ensure listener calls happen on the main thread if UI updates are involved
        // For simplicity now, calling directly. Consider Handler or Coroutines later.
        authStateListener?.onAuthStateChanged(isInitialized, _currentAccount, initializationError)
        Log.d(
            "MicrosoftAuthManager",
            "Notified Listener: isInitialized=$isInitialized, account=${_currentAccount?.username}"
        )
    }

    private fun updateAccountState(newAccount: IAccount?) {
        if (_currentAccount != newAccount) {
            _currentAccount = newAccount
            notifyListener() // Notify listener when account state changes
        }
    }

    private fun initializeMsal() {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context.applicationContext, // Use application context
            configResId,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalInstance = application
                    initializationError = null
                    isInitialized = true // This triggers the setter and notifyListener()
                    Log.d("MicrosoftAuthManager", "MSAL instance created successfully.")
                    loadAccountAsync() // Load initial account state
                }
                override fun onError(exception: MsalException) {
                    msalInstance = null
                    initializationError = exception // Triggers setter
                    isInitialized = false // Triggers setter
                    updateAccountState(null) // Clear account state
                    Log.e("MicrosoftAuthManager", "MSAL Init Error.", exception)
                }
            })
    }

    private fun loadAccountAsync() {
        if (!isInitialized || msalInstance == null) {
            Log.w("MicrosoftAuthManager", "loadAccountAsync called before MSAL initialized.")
            return
        }
        msalInstance?.getCurrentAccountAsync(object :
            ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                Log.d("MicrosoftAuthManager", "Async Account loaded: ${activeAccount?.username}")
                updateAccountState(activeAccount) // Update state via helper
            }

            override fun onAccountChanged(priorAccount: IAccount?, newAccount: IAccount?) {
                Log.d("MicrosoftAuthManager", "Async Account changed to: ${newAccount?.username}")
                updateAccountState(newAccount) // Update state via helper
            }

            override fun onError(exception: MsalException) {
                Log.e("MicrosoftAuthManager", "MSAL Load Account Async Error", exception)
                updateAccountState(null) // Clear account state on error
            }
        })
    }

    // --- Sign In ---
    fun signIn(activity: Activity, scopes: List<String>, callback: (SignInResult) -> Unit) {
        if (!isInitialized || msalInstance == null) {
            Log.e("MicrosoftAuthManager", "signIn called before MSAL initialized or init failed.")
            callback(SignInResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.d(
                    "MicrosoftAuthManager",
                    "Sign in success. Account: ${authenticationResult.account.username}"
                )
                updateAccountState(authenticationResult.account) // Update internal state
                callback(SignInResult.Success(authenticationResult.account))
            }
            override fun onError(exception: MsalException) {
                Log.e("MicrosoftAuthManager", "Sign In Error", exception)
                callback(SignInResult.Error(exception))
            }
            override fun onCancel() {
                Log.d("MicrosoftAuthManager", "Sign in cancelled by user.")
                callback(SignInResult.Cancelled)
            }
        }

        val signInParameters = SignInParameters.builder()
            .withActivity(activity)
            .withLoginHint(null) // Can be null
            .withScopes(scopes)
            .withCallback(authCallback)
            .build()
        msalInstance?.signIn(signInParameters)
    }

    // --- Sign Out ---
    fun signOut(callback: (SignOutResult) -> Unit) {
        if (!isInitialized || msalInstance == null) {
            Log.e("MicrosoftAuthManager", "signOut called before MSAL initialized or init failed.")
            callback(SignOutResult.NotInitialized)
            return
        }

        msalInstance?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                Log.d("MicrosoftAuthManager", "Sign out success.")
                updateAccountState(null) // Update internal state
                callback(SignOutResult.Success)
            }
            override fun onError(exception: MsalException) {
                Log.e("MicrosoftAuthManager", "Sign Out Error", exception)
                callback(SignOutResult.Error(exception))
            }
        })
    }

    // --- Acquire Token Silently ---
    fun acquireTokenSilent(scopes: List<String>, callback: (AcquireTokenResult) -> Unit) {
        val account = _currentAccount // Read from the backing field
        val authority = msalInstance?.configuration?.defaultAuthority?.authorityURL?.toString()

        if (!isInitialized || msalInstance == null) {
            Log.w("MicrosoftAuthManager", "acquireTokenSilent: MSAL instance not initialized.")
            callback(AcquireTokenResult.NotInitialized)
            return
        }
        if (account == null) {
            Log.w("MicrosoftAuthManager", "acquireTokenSilent: No account found.")
            callback(AcquireTokenResult.NoAccount)
            return
        }
        if (authority == null) {
            Log.e("MicrosoftAuthManager", "acquireTokenSilent: Could not get authority.")
            callback(
                AcquireTokenResult.Error(
                    MsalClientException(
                        MsalClientException.MALFORMED_URL,
                        "Authority URL is null."
                    )
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
                    Log.d("MicrosoftAuthManager", "acquireTokenSilent success.")
                    callback(AcquireTokenResult.Success(authenticationResult))
                }

                override fun onError(exception: MsalException) {
                    Log.e("MicrosoftAuthManager", "acquireTokenSilent Error", exception)
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

    // --- Acquire Token Interactively ---
    fun acquireTokenInteractive(
        activity: Activity,
        scopes: List<String>,
        callback: (AcquireTokenResult) -> Unit
    ) {
        if (!isInitialized || msalInstance == null) {
            Log.e(
                "MicrosoftAuthManager",
                "acquireTokenInteractive called before MSAL initialized or init failed."
            )
            callback(AcquireTokenResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                updateAccountState(authenticationResult.account) // Update state if needed
                Log.d("MicrosoftAuthManager", "acquireTokenInteractive success.")
                callback(AcquireTokenResult.Success(authenticationResult))
            }
            override fun onError(exception: MsalException) {
                Log.e("MicrosoftAuthManager", "acquireTokenInteractive Error", exception)
                callback(AcquireTokenResult.Error(exception))
            }
            override fun onCancel() {
                Log.d("MicrosoftAuthManager", "acquireTokenInteractive cancelled by user.")
                callback(AcquireTokenResult.Cancelled)
            }
        }

        val interactiveParameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(authCallback)
            .build()
        msalInstance?.acquireToken(interactiveParameters)
    }

    // TODO: Integrate with AccountManager
}
