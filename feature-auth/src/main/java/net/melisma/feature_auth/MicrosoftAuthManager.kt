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
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException

// --- Sealed Result Classes (Keep As Is) ---
sealed class SignInResult {
    data class Success(val account: IAccount) : SignInResult()
    data class Error(val exception: MsalException) : SignInResult()
    object Cancelled : SignInResult()
    object NotInitialized : SignInResult()
}

// ... (SignOutResult and AcquireTokenResult remain the same) ...
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


class MicrosoftAuthManager(
    // Make context public so MainActivity can access it for Toasts (temporary fix)
    val context: Context,
    @RawRes private val configResId: Int
) {

    private var msalInstance: ISingleAccountPublicClientApplication? = null
    val currentAccount: IAccount?
        get() = msalInstance?.currentAccount?.currentAccount

    var isInitialized = false
        private set
    var initializationError: MsalException? = null
        private set

    init {
        initializeMsal()
    }

    // --- Rest of the methods (initializeMsal, signIn, signOut, etc.) remain the same ---
    // --- as in the previous version (microsoft_auth_manager_v5) ---
    private fun initializeMsal() {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context,
            configResId,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalInstance = application
                    isInitialized = true
                    initializationError = null
                    Log.d("MicrosoftAuthManager", "MSAL instance created successfully.")
                }

                override fun onError(exception: MsalException) {
                    msalInstance = null
                    isInitialized = false
                    initializationError = exception
                    Log.e("MicrosoftAuthManager", "MSAL Init Error", exception)
                }
            })
    }

    // --- Sign In ---
    fun signIn(activity: Activity, scopes: List<String>, callback: (SignInResult) -> Unit) {
        if (msalInstance == null) {
            Log.e("MicrosoftAuthManager", "signIn called before MSAL initialized.")
            callback(SignInResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.d(
                    "MicrosoftAuthManager",
                    "Sign in success. Account: ${authenticationResult.account.username}"
                )
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
            .withLoginHint(null)
            .withScopes(scopes)
            .withCallback(authCallback)
            .build()

        msalInstance?.signIn(signInParameters)
    }

    // --- Sign Out ---
    fun signOut(callback: (SignOutResult) -> Unit) {
        if (msalInstance == null) {
            Log.e("MicrosoftAuthManager", "signOut called before MSAL initialized.")
            callback(SignOutResult.NotInitialized)
            return
        }

        msalInstance?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                Log.d("MicrosoftAuthManager", "Sign out success.")
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
        val account = msalInstance?.currentAccount?.currentAccount
        val authority = msalInstance?.configuration?.defaultAuthority?.authorityURL?.toString()

        if (msalInstance == null) {
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
        if (msalInstance == null) {
            Log.e("MicrosoftAuthManager", "acquireTokenInteractive called before MSAL initialized.")
            callback(AcquireTokenResult.NotInitialized)
            return
        }

        val authCallback = object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
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

    // Removed isBrokerAvailable function

    // TODO: Integrate with AccountManager
}
