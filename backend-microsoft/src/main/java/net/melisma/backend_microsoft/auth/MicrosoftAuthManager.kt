// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/auth/MicrosoftAuthManager.kt
package net.melisma.backend_microsoft.auth

// import android.accounts.AccountManager // No longer directly used here
// import kotlinx.coroutines.Dispatchers // Not used directly
// import net.melisma.core_data.security.SecureEncryptionService // No longer directly used here

// New imports for PersistenceResult
// import net.melisma.backend_microsoft.common.PersistenceResult // Old import

// Model imports
// MSAL Logger imports
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ILoggerCallback
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.Logger
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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

    // Holds IAccount objects known to MSAL and our app
    private val _msalAccounts = MutableStateFlow<List<IAccount>>(emptyList())
    val msalAccounts: StateFlow<List<IAccount>> = _msalAccounts.asStateFlow()

    companion object {
        // All required scopes for the application
        // Now sourced from MicrosoftScopeDefinitions
        val MICROSOFT_SCOPES = MicrosoftScopeDefinitions.RequiredScopes

        // MINIMUM_REQUIRED_SCOPES has been removed.
    }

    init {
        Timber.tag(TAG).d("MicrosoftAuthManager created. Initialization will be triggered.")
        initializeMsalClient() // This will also load persisted accounts once MSAL app is created
        setupMsalLogger() // Call to setup MSAL logger
    }

    private fun setupMsalLogger() {
        Logger.getInstance().setExternalLogger(object : ILoggerCallback {
            override fun log(
                tag: String?,
                logLevel: Logger.LogLevel?,
                message: String?,
                containsPII: Boolean
            ) {
                // Use Timber for logging. You can customize the Timber tag or log level mapping.
                val msalTag = "MSAL_$tag"
                val logMessage = "ContainsPII: $containsPII - Message: $message"

                when (logLevel) {
                    Logger.LogLevel.ERROR -> Timber.tag(msalTag).e(logMessage)
                    Logger.LogLevel.WARNING -> Timber.tag(msalTag).w(logMessage)
                    Logger.LogLevel.INFO -> Timber.tag(msalTag).i(logMessage)
                    Logger.LogLevel.VERBOSE -> Timber.tag(msalTag).v(logMessage)
                    else -> Timber.tag(msalTag)
                        .d(logMessage) // Default to debug for null or other levels
                }
            }
        })
        // Enable PII logging - be careful with this in production apps.
        // Only enable PII if you are legally allowed to and you handle the data responsibly.
        Logger.getInstance().setEnablePII(true)
        Timber.tag(TAG).i("MSAL PII logging enabled. Ensure compliance with privacy regulations.")

        // Enable MSAL logs to also go to Logcat (in addition to the external logger)
        Logger.getInstance().setEnableLogcatLog(true)
        Timber.tag(TAG).i("MSAL Logcat logging enabled.")
    }

    private fun initializeMsalClient() {
        externalScope.launch { // This launch is important for the lifetime
            val deferred = CompletableDeferred<IMultipleAccountPublicClientApplication>()
            try {
                Timber.tag(TAG)
                    .d("Attempting to create IMultipleAccountPublicClientApplication with CompletableDeferred.")
                PublicClientApplication.createMultipleAccountPublicClientApplication(
                    context.applicationContext,
                    authConfigProvider.getMsalConfigResId(),
                    object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                        override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                            Timber.tag(TAG)
                                .i("MSAL IMultipleAccountPublicClientApplication created successfully.")
                            initializationException = null
                            deferred.complete(application)
                        }

                        override fun onError(exception: MsalException) {
                            Timber.tag(TAG).e(
                                exception,
                                "MSAL IMultipleAccountPublicClientApplication creation failed."
                            )
                            initializationException = exception
                            deferred.completeExceptionally(exception)
                        }
                    }
                )

                // Wait for the deferred to complete
                val application = deferred.await() // This is the suspending call
                mMultipleAccountApp = application // Assign the successfully created application
                _isMsalInitialized.value = true
                Timber.tag(TAG)
                    .i("MSAL client initialized successfully via CompletableDeferred. mMultipleAccountApp is set. Loading persisted accounts...")
                loadPersistedAccounts() // Load accounts after MSAL app is ready

            } catch (e: Exception) { // Catches MsalException from completeExceptionally or CancellationException from await
                Timber.tag(TAG)
                    .e(e, "MSAL initialization coroutine failed (deferred.await or other).")
                if (e is MsalException) {
                    initializationException = e
                } else if (e is kotlinx.coroutines.CancellationException) {
                    Timber.tag(TAG).w(e, "MSAL initialization was cancelled.")
                    // Potentially set a specific error or rethrow if needed, for now, generic
                    initializationException = MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "MSAL Initialization Cancelled",
                        e
                    )
                } else {
                    initializationException = MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "Generic exception during MSAL init: ${e.message}",
                        e
                    )
                }
                mMultipleAccountApp = null
                _isMsalInitialized.value = false
            }
        }
    }

    fun getInitializationError(): MsalException? = initializationException

    // New method to load accounts known from our persistence into MSAL
    private suspend fun loadPersistedAccounts() {
        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("loadPersistedAccounts: MSAL client not ready.")
            return
        }
        withContext(ioDispatcher) {
            Timber.tag(TAG)
                .d("loadPersistedAccounts: Loading accounts from TokenPersistenceService.")
            val persistedIdWrappers = tokenPersistenceService.getAllPersistedAccounts()
            val loadedMsalAccounts = mutableListOf<IAccount>()
            if (persistedIdWrappers.isEmpty()) {
                Timber.tag(TAG)
                    .i("loadPersistedAccounts: No accounts found in persistence service.")
            } else {
                Timber.tag(TAG)
                    .i("loadPersistedAccounts: Found ${persistedIdWrappers.size} persisted account identifiers. Attempting to load from MSAL.")
                for (persistedAccount in persistedIdWrappers) {
                    try {
                        // MSAL's getAccount requires a specific identifier format: homeAccountId.uid-utid
                        // IAccount.getId() might not be what MSAL's getAccount(String) expects across sessions.
                        // IAccount.getHomeAccountId().getIdentifier() is more reliable for getAccount API.
                        // For now, we are using persistedAccount.msalAccountId which should be IAccount.getId().
                        // If this fails to retrieve accounts, we need to ensure we persist and use homeAccountId.identifier.
                        // Let's assume persistedAccount.msalAccountId (which is IAccount.id) works for now.
                        // If not, the PersistedMicrosoftAccount model and saving logic need adjustment to store homeAccountId.identifier.

                        // Based on MSAL documentation, getAccount(String accountId) expects the MSAL account ID
                        // (often home_account_id + '.' + environment, or just home_account_id).
                        // IAccount.getId() is usually the local account ID for a specific tenant/policy.
                        // IAccount.getHomeAccountId() is the more stable identifier for the user.
                        // The current PersistedMicrosoftAccount stores `msalAccountId` which is `IAccount.id`.
                        // Let's try to load using that first. If it fails, we will need to adjust to use HomeAccountId.

                        var msalLoadedAccount: IAccount? = null
                        try {
                            msalLoadedAccount =
                                currentApp.getAccount(persistedAccount.msalAccountId)
                        } catch (e: MsalException) {
                            Timber.tag(TAG).e(
                                e,
                                "loadPersistedAccounts: MsalException during getAccount for ID ${persistedAccount.msalAccountId}. Trying to find matching username."
                            )
                        }


                        if (msalLoadedAccount != null) {
                            Timber.tag(TAG)
                                .i("loadPersistedAccounts: Successfully loaded IAccount from MSAL for ID: ${persistedAccount.msalAccountId} (User: ${msalLoadedAccount.username})")
                            loadedMsalAccounts.add(msalLoadedAccount)
                        } else {
                            // Fallback or alternative: try getting all accounts from MSAL and matching by username or other persisted fields if getAccount by ID fails.
                            // This indicates a potential mismatch in how accountId is persisted vs how MSAL expects it for retrieval.
                            // For now, log it. A robust solution might involve persisting homeAccountId.
                            Timber.tag(TAG)
                                .w("loadPersistedAccounts: Could not load IAccount from MSAL directly using persisted msalAccountId: ${persistedAccount.msalAccountId} (User: ${persistedAccount.username}). The account might have been removed from MSAL's cache or identifier mismatch.")
                            // As a fallback, let's try to find the account by iterating through all accounts MSAL knows about
                            // This is less efficient but can help if the ID used for getAccount(String) is problematic.
                            val allMsalAccounts = currentApp.accounts
                            val matchedByUsername =
                                allMsalAccounts.find { it.username == persistedAccount.username && it.tenantId == persistedAccount.tenantId }
                            if (matchedByUsername != null) {
                                Timber.tag(TAG)
                                    .i("loadPersistedAccounts: Fallback - Matched account by username and tenantId: ${matchedByUsername.username}")
                                loadedMsalAccounts.add(matchedByUsername)
                            } else {
                                Timber.tag(TAG)
                                    .w("loadPersistedAccounts: Fallback - Still could not find MSAL account for persisted username: ${persistedAccount.username}. This account may need re-authentication or is gone.")
                                // Optionally, we could remove this stale persisted entry here.
                                // tokenPersistenceService.clearAccountData(persistedAccount.accountManagerName, true)
                            }
                        }
                    } catch (e: Exception) { // Catch broader exceptions during account loading
                        Timber.tag(TAG).e(
                            e,
                            "loadPersistedAccounts: Generic error loading account with persisted ID ${persistedAccount.msalAccountId}."
                        )
                    }
                }
            }
            _msalAccounts.value = loadedMsalAccounts
            Timber.tag(TAG)
                .d("loadPersistedAccounts: Finished. ${_msalAccounts.value.size} IAccount(s) loaded into manager state.")
            // Update active account holder if necessary
            val activeId = activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue()
            if (activeId != null && _msalAccounts.value.none { it.id == activeId }) {
                Timber.tag(TAG)
                    .w("loadPersistedAccounts: Active account ID $activeId not found in loaded MSAL accounts. Clearing active account holder.")
                activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
            }
        }
    }

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

                    // Log refresh token availability (MSAL might not expose it directly)
                    // MSAL typically handles refresh tokens internally.
                    // We can check if the IAuthenticationResult contains any information related to it,
                    // or if a new refresh token was issued. However, direct access is unlikely.
                    // For now, logging the entire authenticationResult for inspection.
                    Timber.tag(TAG).d("AuthenticationResult details: $authenticationResult")
                    // You might need to inspect the authenticationResult object structure
                    // or MSAL documentation to find specific refresh token indicators if any are available.

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
                            displayNameFromClaims = displayNameFromClaims
                        )
                        if (saveResult is PersistenceResult.Success) {
                            Timber.tag(TAG)
                                .i("Account info (identifiers) saved via TokenPersistenceService for ${authenticationResult.account.username}")
                            // Add to our internal list of MSAL accounts
                            _msalAccounts.value = _msalAccounts.value.let { currentList ->
                                if (currentList.any { it.id == authenticationResult.account.id }) {
                                    currentList.map { if (it.id == authenticationResult.account.id) authenticationResult.account else it }
                                } else {
                                    currentList + authenticationResult.account
                                }
                            }
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
        // Add detailed logging before acquireToken call
        Timber.tag(TAG).d(
            "signInInteractive: Pre-acquireToken details - Activity: ${activity.localClassName}, isFinishing: ${activity.isFinishing}, isChangingConfigurations: ${activity.isChangingConfigurations}"
        )
        Timber.tag(TAG).d("signInInteractive: Scopes for acquireToken: $scopes")
        // It might be verbose, but logging parts of acquireTokenParameters could be useful if it had more configurable parts exposed
        // For now, activity state and scopes are the most relevant custom inputs here.

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
    ): AcquireTokenResult =
        withContext(ioDispatcher) { // Still good to ensure MSAL calls happen off main initially
        Timber.tag(TAG)
            .d(
                "acquireTokenSilent: called for account: ${account.username}, ID: ${
                    account.id?.take(
                        5
                    )
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

            val deferred = CompletableDeferred<IAuthenticationResult>()

            try {
                val silentAuthenticationCallback = object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        Timber.tag(TAG).i(
                            "acquireTokenSilent: Success. Account: ${authenticationResult.account.username}, ID: ${
                                authenticationResult.account.id?.take(5)
                            }..."
                        )
                        deferred.complete(authenticationResult)
                    }

                    override fun onError(exception: MsalException) {
                        Timber.tag(TAG)
                            .w(exception, "acquireTokenSilent: Error for ${account.username}")
                        deferred.completeExceptionally(exception)
                    }
                }

                val authority = currentApp.configuration.defaultAuthority.authorityURL.toString()
                Timber.tag(TAG).d("acquireTokenSilent: Using authority: $authority")

                val silentTokenParameters = AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(authority)
                    .withScopes(scopes)
                    .forceRefresh(false)
                    .withCallback(silentAuthenticationCallback)
                    .build()

                Timber.tag(TAG).d("acquireTokenSilent: Calling currentApp.acquireTokenSilentAsync.")
                currentApp.acquireTokenSilentAsync(silentTokenParameters)

                val authResult =
                    deferred.await() // Suspend until callback completes or an exception occurs
            AcquireTokenResult.Success(authResult)

            } catch (e: Exception) { // Catches MsalException or CancellationException
            if (e is MsalUiRequiredException) {
                Timber.tag(TAG)
                    .w("acquireTokenSilent: UI required for account: ${account.username}, Error: ${e.errorCode}")
                AcquireTokenResult.UiRequired
            } else if (e is MsalException) {
                Timber.tag(TAG).e(
                    e,
                    "acquireTokenSilent: MsalException for account: ${account.username}, Error: ${e.errorCode}"
                )
                AcquireTokenResult.Error(e)
            } else if (e is kotlinx.coroutines.CancellationException) {
                Timber.tag(TAG).w(e, "acquireTokenSilent for ${account.username} was cancelled.")
                // Convert CancellationException to an MsalClientException or a specific AcquireTokenResult state if needed
                AcquireTokenResult.Error(
                    MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "Acquire token silent cancelled",
                        e
                    )
                )
            } else {
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
    }

    // TEMPORARILY SIMPLIFIED TO ISOLATE BUILD ERRORS
    suspend fun signOut(accountId: String): SignOutResultWrapper = withContext(ioDispatcher) {
        Timber.tag(TAG)
            .d("signOut: Refactoring with CompletableDeferred for accountId: ${accountId.take(5)}...")

        val currentApp = mMultipleAccountApp
        if (currentApp == null || !_isMsalInitialized.value) {
            Timber.tag(TAG).w("signOut: MSAL client not initialized.")
            return@withContext SignOutResultWrapper.Error(
                MsalClientException(
                    MsalClientException.INVALID_PARAMETER,
                    "MSAL client not initialized."
                )
            )
        }

        val accountToRemove = getMsalAccount(accountId) // getMsalAccount is suspend

        if (accountToRemove == null) {
            Timber.tag(TAG)
                .w("signOut: Account with ID $accountId not found in MSAL. Attempting to clear local persistence only.")
            // Perform local cleanup directly as MSAL account is not found
            try {
                tokenPersistenceService.clearAccountData(accountId, removeAccountFromManager = true)
                _msalAccounts.value = _msalAccounts.value.filterNot { it.id == accountId }
                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == accountId) {
                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                }
                return@withContext SignOutResultWrapper.Success
            } catch (e: Exception) {
                Timber.tag(TAG)
                    .e(e, "Exception during local cleanup for non-MSAL account $accountId")
                return@withContext SignOutResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "Local cleanup failed for non-MSAL account",
                        e
                    )
                )
            }
        }

        val deferred = CompletableDeferred<SignOutResultWrapper>()

        try {
            currentApp.removeAccount(
                accountToRemove,
                object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                    override fun onRemoved() {
                        Timber.tag(TAG)
                            .i("signOut: Account ${accountToRemove.username} successfully removed from MSAL callback.")
                        externalScope.launch(ioDispatcher) { // Perform cleanup on IO dispatcher
                            try {
                                tokenPersistenceService.clearAccountData(
                                    accountId,
                                    removeAccountFromManager = true
                                )
                                _msalAccounts.value =
                                    _msalAccounts.value.filterNot { it.id == accountId }
                                if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == accountId) {
                                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                                }
                                deferred.complete(SignOutResultWrapper.Success)
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(
                                    e,
                                    "Exception during signOut onRemoved cleanup for ${accountToRemove.username}"
                                )
                                deferred.complete(
                                    SignOutResultWrapper.Error(
                                        MsalClientException(
                                            MsalClientException.UNKNOWN_ERROR,
                                            "Cleanup failed in onRemoved",
                                            e
                                        )
                                    )
                                )
                            }
                        }
                    }

                    override fun onError(exception: MsalException) {
                        Timber.tag(TAG).e(
                            exception,
                            "signOut: Error removing account ${accountToRemove.username} from MSAL callback."
                        )
                        deferred.complete(SignOutResultWrapper.Error(exception)) // Complete with the error from MSAL
                    }
                }
            )
            return@withContext deferred.await() // Suspend until the callback completes the deferred

        } catch (e: Exception) { // Catches MsalException or CancellationException from await
            Timber.tag(TAG).e(e, "Exception in signOut using CompletableDeferred for $accountId")
            if (e is kotlinx.coroutines.CancellationException) {
                return@withContext SignOutResultWrapper.Error(
                    MsalClientException(
                        MsalClientException.UNKNOWN_ERROR,
                        "Sign out cancelled",
                        e
                    )
                )
            }
            return@withContext SignOutResultWrapper.Error(
                MsalClientException(
                    MsalClientException.UNKNOWN_ERROR,
                    "Sign out failed: ${e.message}",
                    e
                )
            )
        }
    }

    // Function to get all *currently loaded and MSAL-confirmed* accounts
    fun getLoadedMsalAccounts(): List<IAccount> {
        return _msalAccounts.value
    }

    // Function to get a specific IAccount by its ID, from loaded accounts or by querying MSAL app
    // This is the primary way other services should get an IAccount object.
    suspend fun getMsalAccount(accountId: String): IAccount? = withContext(ioDispatcher) {
        val currentApp = mMultipleAccountApp ?: return@withContext null
        // Prefer account from our loaded list first, as it's confirmed by our logic
        var account = _msalAccounts.value.find { it.id == accountId }
        if (account != null) {
            Timber.tag(TAG)
                .d("getMsalAccount: Found account $accountId in loaded _msalAccounts cache.")
            return@withContext account
        }

        // If not in our cache, try to get it directly from MSAL application
        // This might be necessary if _msalAccounts is not perfectly synced or account was added via other means.
        Timber.tag(TAG)
            .d("getMsalAccount: Account $accountId not in _msalAccounts cache. Querying MSAL directly.")
        try {
            account = currentApp.getAccount(accountId)
            if (account != null) {
                Timber.tag(TAG)
                    .d("getMsalAccount: Successfully retrieved account $accountId directly from MSAL. Adding to _msalAccounts.")
                // Add to our list if found, to keep _msalAccounts somewhat up-to-date
                _msalAccounts.value = _msalAccounts.value.let { currentList ->
                    if (currentList.none { acc -> acc.id == accountId }) currentList + account!! else currentList
                }
            } else {
                Timber.tag(TAG)
                    .w("getMsalAccount: Account $accountId not found via MSAL getAccount direct call either.")
            }
            return@withContext account
        } catch (e: MsalException) {
            Timber.tag(TAG).e(
                e,
                "getMsalAccount: MsalException when trying to get account $accountId directly from MSAL."
            )
            return@withContext null
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e,
                "getMsalAccount: Generic Exception when trying to get account $accountId directly from MSAL."
            )
            return@withContext null
        }
    }

    // Method to find an IAccount using its username (less reliable than ID but can be a fallback)
    // This might be useful if only username is known.
    suspend fun findMsalAccountByUsername(username: String): IAccount? = withContext(ioDispatcher) {
        mMultipleAccountApp?.accounts?.find { it.username.equals(username, ignoreCase = true) }
    }

    // Added functions to interact with MicrosoftTokenPersistenceService
    // These expose persistence results directly for repository layer if needed
    suspend fun getPersistedMicrosoftAccount(accountId: String): PersistenceResult<ManagedMicrosoftAccount> {
        return tokenPersistenceService.getPersistedAccount(accountId).let {
            when (it) {
                is PersistenceResult.Success -> {
                    val persisted = it.data
                    val msalAccount =
                        getMsalAccount(persisted.msalAccountId) // Corrected: Changed getAccount to getMsalAccount (suspend)
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
                    val msalAccount =
                        getMsalAccount(persisted.msalAccountId) // Corrected: Changed getAccount to getMsalAccount (suspend)
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

    // Called by MicrosoftAccountRepository.handleAuthenticationResult
    // This is a placeholder or for specific scenarios where auth results aren't via AuthCallbackActivity
    suspend fun processPotentialAuthenticationResult(resultCode: Int, data: Intent?) =
        withContext(ioDispatcher) {
            Timber.tag(TAG)
                .d("processPotentialAuthenticationResult called with resultCode: $resultCode. Data present: ${data != null}")
            // MSAL's primary interactive flow result is typically handled by handleInteractiveSignInResult via AuthCallbackActivity.
            // This method is a general hook mandated by the AccountRepository interface for its implementers.
            // For this MSAL implementation, if an Intent result comes here, it's unexpected for the main sign-in flow.
            // It could be relevant for other, more advanced MSAL configurations or custom tabs scenarios that
            // might return results differently.
            if (data != null) {
                Timber.tag(TAG)
                    .i("processPotentialAuthenticationResult: Received data. Action: ${data.action}, Extras: ${data.extras?.keySet()}")
                // Example: Check for known MSAL redirect URI or specific data patterns if applicable
                // if (data.dataString?.startsWith(authConfigProvider.getRedirectUri()) == true) { ... }
            } else {
                Timber.tag(TAG)
                    .w("processPotentialAuthenticationResult: No data in intent. Result code: $resultCode")
            }
            // Since the main flow is handled via AuthCallbackActivity and internal continuations in MicrosoftAuthManager,
            // there's no specific action to take here for the current design other than logging.
            // If this method were to *complete* an ongoing Flow (like in AppAuth), it would need a way to signal that Flow.
        }
}
