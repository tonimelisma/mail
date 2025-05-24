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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.melisma.backend_microsoft.common.PersistenceErrorType
import net.melisma.backend_microsoft.model.ManagedMicrosoftAccount
import net.melisma.backend_microsoft.model.PersistedMicrosoftAccount
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
        // All required scopes for the application
        // Now sourced from MicrosoftScopeDefinitions
        val MICROSOFT_SCOPES = MicrosoftScopeDefinitions.RequiredScopes

        // MINIMUM_REQUIRED_SCOPES has been removed.
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
                    Timber.tag(TAG)
                        .d("Granted scopes from MSAL: ${authenticationResult.scope?.joinToString()}")

                    // Verify all requested scopes were granted
                    val requestedScopesSet = scopes.toSet()
                    val grantedScopesSet = authenticationResult.scope?.toSet() ?: emptySet()

                    if (!grantedScopesSet.containsAll(requestedScopesSet)) {
                        Timber.tag(TAG).w(
                            "signInInteractive: Not all requested scopes were granted. Requested: $requestedScopesSet, Granted: $grantedScopesSet"
                        )
                        trySend(
                            AuthenticationResultWrapper.Error(
                                MsalClientException(
                                    "declined_scopes", // Custom error code for declined/missing scopes
                                    "Not all requested permissions were granted by the user."
                                )
                            )
                        )
                        close()
                        return
                    }

                    Timber.tag(TAG).i("All requested scopes have been granted.")

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
                            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(
                                authenticationResult.account.id
                            ) // Set active account on successful save
                            val managedAccount = ManagedMicrosoftAccount.fromIAccount(
                                authenticationResult.account,
                                authenticationResult
                            )
                            trySend(
                                AuthenticationResultWrapper.Success(
                                    managedAccount,
                                    authenticationResult
                                )
                            )
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            val specificFailure =
                                saveResult as PersistenceResult.Failure<PersistenceErrorType>
                            Timber.tag(TAG).e(
                                specificFailure.cause,
                                "Failed to save account info for ${authenticationResult.account.username}: ${specificFailure.errorType}, Msg: ${specificFailure.message}"
                            )
                            // Even if persistence fails, the MSAL auth succeeded.
                            // We might want to convey this specific error.
                            // For now, let's treat MSAL success as overall success but log persistence error.
                            val managedAccount = ManagedMicrosoftAccount.fromIAccount(
                                authenticationResult.account,
                                authenticationResult
                            )
                            trySend(
                                AuthenticationResultWrapper.Success(
                                    managedAccount,
                                    authenticationResult
                                )
                            )
                        }
                    }
                }

                override fun onError(exception: MsalException) {
                    Timber.tag(TAG).w(exception, "signInInteractive: Error")
                    Timber.tag(TAG)
                        .w("MSAL error code: ${exception.errorCode}, message: ${exception.message}")
                    // Handle MsalDeclinedScopeException here if needed (custom logging or mapping)
                    trySend(AuthenticationResultWrapper.Error(exception))
                    close() // Close the flow without propagating the exception
                }

                override fun onCancel() {
                    Timber.tag(TAG).i("signInInteractive: Cancelled by user.")
                    trySend(AuthenticationResultWrapper.Cancelled)
                    close() // Close the flow
                }
            }).build()

        Timber.tag(TAG).d("signInInteractive: Calling currentApp.acquireToken with parameters.")
        currentApp.acquireToken(acquireTokenParameters)

        awaitClose {
            Timber.tag(TAG).d("signInInteractive: callbackFlow awaitClose called.")
            // MSAL's acquireToken is activity-based and doesn't return a cancel handle here.
            // Cancellation is typically user-driven through the UI or back press, handled by onCancel/onError.
        }
    }.flowOn(ioDispatcher) // Initial check and parameter building can be on IO, MSAL calls back on main.

    // Reverted to suspend fun using suspendCancellableCoroutine as per original structure
    suspend fun acquireTokenSilent(
        account: IAccount,
        scopes: List<String>
    ): AcquireTokenResult = withContext(ioDispatcher) {
        Timber.tag(TAG)
            .d(
                "acquireTokenSilent: called for account: ${account.username}, ID: ${
                    account.id?.take(5)
                }..., scopes: $scopes"
            )
        val currentApp = mMultipleAccountApp

        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("acquireTokenSilent: MSAL client not initialized.")
            return@withContext AcquireTokenResult.Error(
                MsalClientException(
                    MsalClientException.INVALID_PARAMETER,
                    "MSAL client not initialized."
                )
            )
        }

        return@withContext try {
            val authResult = suspendCancellableCoroutine<IAuthenticationResult> { continuation ->
                val silentAuthenticationCallback = object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        Timber.tag(TAG).i(
                            "acquireTokenSilent: Success. Account: ${authenticationResult.account.username}, ID: ${
                                authenticationResult.account.id?.take(5)
                            }..."
                        )
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(authenticationResult))
                        }
                    }

                    override fun onError(exception: MsalException) {
                        Timber.tag(TAG)
                            .w(exception, "acquireTokenSilent: Error for ${account.username}")
                        if (continuation.isActive) {
                            continuation.resumeWithException(exception)
                        }
                    }
                }

                val authority = currentApp.configuration.defaultAuthority.authorityURL.toString()
                Timber.tag(TAG).d("acquireTokenSilent: Using authority: $authority")

                val silentTokenParameters = AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(authority)
                    .withScopes(scopes)
                    .forceRefresh(false) // Set to true if you always want to bypass cache for this call
                    .withCallback(silentAuthenticationCallback)
                    .build()

                Timber.tag(TAG).d("acquireTokenSilent: Calling currentApp.acquireTokenSilentAsync.")
                currentApp.acquireTokenSilentAsync(silentTokenParameters)

                continuation.invokeOnCancellation { cause: Throwable? ->
                    Timber.tag(TAG)
                        .w(cause, "acquireTokenSilent for ${account.username} was cancelled.")
                    // MSAL's acquireTokenSilentAsync doesn't return a direct cancel handle.
                    // Cancellation here means the coroutine scope was cancelled.
                }
            }
            AcquireTokenResult.Success(authResult)
        } catch (e: MsalException) {
            if (e is MsalUiRequiredException) {
                Timber.tag(TAG)
                    .w("acquireTokenSilent: UI required for account: ${account.username}, Error: ${e.errorCode}")
                AcquireTokenResult.UiRequired
            } else {
                Timber.tag(TAG)
                    .e(
                        e,
                        "acquireTokenSilent: MsalException for account: ${account.username}, Error: ${e.errorCode}"
                    )
                AcquireTokenResult.Error(e)
            }
        } catch (e: Exception) {
            Timber.tag(TAG)
                .e(e, "acquireTokenSilent: Generic exception for account: ${account.username}")
            AcquireTokenResult.Error(
                MsalClientException(
                    MsalClientException.UNKNOWN_ERROR,
                    e.message,
                    e
                )
            )
        }
    }

    fun signOut(account: IAccount): Flow<SignOutResultWrapper> = callbackFlow {
        Timber.tag(TAG)
            .d("signOut: called for account: ${account.username}, ID: ${account.id?.take(5)}...")
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
                                accountManagerName = accountId,
                                removeAccountFromManager = true
                            )
                            if (clearResult is PersistenceResult.Failure<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val failure =
                                    clearResult as PersistenceResult.Failure<PersistenceErrorType>
                                Timber.tag(TAG).e(
                                    failure.cause,
                                    "Failed to clear orphaned account ${accountId} from persistence. Error: ${failure.errorType}, Msg: ${failure.message}"
                                )
                                trySend(
                                    SignOutResultWrapper.Error(
                                        MsalClientException(
                                            MsalClientException.UNKNOWN_ERROR,
                                            "Persistence clear failure: ${failure.message ?: failure.errorType.toString()}"
                                        )
                                    )
                                )
                            } else {
                                Timber.tag(TAG)
                                    .i("Account data cleared from persistence for ${account.username}")
                                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == accountId) {
                                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                                }
                            }
                        }
                    }
                    trySend(SignOutResultWrapper.Success)
                    close()
                }

                override fun onError(exception: MsalException) {
                    Timber.tag(TAG)
                        .e(exception, "MSAL account removal error for: ${account.username}")
                    // Also attempt to clear local persistence as a fallback
                    externalScope.launch(ioDispatcher) {
                        account.id?.let { accountId ->
                            val clearResult = tokenPersistenceService.clearAccountData(
                                accountManagerName = accountId,
                                removeAccountFromManager = true // Attempt full removal despite MSAL error
                            )
                            if (clearResult is PersistenceResult.Failure<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val failure =
                                    clearResult as PersistenceResult.Failure<PersistenceErrorType>
                                Timber.tag(TAG).e(
                                    failure.cause,
                                    "Failed to clear account ${accountId} from persistence after MSAL removal error. Error: ${failure.errorType}, Msg: ${failure.message}"
                                )
                                // The original MSAL error is more primary
                            } else {
                                Timber.tag(TAG)
                                    .i("Account data cleared from persistence for ${account.username} despite MSAL removal error.")
                                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == accountId) {
                                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                                }
                            }
                        }
                    }
                    trySend(SignOutResultWrapper.Error(exception))
                    close()
                }
            })
        awaitClose { Timber.tag(TAG).d("signOut callbackFlow for ${account.username} closed") }
    }.flowOn(ioDispatcher)

    suspend fun getManagedAccountsFromMSAL(): List<ManagedMicrosoftAccount> =
        withContext(ioDispatcher) {
            Timber.tag(TAG).d("getManagedAccountsFromMSAL: called")
            val currentApp = mMultipleAccountApp
            if (currentApp == null || !_isMsalInitialized.value) {
                Timber.tag(TAG).w("getManagedAccountsFromMSAL: MSAL client not initialized.")
                initializationException?.let { throw it } // Propagate init error if present
                throw MsalClientException(
                    MsalClientException.UNKNOWN_ERROR,
                    "MSAL not initialized in getManagedAccountsFromMSAL"
                )
        }
            try {
                val msalAccounts = currentApp.accounts
                Timber.tag(TAG)
                    .i("getManagedAccountsFromMSAL: Found ${msalAccounts.size} MSAL IAccount(s).")
                // Map IAccount to ManagedMicrosoftAccount. Pass null for authResult to fromIAccount,
                // which will attempt to use claims if IAccount has them, or fallback to username.
                val managedList = msalAccounts.map { msalIAccount ->
                    ManagedMicrosoftAccount.fromIAccount(msalIAccount, null)
                }
                Timber.tag(TAG)
                    .i("getManagedAccountsFromMSAL: Mapped to ${managedList.size} ManagedMicrosoftAccount(s).")
                return@withContext managedList
            } catch (e: MsalException) {
                Timber.tag(TAG)
                    .e(e, "getManagedAccountsFromMSAL: MsalException while retrieving accounts.")
                throw e // Re-throw to be handled by caller or higher-level try-catch
            } catch (e: Exception) {
                Timber.tag(TAG).e(
                    e,
                    "getManagedAccountsFromMSAL: Generic exception while retrieving accounts."
                )
                throw MsalClientException(MsalClientException.UNKNOWN_ERROR, e.message, e)
        }
        }

    suspend fun getAccount(accountId: String): IAccount? = withContext(ioDispatcher) {
        Timber.tag(TAG).d("getAccount: called for ID (substring): ${accountId.take(5)}...")
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("getAccount: MSAL client not initialized.")
            return@withContext null
        }
        return@withContext try {
            currentApp.getAccount(accountId)
        } catch (e: MsalException) {
            Timber.tag(TAG).e(e, "getAccount: MsalException for ID $accountId")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "getAccount: Generic exception for ID $accountId")
            null
        }
    }

    // Added functions to interact with MicrosoftTokenPersistenceService
    // These expose persistence results directly for repository layer if needed
    suspend fun getPersistedMicrosoftAccount(accountId: String): PersistenceResult<ManagedMicrosoftAccount> {
        return tokenPersistenceService.getPersistedAccount(accountId).let {
            when (it) {
                is PersistenceResult.Success -> {
                    val persisted = it.data
                    val msalAccount =
                        getAccount(persisted.msalAccountId) // Fetch IAccount for ManagedMicrosoftAccount
                    if (msalAccount != null) {
                        PersistenceResult.Success(
                            ManagedMicrosoftAccount.fromPersisted(
                                persisted,
                                msalAccount
                            )
                        )
                    } else {
                        Timber.w(
                            TAG,
                            "Could not find MSAL IAccount for persisted account ${persisted.msalAccountId}"
                        )
                        PersistenceResult.Failure(
                            PersistenceErrorType.ACCOUNT_NOT_FOUND,
                            "MSAL IAccount not found for persisted data for ID: ${persisted.msalAccountId}"
                        )
                    }
                }

                is PersistenceResult.Failure<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val failure = it as PersistenceResult.Failure<PersistenceErrorType>
                    PersistenceResult.Failure(failure.errorType, failure.message, failure.cause)
                }
            }
        }
    }

    suspend fun getAllPersistedMicrosoftAccounts(): PersistenceResult<List<ManagedMicrosoftAccount>> {
        return try {
            withContext(ioDispatcher) {
                val persistedAccounts: List<PersistedMicrosoftAccount> =
                    tokenPersistenceService.getAllPersistedAccounts()

                val managedAccounts = mutableListOf<ManagedMicrosoftAccount>()
                for (persisted in persistedAccounts) {
                    val msalAccount = getAccount(persisted.msalAccountId) // This is a suspend call
                    if (msalAccount != null) {
                        managedAccounts.add(
                            ManagedMicrosoftAccount.fromPersisted(
                                persisted,
                                msalAccount
                            )
                        )
                    } else {
                        Timber.w(
                            TAG,
                            "Could not find MSAL IAccount for persisted account ${persisted.msalAccountId} in getAll. Skipping."
                        )
                    }
                }
                PersistenceResult.Success(managedAccounts)
            }
        } catch (e: MsalException) {
            Timber.tag(TAG).e(e, "MsalException in getAllPersistedMicrosoftAccounts")
            PersistenceResult.Failure(
                PersistenceErrorType.MSAL_SDK_ERROR,
                e.message ?: "MSAL SDK error",
                e
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception in getAllPersistedMicrosoftAccounts")
            PersistenceResult.Failure(
                PersistenceErrorType.UNKNOWN_ERROR,
                e.message ?: "Unknown error",
                e
            )
        }
    }
}
