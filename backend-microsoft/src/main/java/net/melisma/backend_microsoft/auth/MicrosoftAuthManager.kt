// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt
package net.melisma.backend_microsoft.auth

// import android.accounts.AccountManager // No longer directly used here
// import kotlinx.coroutines.Dispatchers // Not used directly
// import net.melisma.core_data.security.SecureEncryptionService // No longer directly used here

// New imports for PersistenceResult
// import net.melisma.backend_microsoft.common.PersistenceResult // Old import

// Model imports
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
import net.melisma.backend_microsoft.model.ManagedMicrosoftAccount
import net.melisma.core_data.common.PersistenceResult
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

// import com.microsoft.identity.client.IAccount // IAccount is used directly in ManagedMicrosoftAccount

// Constants for AccountManager keys are now in MicrosoftTokenPersistenceService

// PersistedMicrosoftAccount data class is now in net.melisma.backend_microsoft.model package

// Sealed Result Classes - These remain as they define the API of this manager
// ... (AddAccountResult, RemoveAccountResult, AcquireTokenResult, AuthenticationResultWrapper, SignOutResultWrapper) ...
// Retaining existing sealed classes
sealed class AddAccountResult {
    data class Success(val managedAccount: ManagedMicrosoftAccount) : AddAccountResult()
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

interface AuthStateListener {
    fun onAuthStateChanged(
        isInitialized: Boolean,
        accounts: List<IAccount>,
        error: MsalException?
    )
}

sealed class AuthenticationResultWrapper {
    data class Success(
        val managedAccount: ManagedMicrosoftAccount,
        val authenticationResult: IAuthenticationResult
    ) :
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
    // private val secureEncryptionService: SecureEncryptionService, // Injected into persistence service
    private val tokenPersistenceService: MicrosoftTokenPersistenceService, // New service
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder, // New injection
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "MicrosoftAuthManager"

    // private val PERSISTENCE_TAG = "MsAuthManagerPersist" // Moved to persistence service
    private var mMultipleAccountApp: IMultipleAccountPublicClientApplication? = null
    private val _isMsalInitialized = MutableStateFlow(false)
    val isMsalInitialized: StateFlow<Boolean> = _isMsalInitialized.asStateFlow()
    private var initializationException: MsalException? = null

    // private val accountManager: AccountManager by lazy { AccountManager.get(context) } // Moved to persistence service

    companion object {
        // Scopes remain relevant for this manager's API
        val MICROSOFT_SCOPES = listOf(
            "User.Read",
            "Mail.Read",
            "offline_access",
            "Mail.Send",
            "Calendars.ReadWrite",
            "Contacts.ReadWrite"
        )

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
                    externalScope.launch(ioDispatcher) { // Ensure persistence is on IO dispatcher
                        val displayNameFromClaims =
                            authenticationResult.account.claims?.get("name") as? String
                        val saveResult = tokenPersistenceService.saveAccountInfo(
                            msalAccount = authenticationResult.account,
                            authResult = authenticationResult,
                            displayNameFromClaims = displayNameFromClaims
                        )
                        if (saveResult is PersistenceResult.Success) {
                            Timber.tag(TAG)
                                .i("Account info saved via TokenPersistenceService for ${authenticationResult.account.username}")
                            authenticationResult.account.id?.let {
                                activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(it)
                            }
                        } else if (saveResult is PersistenceResult.Failure) {
                            Timber.tag(TAG)
                                .e("Failed to save account info via TokenPersistenceService for ${authenticationResult.account.username}: ${saveResult.errorType} - ${saveResult.message}")
                            // Optional: Decide if this should change the overall trySend result to an error for the UI
                            // For now, MSAL auth was successful, so we proceed with success, but log persistence error.
                        }
                    }

                    externalScope.launch {
                        val managedAccount = enrichIAccount(authenticationResult.account)
                        trySend(
                            AuthenticationResultWrapper.Success(
                                managedAccount,
                                authenticationResult
                            )
                        )
                        close()
                    }
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
        currentApp.acquireToken(acquireTokenParameters)
    }.flowOn(ioDispatcher) // Execute the MSAL call itself on IO dispatcher if appropriate (MSAL might manage its own threads)

    fun acquireTokenSilent(
        account: IAccount,
        scopes: List<String>
    ): Flow<AuthenticationResultWrapper> = callbackFlow {
        Timber.tag(TAG)
            .d("acquireTokenSilent: called for account: ${account.username}, scopes: $scopes")
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("acquireTokenSilent: MSAL client not initialized.")
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
        Timber.tag(TAG).d("Using authority: $authority for silent token acquisition.")

        val silentTokenParameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(authority) // It's good practice to specify authority
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Timber.tag(TAG)
                        .i("acquireTokenSilent: Success for account ${authenticationResult.account.username}")
                    // Optionally update persisted tokens here if we decide to cache them
                    // externalScope.launch(ioDispatcher) {
                    // tokenPersistenceService.updatePersistedTokens(authenticationResult.account.id, authenticationResult.accessToken, authenticationResult.idToken)
                    // }
                    externalScope.launch {
                        val managedAccount = enrichIAccount(authenticationResult.account)
                        trySend(
                            AuthenticationResultWrapper.Success(
                                managedAccount,
                                authenticationResult
                            )
                        )
                        close()
                    }
                }

