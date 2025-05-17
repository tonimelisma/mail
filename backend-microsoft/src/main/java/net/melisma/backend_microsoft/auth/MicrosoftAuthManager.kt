// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt
package net.melisma.backend_microsoft.auth

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.AuthConfigProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
    private val context: Context,
    private val authConfigProvider: AuthConfigProvider,
    @ApplicationScope private val externalScope: CoroutineScope
) {
    private val TAG = "MicrosoftAuthManager"
    private var mMultipleAccountApp: IMultipleAccountPublicClientApplication? = null
    private val _isMsalInitialized = MutableStateFlow(false)
    val isMsalInitialized: StateFlow<Boolean> = _isMsalInitialized.asStateFlow()
    private var initializationException: MsalException? = null

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
                                    continuation.resume(application)
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
        currentApp.getAccount(accountId, object : IPublicClientApplication.GetAccountCallback {
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
}
