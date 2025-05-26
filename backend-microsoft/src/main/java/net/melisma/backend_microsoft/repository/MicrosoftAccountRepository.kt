// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package net.melisma.backend_microsoft.repository

import android.app.Activity
import android.content.Intent
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.melisma.backend_microsoft.auth.AcquireTokenResult
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
import net.melisma.backend_microsoft.auth.AuthenticationResultWrapper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.SignOutResultWrapper
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.backend_microsoft.model.ManagedMicrosoftAccount
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.GenericAuthErrorType
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val externalScope: CoroutineScope,
    private val microsoftErrorMapper: MicrosoftErrorMapper,
    private val activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : AccountRepository {

    private val TAG = "MicrosoftAccountRepo"

    private val _msAccounts = MutableStateFlow<List<Account>>(emptyList())

    private val _overallApplicationAuthState =
        MutableStateFlow<OverallApplicationAuthState>(OverallApplicationAuthState.UNKNOWN)
    override val overallApplicationAuthState: StateFlow<OverallApplicationAuthState> =
        _overallApplicationAuthState.asStateFlow()

    private val _accountActionMessage = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        Timber.tag(TAG).i("MicrosoftAccountRepository Initializing.")
        observeMicrosoftAuthManagerChanges()
    }

    private fun observeMicrosoftAuthManagerChanges() {
        externalScope.launch {
            microsoftAuthManager.isMsalInitialized.collectLatest { isInitialized ->
                if (isInitialized) {
                    Timber.tag(TAG).d("MSAL is initialized. Fetching and processing MS accounts.")
                    try {
                        // Call the renamed suspend function that returns List<ManagedMicrosoftAccount>
                        val managedAccountsList: List<ManagedMicrosoftAccount> =
                            microsoftAuthManager.getManagedAccountsFromMSAL()

                        Timber.tag(TAG)
                            .d("Received ${managedAccountsList.size} ManagedMicrosoftAccounts from MicrosoftAuthManager.")
                        val genericAccounts = mutableListOf<Account>()

                        for (managedAccount: ManagedMicrosoftAccount in managedAccountsList) {
                            var needsReAuth = false
                            try {
                                val iAccountFromManaged = managedAccount.iAccount
                                // acquireTokenSilent is suspend, returns AcquireTokenResult directly (not a Flow)
                                val tokenResult: AcquireTokenResult =
                                    microsoftAuthManager.acquireTokenSilent(
                                        iAccountFromManaged,
                                        MicrosoftAuthManager.MICROSOFT_SCOPES
                                    )

                                when (tokenResult) {
                                    is AcquireTokenResult.UiRequired -> {
                                        needsReAuth = true
                                        Timber.tag(TAG)
                                            .w("Account ${iAccountFromManaged.username} needs re-authentication (UiRequired).")
                                    }

                                    is AcquireTokenResult.Error -> {
                                        Timber.tag(TAG)
                                            .w("Account ${iAccountFromManaged.username} silent token failed with error: ${tokenResult.exception.errorCode}")
                                        if (tokenResult.exception is MsalUiRequiredException || tokenResult.exception is MsalDeclinedScopeException) {
                                            needsReAuth =
                                                true // Specifically mark for re-auth if it's UI required type error or scope declined
                                        }
                                        // Otherwise, just a general error, don't necessarily force re-auth display
                                    }

                                    is AcquireTokenResult.Success -> {
                                        Timber.tag(TAG)
                                            .d("Silent token successful for ${iAccountFromManaged.username} during account observation.")
                                    }
                                    // Handle other AcquireTokenResult states if necessary (Cancelled, NotInitialized, NoAccountProvided)
                                    else -> {
                                        Timber.tag(TAG)
                                            .w("Account ${iAccountFromManaged.username} silent token had unhandled/unexpected result: $tokenResult")
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG)
                                    .e(
                                        e,
                                        "Exception during silent token check for ${managedAccount.iAccount.username}"
                                    )
                                needsReAuth =
                                    true // Assume re-auth needed if any exception during token check
                            }

                            val accountUsername =
                                managedAccount.displayName ?: managedAccount.iAccount.username
                                ?: "Unknown MS User"

                            genericAccounts.add(
                                Account(
                                    id = managedAccount.iAccount.id ?: UUID.randomUUID().toString(),
                                    username = accountUsername,
                                    providerType = Account.PROVIDER_TYPE_MS,
                                    needsReauthentication = needsReAuth
                                )
                            )
                        }
                        _msAccounts.value = genericAccounts
                        Timber.tag(TAG)
                            .d("Updated _msAccounts with ${genericAccounts.size} mapped accounts.")

                        updateOverallApplicationAuthState()

                        // If there's only one MS account and no active one, set it as active.
                        if (_msAccounts.value.size == 1 && activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == null) {
                            _msAccounts.value.firstOrNull()?.let { singleAccount ->
                                if (!singleAccount.needsReauthentication) { // Only set active if not needing re-auth immediately
                                    Timber.tag(TAG)
                                        .d("Setting single available MS account ${singleAccount.username} as active.")
                                    activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(
                                        singleAccount.id
                                    )
                                } else {
                                    Timber.tag(TAG)
                                        .d("Single MS account ${singleAccount.username} needs re-authentication, not setting as active immediately.")
                                }
                            }
                        }

                        val activeMsId =
                            activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue()
                        if (activeMsId != null && genericAccounts.none { it.id == activeMsId }) {
                            Timber.tag(TAG)
                                .w("Active MS account $activeMsId no longer found in MSAL accounts. Clearing active holder.")
                            activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(
                            e,
                            "Error in observeMicrosoftAuthManagerChanges fetching/processing accounts."
                        )
                        _msAccounts.value = emptyList() // Clear accounts on error
                        updateOverallApplicationAuthState() // Reflect empty state
                    }
                } else {
                    Timber.tag(TAG).d("MSAL is not initialized yet. Clearing MS accounts.")
                    _msAccounts.value = emptyList()
                    updateOverallApplicationAuthState()
                }
            }
        }
    }

    override fun getAccounts(): Flow<List<Account>> = _msAccounts.asStateFlow()

    override fun getActiveAccount(providerType: String): Flow<Account?> {
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) return flowOf(null)

        return activeMicrosoftAccountHolder.activeMicrosoftAccountId.flatMapLatest { activeId ->
            if (activeId == null) {
                flowOf(null)
            } else {
                _msAccounts.map { accounts -> accounts.find { it.id == activeId } }
            }
        }
    }

    override fun signIn(
        activity: Activity,
        loginHint: String?,
        providerType: String
    ): Flow<GenericAuthResult> {
        if (providerType != Account.PROVIDER_TYPE_MS) {
            return flowOf(
                GenericAuthResult.Error(
                    message = "signIn called for non-Microsoft provider in MicrosoftAccountRepository",
                    type = GenericAuthErrorType.INVALID_REQUEST
                )
            )
        }
        val scopes = MicrosoftAuthManager.MICROSOFT_SCOPES
        return microsoftAuthManager.signInInteractive(activity, scopes)
            .map { msalResult ->
                when (msalResult) {
                    is AuthenticationResultWrapper.Success -> {
                        val managedAccount = msalResult.managedAccount
                        val accountUsername =
                            managedAccount.displayName ?: managedAccount.iAccount.username
                            ?: "Unknown MS User"

                        val coreAccount = Account(
                            id = managedAccount.iAccount.id ?: UUID.randomUUID().toString(),
                            username = accountUsername,
                            providerType = Account.PROVIDER_TYPE_MS,
                            needsReauthentication = false
                        )
                        val currentAccounts =
                            _msAccounts.value.filterNot { it.id == coreAccount.id }.toMutableList()
                        currentAccounts.add(0, coreAccount)
                        _msAccounts.value = currentAccounts
                        updateOverallApplicationAuthState()

                        GenericAuthResult.Success(coreAccount)
                    }
                    is AuthenticationResultWrapper.Error -> {
                        Timber.tag(TAG).w("MSAL error during sign-in: ${msalResult.exception}")
                        val errorCode = msalResult.exception.errorCode
                        val errorMessage = msalResult.exception.message
                        Timber.tag(TAG).w("MSAL error code: $errorCode, message: $errorMessage")

                        // Handle specific error codes
                        when (msalResult.exception) {
                            is MsalDeclinedScopeException -> {
                                GenericAuthResult.Error(
                                    message = "Please accept all permissions, including offline access. This is required for the app to function properly.",
                                    type = GenericAuthErrorType.AUTHENTICATION_FAILED
                                )
                            }

                            else -> {
                                val mappedError =
                                    microsoftErrorMapper.mapExceptionToErrorDetails(msalResult.exception)
                                GenericAuthResult.Error(
                                    message = mappedError.message,
                                    type = mappedError.type
                                )
                            }
                        }
                    }

                    is AuthenticationResultWrapper.Cancelled -> {
                        GenericAuthResult.Error(
                            message = "Sign-in cancelled by user",
                            type = GenericAuthErrorType.OPERATION_CANCELLED
                        )
                    }
                }
            }
    }

    override suspend fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?
    ) {
        if (!providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) return
        Timber.tag(TAG)
            .i("handleAuthenticationResult for MS provider. MSAL handles its own ActivityResults. No action taken by repository.")
    }

    override fun signOut(account: Account): Flow<GenericSignOutResult> {
        if (account.providerType != Account.PROVIDER_TYPE_MS) {
            return flowOf(
                GenericSignOutResult.Error(
                    "Sign out called for non-MS account.",
                    GenericAuthErrorType.INVALID_REQUEST
                )
            )
        }
        Timber.tag(TAG)
            .i("Attempting to sign out Microsoft account: ${account.username} (ID: ${account.id})")

        return flow { // flow builder provides a coroutine scope
            try {
                // getManagedAccountsFromMSAL() is a suspend function
                val msalAccountsList: List<ManagedMicrosoftAccount> =
                    microsoftAuthManager.getManagedAccountsFromMSAL()
                val managedMsalAccount = msalAccountsList.find { it.iAccount.id == account.id }

                if (managedMsalAccount == null) {
                    Timber.tag(TAG)
                        .e("Could not find ManagedMicrosoftAccount in MSAL for generic Account ID: ${account.id} to sign out. Attempting to clear local state anyway.")
                    if (activeMicrosoftAccountHolder.getActiveMicrosoftAccountIdValue() == account.id) {
                        activeMicrosoftAccountHolder.setActiveMicrosoftAccountId(null)
                    }
                    _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                    updateOverallApplicationAuthState()
                    emit(
                        GenericSignOutResult.Error(
                            "MS Account not found in MSAL for sign out. Local state cleared.",
                            GenericAuthErrorType.ACCOUNT_NOT_FOUND
                        )
                    )
                    return@flow // Exit flow if account not found
                }

                // managedMsalAccount is guaranteed non-null here
                microsoftAuthManager.signOut(managedMsalAccount.iAccount).collect { signOutResult ->
                    when (signOutResult) {
                        is SignOutResultWrapper.Success -> {
                            _msAccounts.value = _msAccounts.value.filterNot { it.id == account.id }
                            updateOverallApplicationAuthState()
                            emit(GenericSignOutResult.Success)
                        }

                        is SignOutResultWrapper.Error -> {
                            val mappedDetails =
                                microsoftErrorMapper.mapExceptionToErrorDetails(signOutResult.exception)
                            emit(
                                GenericSignOutResult.Error(
                                    mappedDetails.message,
                                    mappedDetails.type,
                                    mappedDetails.providerSpecificErrorCode
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG)
                    .e(e, "Error during MSAL sign out for account ${account.id}: ${e.message}")
                emit(
                    GenericSignOutResult.Error(
                        "Sign out failed due to an unexpected error: ${e.message}",
                        GenericAuthErrorType.UNKNOWN_ERROR
                    )
                )
            }
        }.flowOn(ioDispatcher) // Ensure execution on IO dispatcher
    }

    override fun observeActionMessages(): Flow<String?> = _accountActionMessage.asSharedFlow()

    override fun clearActionMessage() {
        _accountActionMessage.tryEmit(null)
    }

    override suspend fun markAccountForReauthentication(accountId: String, providerType: String) {
        if (providerType != Account.PROVIDER_TYPE_MS) return
        val currentList = _msAccounts.value
        _msAccounts.value = currentList.map { account ->
            if (account.id == accountId) account.copy(needsReauthentication = true) else account
        }

        updateOverallApplicationAuthState()
        // Potentially call microsoftAuthManager.signOut(accountId, true) // if a soft sign-out/reauth is needed
        Timber.tag(TAG).i("Account $accountId marked for re-authentication.")
    }

    override suspend fun syncAccount(accountId: String): Result<Unit> {
        Timber.tag(TAG)
            .d("syncAccount called for accountId: $accountId in MicrosoftAccountRepository")
        // This would trigger specific Microsoft sync/refresh mechanisms if available.
        // For now, just a placeholder.
        return Result.success(Unit) // Or Result.failure(NotImplementedError("syncAccount for MS not implemented"))
    }

    override fun getAuthenticationIntentRequest(
        providerType: String,
        activity: Activity,
        scopes: List<String>? // Note: MSAL scopes are usually fixed in MicrosoftAuthManager
    ): Flow<GenericAuthResult> {
        Timber.tag(TAG)
            .d("getAuthenticationIntentRequest called for provider: $providerType. Redirecting to signIn.")
        // For Microsoft, the signIn method itself handles the interactive flow that might
        // be considered equivalent to getting an "intent request" in other auth libraries.
        // The scopes are typically managed by MicrosoftAuthManager.MICROSOFT_SCOPES.
        // We pass null for loginHint as getAuthenticationIntentRequest doesn't have it.
        // Also, the `scopes` parameter passed here is effectively ignored because signIn
        // uses MicrosoftAuthManager.MICROSOFT_SCOPES internally.
        return signIn(activity, null, providerType)
    }

    private fun updateOverallApplicationAuthState() {
        val accounts = _msAccounts.value
        val newState = when {
            accounts.isEmpty() -> OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
            accounts.all { it.needsReauthentication } -> OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION
            accounts.any { it.needsReauthentication } -> OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION
            else -> OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
        }

        if (_overallApplicationAuthState.value != newState) {
            _overallApplicationAuthState.value = newState
            Timber.tag(TAG).d("Overall auth state updated to: $newState")
        }
    }
}