                override fun onError(exception: MsalException) {
                    Timber.tag(TAG)
                        .e(exception, "acquireTokenSilent: Error for account ${account.username}")
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
        currentApp.acquireTokenSilentAsync(silentTokenParameters)
    }.flowOn(ioDispatcher)


    fun signOut(account: IAccount): Flow<SignOutResultWrapper> = callbackFlow {
        Timber.tag(TAG).i("signOut called for account: ${account.username}")
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("signOut: MSAL client not initialized.")
            trySend(
                SignOutResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.INVALID_PARAMETER,
                        "MSAL client not initialized."
                    )
                )
            )
            close()
            return@callbackFlow
        }

        currentApp.removeAccount(
            account,
            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() {
                    Timber.tag(TAG).i("MSAL account removed successfully: ${account.username}")
                    externalScope.launch(ioDispatcher) {
                        account.id?.let { accountId ->
                            val clearResult = tokenPersistenceService.clearAccountData(
                                accountId,
                                removeAccountFromManager = true
                            )
                            if (clearResult is PersistenceResult.Success) {
                                Timber.tag(TAG)
                                    .i("Account data cleared from persistence for ${account.username}")
                                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == accountId) {
                                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                                }
                            } else if (clearResult is PersistenceResult.Failure) {
                                Timber.tag(TAG)
                                    .e("Failed to clear account data from persistence for ${account.username}: ${clearResult.errorType} - ${clearResult.message}")
                                // MSAL removal succeeded, so this is a lingering persistence issue.
                                // The overall signOut for MSAL is still considered success from MSAL's perspective.
                            }
                        }
                    }
                    trySend(SignOutResultWrapper.Success)
                    close()
                }

                override fun onError(exception: MsalException) {
                    Timber.tag(TAG)
                        .e(exception, "MSAL account removal error for ${account.username}")
                    trySend(SignOutResultWrapper.Error(exception))
                    close()
                }
            })
    }.flowOn(ioDispatcher)


    fun getAccount(accountManagerName: String): Flow<ManagedMicrosoftAccount?> =
        flow { // accountManagerName is IAccount.id
            Timber.tag(TAG).d("getAccount called for ID: $accountManagerName")
            val currentApp = mMultipleAccountApp
            if (currentApp == null || !_isMsalInitialized.value) {
                Timber.tag(TAG).w("getAccount: MSAL client not initialized.")
                emit(null)
                return@flow
        }
            try {
                // This call might involve IO, ensure it's on the correct dispatcher
                val iAccount =
                    withContext(ioDispatcher) { currentApp.getAccount(accountManagerName) }
                if (iAccount != null) {
                    emit(enrichIAccount(iAccount))
                } else {
                    emit(null)
                }
            } catch (e: MsalException) {
                Timber.tag(TAG).e(e, "Error getting account $accountManagerName from MSAL")
                emit(null)
            } catch (e: Exception) { // Catching InterruptedException explicitly if needed.
                Timber.tag(TAG).e(e, "Generic error getting account $accountManagerName from MSAL")
                emit(null)
        }
        } // No specific flowOn needed here as withContext handles dispatcher for getAccount


    fun getAccounts(): Flow<List<ManagedMicrosoftAccount>> = flow {
        Timber.tag(TAG).d("getAccounts called")
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("getAccounts: MSAL client not initialized.")
            emit(emptyList<ManagedMicrosoftAccount>())
            return@flow
        }
        try {
            // This call might involve IO
            val iAccounts = withContext(ioDispatcher) { currentApp.accounts }
            Timber.tag(TAG).d("MSAL returned ${iAccounts.size} accounts.")
            val managedAccounts = iAccounts.map { enrichIAccount(it) } // Enrich each account
            emit(managedAccounts)
        } catch (e: MsalException) {
            Timber.tag(TAG).e(e, "Error getting accounts from MSAL")
            emit(emptyList<ManagedMicrosoftAccount>())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Generic error getting accounts from MSAL")
            emit(emptyList<ManagedMicrosoftAccount>())
        }
    } // No specific flowOn needed here

    private suspend fun enrichIAccount(iAccount: IAccount): ManagedMicrosoftAccount {
        val persistedResult =
            tokenPersistenceService.getPersistedAccount(iAccount.id!!) // Assuming id is non-null for an active IAccount
        val (displayName, tenantId) = when (persistedResult) {
            is PersistenceResult.Success -> {
                val persistedData = persistedResult.data
                (persistedData.displayName ?: iAccount.username) to persistedData.tenantId
            }

            is PersistenceResult.Failure -> {
                Timber.tag(TAG)
                    .w("Failed to get persisted data for ${iAccount.id} during enrichment: ${persistedResult.errorType} - ${persistedResult.message}. Using IAccount defaults.")
                (iAccount.username) to iAccount.tenantId // Fallback to IAccount data
            }
        }
        return ManagedMicrosoftAccount(
            iAccount = iAccount,
            displayName = displayName,
            tenantId = tenantId
        )
    }

    // --- Removed persistence methods that are now in MicrosoftTokenPersistenceService ---
    // - saveAccountInfoToAccountManager
    // - getPersistedAccountData
    // - getAllPersistedMicrosoftAccounts
    // - getPersistedAccessToken
    // - getPersistedIdToken
    // - deleteAccountFromAccountManager
    // - loadPersistedAccount (if it existed as a separate public method)
}
