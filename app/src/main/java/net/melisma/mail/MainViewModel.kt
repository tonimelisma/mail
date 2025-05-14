// File: app/src/main/java/net/melisma/mail/MainViewModel.kt
package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.capabilities.GoogleAccountCapability
import net.melisma.data.repository.DefaultAccountRepository
import javax.inject.Inject


@Immutable
data class MainScreenState(
    val authState: AuthState = AuthState.Initializing,
    val accounts: List<Account> = emptyList(),
    val isLoadingAccountAction: Boolean = false,
    val foldersByAccountId: Map<String, FolderFetchState> = emptyMap(),
    val selectedFolderAccountId: String? = null,
    val selectedFolder: MailFolder? = null,
    val messageDataState: MessageDataState = MessageDataState.Initial,
    val toastMessage: String? = null,
    val pendingGoogleConsentAccountId: String? = null
) {
    val isAnyFolderLoading: Boolean
        get() = foldersByAccountId.values.any { it is FolderFetchState.Loading }
    val isMessageLoading: Boolean
        get() = messageDataState is MessageDataState.Loading
    val messages: List<Message>?
        get() = (messageDataState as? MessageDataState.Success)?.messages
    val messageError: String?
        get() = (messageDataState as? MessageDataState.Error)?.error
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val accountRepository: AccountRepository,
    private val folderRepository: FolderRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val TAG = "MainViewModel_AppAuth"
    private val TAG_DEBUG_DEFAULT_SELECT = "MailAppDebug"

    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    private val mailReadScopes = listOf("User.Read", "Mail.Read")
    private val gmailReadScopes = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "email",
        "profile"
    )

    private val _appAuthIntentToLaunch = MutableStateFlow<Intent?>(null)
    val appAuthIntentToLaunch: StateFlow<Intent?> = _appAuthIntentToLaunch.asStateFlow()

    private val _googleConsentIntentSender = MutableStateFlow<IntentSender?>(null)
    val googleConsentIntentSender: StateFlow<IntentSender?> =
        _googleConsentIntentSender.asStateFlow()
    private val googleAccountCapability = accountRepository as? GoogleAccountCapability


    init {
        Log.d(TAG, "ViewModel Initializing")
        observeAccountRepository()
        observeFolderRepository() // Restored body
        observeMessageRepository() // Restored body

        if (accountRepository is DefaultAccountRepository) {
            Log.d(
                TAG,
                "Setting up observation of appAuthAuthorizationIntent from DefaultAccountRepository"
            )
            accountRepository.appAuthAuthorizationIntent
                .onEach { intent ->
                    Log.d(TAG, "Received AppAuth Intent in ViewModel: ${intent?.action}")
                    _appAuthIntentToLaunch.value = intent
                }.launchIn(viewModelScope)
        } else {
            Log.w(
                TAG,
                "AccountRepository is not DefaultAccountRepository, cannot observe AppAuth intent directly."
            )
        }

        googleAccountCapability?.let { capability ->
            Log.d(
                TAG,
                "Setting up observation of legacy googleConsentIntent from GoogleAccountCapability"
            )
            capability.googleConsentIntent
                .onEach { intentSender ->
                    if (intentSender != null) {
                        Log.d(TAG, "Legacy Google consent IntentSender received. Signaling UI.")
                        _googleConsentIntentSender.value = intentSender
                    }
                }.launchIn(viewModelScope)
        } ?: run {
            Log.d(
                TAG,
                "GoogleAccountCapability not available from AccountRepository (legacy consent)."
            )
        }

        viewModelScope.launch {
            val initialAccounts = accountRepository.accounts.value
            Log.d(TAG, "Initializing FolderRepository with ${initialAccounts.size} accounts.")
            folderRepository.manageObservedAccounts(initialAccounts)
        }
    }

    private fun observeAccountRepository() {
        Log.d(TAG, "MainViewModel: observeAccountRepository() - Setting up flows")
        accountRepository.authState
            .onEach { newAuthState ->
                Log.d(TAG, "AccountRepo AuthState Changed: $newAuthState")
                val wasInitialized = _uiState.value.authState is AuthState.Initialized
                val isInitialized = newAuthState is AuthState.Initialized
                _uiState.update { currentState ->
                    val clearSelection = (!isInitialized && wasInitialized) ||
                            (newAuthState is AuthState.InitializationError) ||
                            (newAuthState is AuthState.Initializing && wasInitialized)
                    if (clearSelection) viewModelScope.launch {
                        messageRepository.setTargetFolder(
                            null,
                            null
                        )
                    }
                    currentState.copy(
                        authState = newAuthState,
                        selectedFolder = if (clearSelection) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (clearSelection) null else currentState.selectedFolderAccountId,
                        pendingGoogleConsentAccountId = if (clearSelection) null else currentState.pendingGoogleConsentAccountId
                    )
                }
            }.launchIn(viewModelScope)

        accountRepository.accounts
            .onEach { newAccountList ->
                Log.d(
                    TAG,
                    "AccountRepo Accounts Changed: ${newAccountList.size} accounts. Usernames: ${newAccountList.joinToString { it.username }}"
                )
                val previousAccounts = _uiState.value.accounts
                val previousSelectedAccountId = _uiState.value.selectedFolderAccountId
                folderRepository.manageObservedAccounts(newAccountList)
                _uiState.update { currentState ->
                    val removedAccountIds =
                        previousAccounts.map { it.id } - newAccountList.map { it.id }.toSet()
                    val selectedAccountRemoved =
                        previousSelectedAccountId != null && previousSelectedAccountId in removedAccountIds
                    if (selectedAccountRemoved) viewModelScope.launch {
                        messageRepository.setTargetFolder(
                            null,
                            null
                        )
                    }
                    currentState.copy(
                        accounts = newAccountList,
                        selectedFolder = if (selectedAccountRemoved) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (selectedAccountRemoved) null else currentState.selectedFolderAccountId,
                        pendingGoogleConsentAccountId = if (selectedAccountRemoved && previousSelectedAccountId == currentState.pendingGoogleConsentAccountId) null else currentState.pendingGoogleConsentAccountId
                    )
                }
                if (_uiState.value.selectedFolder == null) {
                    selectDefaultFolderIfNeeded(_uiState.value)
                }
            }.launchIn(viewModelScope)

        accountRepository.isLoadingAccountAction
            .onEach { isLoading ->
                if (_uiState.value.isLoadingAccountAction != isLoading) {
                    Log.d(TAG, "AccountRepo isLoadingAccountAction changed: $isLoading")
                    _uiState.update { it.copy(isLoadingAccountAction = isLoading) }
                }
            }.launchIn(viewModelScope)

        accountRepository.accountActionMessage
            .onEach { message ->
                if (_uiState.value.toastMessage != message) {
                    Log.d(TAG, "AccountRepo accountActionMessage: $message")
                    _uiState.update { it.copy(toastMessage = message) }
                }
            }.launchIn(viewModelScope)
        Log.d(TAG, "Finished setting up AccountRepository observation flows")
    }

    // <<< RESTORED BODY for observeFolderRepository >>>
    private fun observeFolderRepository() {
        Log.d(TAG, "MainViewModel: observeFolderRepository() - Setting up flow")

        folderRepository.observeFoldersState()
            .onEach { folderStatesMap ->
                val previousFolderMap = _uiState.value.foldersByAccountId
                if (previousFolderMap != folderStatesMap) {
                    Log.d(
                        TAG,
                        "MainViewModel: FolderRepo State Changed: ${folderStatesMap.entries.joinToString { "${it.key}=${it.value::class.simpleName}" }}"
                    )
                    folderStatesMap.forEach { (accountId, state) ->
                        when (state) {
                            is FolderFetchState.Success -> {
                                Log.d(
                                    TAG,
                                    "MainViewModel: Account $accountId has ${state.folders.size} folders"
                                )
                                if (state.folders.isNotEmpty()) {
                                    Log.d(
                                        TAG,
                                        "MainViewModel: Folder names: ${state.folders.joinToString { it.displayName }}"
                                    )
                                }
                            }

                            is FolderFetchState.Loading -> Log.d(
                                TAG,
                                "MainViewModel: Account $accountId folders are loading"
                            )

                            is FolderFetchState.Error -> Log.e(
                                TAG,
                                "MainViewModel: Account $accountId folders error: ${state.error}"
                            )
                        }
                    }
                    _uiState.update { it.copy(foldersByAccountId = folderStatesMap) }
                    Log.d(TAG, "MainViewModel: UI state updated with new folder states")

                    val justLoaded = folderStatesMap.any { (id, state) ->
                        val wasLoading = previousFolderMap[id] is FolderFetchState.Loading
                        val isNotLoadingNow = state !is FolderFetchState.Loading
                        wasLoading && isNotLoadingNow.also {
                            if (it) Log.d(
                                TAG,
                                "MainViewModel: Folders for account $id just finished loading"
                            )
                        }
                    }

                    if (justLoaded && _uiState.value.selectedFolder == null) {
                        Log.d(
                            TAG,
                            "MainViewModel: Folders just loaded and no folder selected, attempting default selection"
                        )
                        selectDefaultFolderIfNeeded(_uiState.value)
                    }
                }
            }.launchIn(viewModelScope)
        Log.d(TAG, "MainViewModel: Finished setting up FolderRepository observation flow")
    }

    // <<< RESTORED BODY for observeMessageRepository >>>
    private fun observeMessageRepository() {
        Log.d(TAG, "MainViewModel: observeMessageRepository() - Setting up flow")

        messageRepository.messageDataState
            .onEach { newMessageState ->
                if (_uiState.value.messageDataState != newMessageState) {
                    Log.d(
                        TAG,
                        "MainViewModel: MessageRepo State Changed: ${newMessageState::class.simpleName}"
                    )
                    when (newMessageState) {
                        is MessageDataState.Success -> {
                            val messageCount = newMessageState.messages.size
                            Log.d(
                                TAG,
                                "ViewModel received Success with ${newMessageState.messages.size} messages. First 3 subjects: ${
                                    newMessageState.messages.take(3).map { it.subject }
                                }"
                            )
                            Log.d(TAG, "MainViewModel: Received ${messageCount} messages")
                            if (messageCount > 0) {
                                Log.d(
                                    TAG,
                                    "MainViewModel: First message subject: ${newMessageState.messages.first().subject}"
                                )
                                Log.d(
                                    TAG,
                                    "MainViewModel: Message IDs: ${
                                        newMessageState.messages.take(3).map { it.id }
                                    }"
                                )
                            }
                        }

                        is MessageDataState.Loading -> Log.d(
                            TAG,
                            "MainViewModel: Messages are loading"
                        )

                        is MessageDataState.Error -> Log.e(
                            TAG,
                            "MainViewModel: Message loading error: ${newMessageState.error}"
                        )

                        is MessageDataState.Initial -> Log.d(
                            TAG,
                            "MainViewModel: Message state is initial (no data loaded yet)"
                        )
                    }
                    _uiState.update { it.copy(messageDataState = newMessageState) }
                    Log.d(TAG, "MainViewModel: UI state updated with new message state")
                }
            }.launchIn(viewModelScope)
        Log.d(TAG, "MainViewModel: Finished setting up MessageRepository observation flow")
    }


    fun addAccount(activity: Activity) {
        Log.d(TAG, "addAccount() called for Microsoft account")
        viewModelScope.launch {
            accountRepository.addAccount(activity, mailReadScopes, "MS")
        }
    }

    fun addGoogleAccount(activity: Activity) {
        Log.i(TAG, "addGoogleAccount() called - AppAuth Flow")
        viewModelScope.launch {
            accountRepository.addAccount(activity, gmailReadScopes, "GOOGLE")
        }
    }

    fun removeAccount(activity: Activity, accountToRemove: Account?) {
        Log.d(TAG, "removeAccount() called for account: ${accountToRemove?.username}")
        if (accountToRemove == null) {
            tryEmitToastMessage("Cannot remove null account.")
            return
        }
        viewModelScope.launch {
            accountRepository.removeAccount(accountToRemove)
        }
    }

    fun clearAppAuthIntentToLaunch() {
        Log.d(TAG, "clearAppAuthIntentToLaunch() called")
        _appAuthIntentToLaunch.value = null
    }

    fun finalizeGoogleAppAuth(intent: Intent) {
        Log.i(TAG, "finalizeGoogleAppAuth called with intent data. Action: ${intent.action}")
        if (accountRepository is DefaultAccountRepository) {
            viewModelScope.launch {
                Log.d(TAG, "Calling repository.finalizeGoogleAccountSetupWithAppAuth")
                accountRepository.finalizeGoogleAccountSetupWithAppAuth(intent)
            }
        } else {
            Log.e(
                TAG,
                "Cannot finalize Google AppAuth: AccountRepository is not DefaultAccountRepository type."
            )
            tryEmitToastMessage("Internal error: Cannot complete Google account setup.")
            _uiState.update { it.copy(isLoadingAccountAction = false) }
        }
    }

    fun handleGoogleAppAuthError(errorMessage: String) {
        Log.e(TAG, "handleGoogleAppAuthError: $errorMessage")
        tryEmitToastMessage("Google Sign-In Error: $errorMessage")
        _uiState.update { it.copy(isLoadingAccountAction = false) }
        if (accountRepository is DefaultAccountRepository) {
            Log.d(TAG, "Calling resetPendingGoogleAccountState in repository due to AppAuth error.")
            accountRepository.resetPendingGoogleAccountState()
        }
    }

    fun isAccountPendingGoogleConsent(accountId: String?): Boolean {
        return _uiState.value.pendingGoogleConsentAccountId == accountId
    }

    fun finalizeGoogleScopeConsent(
        account: Account,
        intent: Intent?,
        activity: Activity
    ) {
        Log.d(TAG, "finalizeGoogleScopeConsent (legacy) called for account: ${account.username}")
        googleAccountCapability?.let {
            viewModelScope.launch {
                it.finalizeGoogleScopeConsent(account, intent, activity)
                _uiState.update { it.copy(pendingGoogleConsentAccountId = null) }
                _googleConsentIntentSender.value = null
            }
        } ?: run {
            Log.e(
                TAG,
                "Cannot finalize legacy Google consent: GoogleAccountCapability not available"
            )
            tryEmitToastMessage("Error: Cannot complete Google account setup (legacy path).")
            _uiState.update {
                it.copy(
                    pendingGoogleConsentAccountId = null,
                    isLoadingAccountAction = false
                )
            }
            _googleConsentIntentSender.value = null
        }
    }

    fun clearGoogleConsentIntentSender() {
        Log.d(TAG, "clearGoogleConsentIntentSender() called")
        _googleConsentIntentSender.value = null
        _uiState.update { it.copy(pendingGoogleConsentAccountId = null) }
    }

    fun toastMessageShown() {
        if (_uiState.value.toastMessage != null) {
            Log.d(TAG, "Toast message shown, clearing UI state message.")
            _uiState.update { it.copy(toastMessage = null) }
            accountRepository.clearAccountActionMessage()
        }
    }

    private fun tryEmitToastMessage(message: String?) {
        if (message != null && _uiState.value.toastMessage != message) {
            _uiState.update { it.copy(toastMessage = message) }
        }
    }

    private fun selectDefaultFolderIfNeeded(currentState: MainScreenState) {
        Log.d(TAG_DEBUG_DEFAULT_SELECT, "Checking if default folder selection is needed...")
        if (currentState.authState is AuthState.Initialized &&
            currentState.accounts.isNotEmpty() &&
            currentState.selectedFolder == null &&
            !currentState.isAnyFolderLoading
        ) {
            Log.d(TAG_DEBUG_DEFAULT_SELECT, "Attempting default folder selection...")
            var folderToSelect: MailFolder? = null
            var accountForFolder: Account? = null
            for (account in currentState.accounts) {
                val folderState = currentState.foldersByAccountId[account.id]
                if (folderState is FolderFetchState.Success) {
                    val inbox = folderState.folders.find { folder: MailFolder ->
                        folder.displayName.equals("Inbox", ignoreCase = true)
                    }
                    if (inbox != null) {
                        folderToSelect = inbox; accountForFolder = account
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Found Inbox in account ${account.username}"
                        )
                        break
                    }
                }
            }
            if (folderToSelect == null) {
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "Inbox not found, checking for first available folder."
                )
                for (account in currentState.accounts) {
                    val folderState = currentState.foldersByAccountId[account.id]
                    if (folderState is FolderFetchState.Success && folderState.folders.isNotEmpty()) {
                        folderToSelect = folderState.folders.firstOrNull(); accountForFolder =
                            account
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Found first folder '${folderToSelect?.displayName}' in account ${account.username}"
                        )
                        break
                    }
                }
            }
            if (folderToSelect != null && accountForFolder != null) {
                Log.i(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "Selecting default folder '${folderToSelect.displayName}' from account ${accountForFolder.username}"
                )
                selectFolder(folderToSelect, accountForFolder)
            } else {
                Log.w(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "No suitable default folder found after checking states."
                )
                viewModelScope.launch { messageRepository.setTargetFolder(null, null) }
            }
        } else {
            Log.d(TAG_DEBUG_DEFAULT_SELECT, "Default folder selection skipped.")
        }
    }

    fun selectFolder(folder: MailFolder, account: Account) {
        val accountId = account.id
        Log.i(
            TAG,
            "Folder selected: '${folder.displayName}' from account ${account.username} (ID: $accountId)"
        )
        if (folder.id == _uiState.value.selectedFolder?.id && accountId == _uiState.value.selectedFolderAccountId) {
            Log.d(TAG, "Folder ${folder.displayName} already selected. Skipping.")
            return
        }
        _uiState.update { it.copy(selectedFolder = folder, selectedFolderAccountId = accountId) }
        viewModelScope.launch { messageRepository.setTargetFolder(account, folder) }
    }

    fun refreshAllFolders(activity: Activity?) {
        Log.d(TAG, "Requesting refresh for ALL folders via FolderRepository...")
        viewModelScope.launch { folderRepository.refreshAllFolders(activity) }
    }

    fun refreshMessages(activity: Activity?) {
        val currentSelectedFolder = _uiState.value.selectedFolder
        val currentSelectedAccountId = _uiState.value.selectedFolderAccountId
        if (currentSelectedFolder == null || currentSelectedAccountId == null) {
            Log.w(TAG, "Refresh messages called but no folder/account selected.")
            tryEmitToastMessage("Select a folder first.")
            return
        }
        if (!isOnline()) {
            tryEmitToastMessage("No internet connection.")
            return
        }
        Log.d(
            TAG,
            "Requesting message refresh via MessageRepository for folder: ${currentSelectedFolder.id}"
        )
        viewModelScope.launch { messageRepository.refreshMessages(activity) }
    }

    private fun isOnline(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel Cleared.")
    }
}
