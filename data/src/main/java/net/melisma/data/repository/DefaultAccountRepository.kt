// File: data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleScopeAuthResult
import net.melisma.backend_google.auth.GoogleSignInResult
import net.melisma.backend_microsoft.auth.AddAccountResult
import net.melisma.backend_microsoft.auth.AuthStateListener
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.RemoveAccountResult
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.capabilities.GoogleAccountCapability
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val googleAuthManager: GoogleAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : AccountRepository, AuthStateListener, GoogleAccountCapability {

    private val TAG = "DefaultAccountRepo" // Logging TAG

    private val _authState = MutableStateFlow(determineInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _accounts = MutableStateFlow(mapToGenericAccounts(microsoftAuthManager.accounts))
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _isLoadingAccountAction = MutableStateFlow(false)
    override val isLoadingAccountAction: StateFlow<Boolean> = _isLoadingAccountAction.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val accountActionMessage: Flow<String?> = _accountActionMessage.asSharedFlow()

    init {
        Log.d(
            TAG,
            "Initializing DefaultAccountRepository. Injected errorMappers keys: ${errorMappers.keys}"
        )
        Log.d(TAG, "Registering as AuthStateListener for MicrosoftAuthManager")
        microsoftAuthManager.setAuthStateListener(this)
        syncStateFromManager()
    }

    override fun onAuthStateChanged(
        isInitialized: Boolean,
        msAccounts: List<IAccount>,
        error: MsalException?
    ) {
        Log.d(
            TAG,
            "onAuthStateChanged (MSAL): init=$isInitialized, msAccountCount=${msAccounts.size}, errorPresent=${error != null}"
        )
        _authState.value = determineAuthState(isInitialized, error)
        val newGenericAccounts = mapToGenericAccounts(msAccounts)
        Log.d(TAG, "onAuthStateChanged: Mapped to ${newGenericAccounts.size} generic accounts.")
        _accounts.value = newGenericAccounts
        _isLoadingAccountAction.value = false
    }

    override suspend fun addAccount(
        activity: Activity,
        scopes: List<String>,
        providerType: String
    ) {
        Log.d(TAG, "addAccount called. ProviderType: $providerType, Scopes: $scopes")
        when (providerType.uppercase()) {
            "MS" -> addMicrosoftAccount(activity, scopes)
            "GOOGLE" -> addGoogleAccount(activity, scopes)
            else -> {
                Log.w(TAG, "Unsupported provider type for addAccount: $providerType")
                tryEmitMessage("Unsupported account provider: $providerType")
            }
        }
    }

    // IntentSender for Google OAuth scope consent
    // Implementation for GoogleAccountCapability interface
    private val _googleConsentIntentInternal = MutableSharedFlow<IntentSender?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val googleConsentIntent: Flow<IntentSender?> =
        _googleConsentIntentInternal.asSharedFlow()

    // Implementation for GoogleAccountCapability interface
    override suspend fun finalizeGoogleScopeConsent(
        account: Account,
        intent: Intent?,
        activity: Activity
    ) {
        Log.d(TAG, "finalizeGoogleScopeConsent called for account: ${account.username}")
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(TAG, "finalizeGoogleScopeConsent: Google Error handler not found.")
            tryEmitMessage("Internal error: Google Error handler not found.")
            return
        }

        _isLoadingAccountAction.value = true
        val result = googleAuthManager.handleScopeConsentResult(intent)
        Log.d(TAG, "finalizeGoogleScopeConsent: Result from GoogleAuthManager: $result")

        when (result) {
            is GoogleScopeAuthResult.Success -> {
                Log.i(TAG, "Google scope consent successful. Access token received.")
                tryEmitMessage("Google account access granted successfully.")
                // Here you would typically store the access token securely
                // and mark the account as fully ready in your account store
            }

            is GoogleScopeAuthResult.Error -> {
                Log.e(TAG, "Error in Google scope consent: ${result.exception.message}")
                tryEmitMessage(
                    "Error completing Google account setup: ${
                        errorMapper.mapAuthExceptionToUserMessage(
                            result.exception
                        )
                    }"
                )
            }

            is GoogleScopeAuthResult.ConsentRequired -> {
                // This is unusual - we shouldn't get another consent required after processing a consent
                Log.w(
                    TAG,
                    "Additional consent required after finalizeGoogleScopeConsent. Requesting again."
                )
                externalScope.launch {
                    _googleConsentIntentInternal.emit(result.pendingIntent)
                }
                tryEmitMessage("Additional permissions needed for Gmail access.")
            }
        }
        _isLoadingAccountAction.value = false
    }

    override suspend fun addAccount(activity: Activity, scopes: List<String>) {
        Log.d(
            TAG,
            "addAccount (legacy overload) called, defaulting to MS provider. Scopes: $scopes"
        )
        addMicrosoftAccount(activity, scopes)
    }

    override suspend fun removeAccount(account: Account) {
        Log.d(
            TAG,
            "removeAccount called for account: ${account.username} (Provider: ${account.providerType})"
        )
        _isLoadingAccountAction.value = true
        when (account.providerType.uppercase()) {
            "MS" -> removeMicrosoftAccount(account)
            "GOOGLE" -> removeGoogleAccount(account)
            else -> {
                Log.w(
                    TAG,
                    "Cannot remove account with unsupported provider type: ${account.providerType}"
                )
                tryEmitMessage("Cannot remove account with unsupported provider type.")
                _isLoadingAccountAction.value = false
            }
        }
    }

    override fun clearAccountActionMessage() {
        Log.d(TAG, "clearAccountActionMessage called.")
        tryEmitMessage(null)
    }

    private fun getErrorMapperForProvider(providerType: String): ErrorMapperService? {
        val providerKey = providerType.uppercase()
        Log.d(
            TAG,
            "getErrorMapperForProvider: Attempting to get mapper for key '$providerKey'. Available keys: ${errorMappers.keys}"
        )
        val mapper = errorMappers[providerKey]
        if (mapper == null) {
            Log.e(TAG, "No ErrorMapperService found for provider type: $providerKey")
        }
        return mapper
    }

    private suspend fun addMicrosoftAccount(activity: Activity, scopes: List<String>) {
        Log.d(TAG, "addMicrosoftAccount: Attempting for scopes: $scopes")
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            Log.e(TAG, "addMicrosoftAccount: MS Error handler not found.")
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        val currentAuthState = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(TAG, "addMicrosoftAccount: Current MS Auth State before adding: $currentAuthState")
        if (currentAuthState !is AuthState.Initialized) {
            Log.w(TAG, "addMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }
        _isLoadingAccountAction.value = true
        Log.d(TAG, "addMicrosoftAccount: Calling MicrosoftAuthManager.addAccount...")
        microsoftAuthManager.addAccount(activity, scopes)
            .onEach { result ->
                Log.d(TAG, "addMicrosoftAccount: Result from MicrosoftAuthManager: $result")
                val message = when (result) {
                    is AddAccountResult.Success -> "Microsoft account added: ${result.account.username}"
                    is AddAccountResult.Error -> "Error adding Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"
                    is AddAccountResult.Cancelled -> "Microsoft account addition cancelled."
                    is AddAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                }
                tryEmitMessage(message)
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in addMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope)
    }

    private suspend fun removeMicrosoftAccount(account: Account) {
        Log.d(TAG, "removeMicrosoftAccount: Attempting for account: ${account.username}")
        val errorMapper = getErrorMapperForProvider("MS")
        if (errorMapper == null) {
            Log.e(TAG, "removeMicrosoftAccount: MS Error handler not found.")
            tryEmitMessage("Internal error: MS Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        val currentAuthState = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(
            TAG,
            "removeMicrosoftAccount: Current MS Auth State before removing: $currentAuthState"
        )
        if (currentAuthState !is AuthState.Initialized) {
            Log.w(TAG, "removeMicrosoftAccount called but MS auth state is not Initialized.")
            tryEmitMessage("Microsoft authentication system not ready.")
            _isLoadingAccountAction.value = false
            return
        }

        val msalAccountToRemove = microsoftAuthManager.accounts.find { it.id == account.id }
        if (msalAccountToRemove == null) {
            Log.e(
                TAG,
                "Microsoft account to remove (ID: ${account.id}, Username: ${account.username}) not found in MS manager."
            )
            tryEmitMessage("Microsoft account not found for removal.")
            _isLoadingAccountAction.value = false
            return
        }

        Log.d(
            TAG,
            "removeMicrosoftAccount: Calling MicrosoftAuthManager.removeAccount for MSAL account: ${msalAccountToRemove.username}"
        )
        microsoftAuthManager.removeAccount(msalAccountToRemove)
            .onEach { result ->
                Log.d(TAG, "removeMicrosoftAccount: Result from MicrosoftAuthManager: $result")
                val message = when (result) {
                    is RemoveAccountResult.Success -> "Microsoft account removed: ${msalAccountToRemove.username}"
                    is RemoveAccountResult.Error -> "Error removing Microsoft account: ${
                        errorMapper.mapAuthExceptionToUserMessage(result.exception)
                    }"
                    is RemoveAccountResult.NotInitialized -> "Microsoft authentication system not ready."
                    is RemoveAccountResult.AccountNotFound -> "Microsoft account to remove not found by MS auth manager."
                }
                tryEmitMessage(message)
                _isLoadingAccountAction.value = false
            }
            .catch { e ->
                Log.e(TAG, "Exception in removeMicrosoftAccount flow", e)
                tryEmitMessage(errorMapper.mapAuthExceptionToUserMessage(e))
                _isLoadingAccountAction.value = false
            }
            .launchIn(externalScope)
    }

    private fun tryEmitMessage(message: String?) {
        Log.d(TAG, "tryEmitMessage: '$message'")
        externalScope.launch {
            val emitted = _accountActionMessage.tryEmit(message)
            if (!emitted) {
                Log.w(TAG, "Failed to emit account action message (Buffer full?): $message")
            }
        }
    }

    private fun syncStateFromManager() {
        Log.d(TAG, "syncStateFromManager called.")
        onAuthStateChanged(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.accounts,
            microsoftAuthManager.initializationError
        )
    }

    private fun determineInitialAuthState(): AuthState {
        val state = determineAuthState(
            microsoftAuthManager.isInitialized,
            microsoftAuthManager.initializationError
        )
        Log.d(TAG, "determineInitialAuthState: $state")
        return state
    }

    private fun determineAuthState(isInitialized: Boolean, error: MsalException?): AuthState {
        val state = when {
            !isInitialized && error != null -> AuthState.InitializationError(error)
            !isInitialized && error == null -> AuthState.Initializing
            else -> AuthState.Initialized
        }
        Log.v(
            TAG,
            "determineAuthState: isInitialized=$isInitialized, errorPresent=${error != null} -> $state"
        )
        return state
    }

    private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
        Log.d(TAG, "mapToGenericAccounts: Mapping ${msalAccounts.size} MSAL accounts.")
        // We'll keep track of both MS and Google accounts in this repository
        // Start with Microsoft accounts
        val accounts = msalAccounts.map { msalAccount ->
            Account(
                id = msalAccount.id ?: "",
                username = msalAccount.username ?: "Unknown User",
                providerType = "MS"
            )
        }.toMutableList()

        // In a more complete implementation, we would also fetch and add Google accounts here
        // from a store maintained by this repository

        return accounts.also {
            Log.d(
                TAG,
                "mapToGenericAccounts: Resulting generic accounts: ${it.joinToString { acc -> acc.username }}"
            )
        }
    }

    private suspend fun addGoogleAccount(activity: Activity, scopes: List<String>) {
        Log.d(TAG, "addGoogleAccount: Attempting with scopes: $scopes")
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(TAG, "addGoogleAccount: Google Error handler not found.")
            tryEmitMessage("Internal error: Google Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        _isLoadingAccountAction.value = true
        Log.d(TAG, "addGoogleAccount: Calling GoogleAuthManager.signIn...")

        when (val signInResult =
            googleAuthManager.signIn(activity, filterByAuthorizedAccounts = false)) {
            is GoogleSignInResult.Success -> {
                Log.i(TAG, "Google sign-in successful.")
                val idTokenCredential = signInResult.idTokenCredential
                val newAccount = googleAuthManager.toGenericAccount(idTokenCredential)

                // We would store the new account here in a more complete implementation

                Log.d(
                    TAG,
                    "Google account added successfully, now requesting access token for scopes: $scopes"
                )

                // Gmail API scopes (at minimum read-only)
                val gmailScopes = listOf("https://www.googleapis.com/auth/gmail.readonly")
                // Request access to Gmail API
                when (val scopeResult =
                    googleAuthManager.requestAccessToken(activity, newAccount.id, gmailScopes)) {
                    is GoogleScopeAuthResult.Success -> {
                        Log.i(TAG, "Gmail access token acquired successfully.")
                        // Store the token for future use (in a secure way)
                        // Mark account as fully authorized
                        tryEmitMessage("Google account added: ${newAccount.username}")
                    }

                    is GoogleScopeAuthResult.ConsentRequired -> {
                        Log.i(
                            TAG,
                            "Gmail access requires user consent. Signaling UI to launch consent intent."
                        )
                        // Signal ViewModel/UI to launch the consent intent
                        externalScope.launch {
                            _googleConsentIntentInternal.emit(scopeResult.pendingIntent)
                        }
                        tryEmitMessage("Additional permissions needed for Gmail access.")
                    }

                    is GoogleScopeAuthResult.Error -> {
                        Log.e(
                            TAG,
                            "Error requesting Gmail access: ${scopeResult.exception.message}"
                        )
                        tryEmitMessage(
                            "Error setting up Gmail access: ${
                                errorMapper.mapAuthExceptionToUserMessage(
                                    scopeResult.exception
                                )
                            }"
                        )
                    }
                }
            }

            is GoogleSignInResult.Error -> {
                Log.e(TAG, "Google sign-in error: ${signInResult.exception.message}")
                tryEmitMessage(
                    "Error adding Google account: ${
                        errorMapper.mapAuthExceptionToUserMessage(
                            signInResult.exception
                        )
                    }"
                )
            }

            is GoogleSignInResult.Cancelled -> {
                Log.d(TAG, "Google sign-in was cancelled by the user.")
                tryEmitMessage("Google account addition cancelled.")
            }

            is GoogleSignInResult.NoCredentialsAvailable -> {
                Log.d(TAG, "No Google credentials available for sign-in.")
                tryEmitMessage("No Google accounts available. Please add a Google account to your device.")
            }
        }

        _isLoadingAccountAction.value = false
    }

    private suspend fun removeGoogleAccount(account: Account) {
        Log.d(TAG, "removeGoogleAccount: Attempting for account: ${account.username}")
        val errorMapper = getErrorMapperForProvider("GOOGLE")
        if (errorMapper == null) {
            Log.e(TAG, "removeGoogleAccount: Google Error handler not found.")
            tryEmitMessage("Internal error: Google Error handler not found.")
            _isLoadingAccountAction.value = false
            return
        }

        try {
            Log.d(TAG, "Calling googleAuthManager.signOut() to clear credential state.")
            googleAuthManager.signOut()

            // In a complete implementation, we would also remove the account from our local store

            tryEmitMessage("Google account removed: ${account.username}")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing Google account", e)
            tryEmitMessage(
                "Error removing Google account: ${
                    errorMapper.mapAuthExceptionToUserMessage(
                        e
                    )
                }"
            )
        } finally {
            _isLoadingAccountAction.value = false
        }
    }
}