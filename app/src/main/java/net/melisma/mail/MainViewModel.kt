// File: app/src/main/java/net/melisma/mail/MainViewModel.kt
package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.GenericAuthErrorType
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.preferences.MailViewModePreference
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.data.repository.DefaultAccountRepository
import timber.log.Timber
import javax.inject.Inject

// Define ViewMode enum before MainScreenState
// enum class ViewMode { THREADS, MESSAGES } // <-- REMOVE THIS
typealias ViewMode = MailViewModePreference // <-- ADD THIS TYPEALIAS

@Immutable
data class MainScreenState(
    val overallApplicationAuthState: OverallApplicationAuthState = OverallApplicationAuthState.UNKNOWN,
    val accounts: List<Account> = emptyList(),
    val isLoadingAccountAction: Boolean = false,
    val foldersByAccountId: Map<String, FolderFetchState> = emptyMap(),
    val selectedFolderAccountId: String? = null,
    val selectedFolder: MailFolder? = null,
    val messageDataState: MessageDataState = MessageDataState.Initial,
    val threadDataState: ThreadDataState = ThreadDataState.Initial,
    val currentViewMode: ViewMode = MailViewModePreference.THREADS, // <-- UPDATE DEFAULT
    val toastMessage: String? = null
) {
    val isAnyFolderLoading: Boolean
        get() = foldersByAccountId.values.any { it is FolderFetchState.Loading }
    val isMessageLoading: Boolean
        get() = messageDataState is MessageDataState.Loading
    val messages: List<Message>?
        get() = (messageDataState as? MessageDataState.Success)?.messages
    val messageError: String?
        get() = (messageDataState as? MessageDataState.Error)?.error
    val isThreadLoading: Boolean
        get() = threadDataState is ThreadDataState.Loading
    val threads: List<MailThread>?
        get() = (threadDataState as? ThreadDataState.Success)?.threads
    val threadError: String?
        get() = (threadDataState as? ThreadDataState.Error)?.error
}

// Define ViewMode enum, can be outside or nested if preferred by style guide
// enum class ViewMode { THREADS, MESSAGES } // This line is now effectively removed by moving it up

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val defaultAccountRepository: DefaultAccountRepository,
    private val folderRepository: FolderRepository,
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val TAG = "MainViewModel_AppAuth"
    private val TAG_DEBUG_DEFAULT_SELECT = "MailAppDebug"

    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    private val _pendingAuthIntent = MutableStateFlow<Intent?>(null)
    val pendingAuthIntent: StateFlow<Intent?> = _pendingAuthIntent.asStateFlow()

    init {
        Timber.tag(TAG).d("ViewModel Initializing")
        observeAccountRepositoryState()
        observeFolderRepository()
        observeMessageRepository()
        observeThreadRepository()
        observeUserPreferences()
    }

    private fun observeAccountRepositoryState() {
        Timber.tag(TAG).d("MainViewModel: observeAccountRepositoryState() - Setting up flows")

        defaultAccountRepository.overallApplicationAuthState
            .onEach { newOverallAuthState ->
                Timber.tag(TAG)
                    .d("AccountRepo OverallApplicationAuthState Changed: $newOverallAuthState")
                _uiState.update { currentState ->
                    val clearSelection =
                        newOverallAuthState == OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED ||
                                newOverallAuthState == OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION ||
                                newOverallAuthState == OverallApplicationAuthState.UNKNOWN
                    if (clearSelection && currentState.selectedFolder != null) {
                        Timber.tag(TAG)
                            .d("Clearing folder selection due to auth state change to $newOverallAuthState")
                        viewModelScope.launch {
                            messageRepository.setTargetFolder(null, null)
                            threadRepository.setTargetFolderForThreads(null, null, null)
                        }
                    }
                    currentState.copy(
                        overallApplicationAuthState = newOverallAuthState,
                        isLoadingAccountAction = when (newOverallAuthState) {
                            OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED,
                            OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED,
                            OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION,
                            OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION -> false

                            else -> currentState.isLoadingAccountAction
                        },
                        selectedFolder = if (clearSelection) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (clearSelection) null else currentState.selectedFolderAccountId
                    )
                }
            }.launchIn(viewModelScope)

        defaultAccountRepository.getAccounts()
            .onEach { newAccountList ->
                Timber.tag(TAG).d(
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
                    if (selectedAccountRemoved) {
                        Timber.tag(TAG)
                            .d("Selected account $previousSelectedAccountId was removed, clearing folder selection.")
                        viewModelScope.launch {
                            messageRepository.setTargetFolder(null, null)
                            threadRepository.setTargetFolderForThreads(null, null, null)
                        }
                    }
                    currentState.copy(
                        accounts = newAccountList,
                        selectedFolder = if (selectedAccountRemoved) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (selectedAccountRemoved) null else currentState.selectedFolderAccountId
                    )
                }
                if (_uiState.value.selectedFolder == null && newAccountList.isNotEmpty()) {
                    selectDefaultFolderIfNeeded(_uiState.value)
                }
            }.launchIn(viewModelScope)

        defaultAccountRepository.observeActionMessages()
            .onEach { message ->
                if (message != null) {
                    Timber.tag(TAG).d("AccountRepo accountActionMessage: $message")
                    _uiState.update { it.copy(toastMessage = message) }
                }
            }.launchIn(viewModelScope)
        Timber.tag(TAG).d("Finished setting up AccountRepository observation flows")
    }

    private fun observeFolderRepository() {
        Timber.tag(TAG).d("MainViewModel: observeFolderRepository() - Setting up flow")

        folderRepository.observeFoldersState()
            .onEach { folderStatesMap ->
                val previousFolderMap = _uiState.value.foldersByAccountId
                if (previousFolderMap != folderStatesMap) {
                    Timber.tag(TAG).d(
                        "MainViewModel: FolderRepo State Changed: ${folderStatesMap.entries.joinToString { "${it.key}=${it.value::class.simpleName}" }}"
                    )
                    folderStatesMap.forEach { (accountId, state) ->
                        when (state) {
                            is FolderFetchState.Success -> {
                                Timber.tag(TAG).d(
                                    "MainViewModel: Account $accountId has ${state.folders.size} folders"
                                )
                                if (state.folders.isNotEmpty()) {
                                    Timber.tag(TAG).d(
                                        "MainViewModel: Folder names: ${state.folders.joinToString { it.displayName }}"
                                    )
                                }
                            }

                            is FolderFetchState.Loading -> Timber.tag(TAG).d(
                                "MainViewModel: Account $accountId folders are loading"
                            )

                            is FolderFetchState.Error -> Timber.tag(TAG).e(
                                "MainViewModel: Account $accountId folders error: ${state.error}"
                            )
                        }
                    }
                    _uiState.update { it.copy(foldersByAccountId = folderStatesMap) }
                    Timber.tag(TAG).d("MainViewModel: UI state updated with new folder states")

                    val justLoaded = folderStatesMap.any { (id, state) ->
                        val wasLoading = previousFolderMap[id] is FolderFetchState.Loading
                        val isNotLoadingNow = state !is FolderFetchState.Loading
                        wasLoading && isNotLoadingNow.also {
                            if (it) Timber.tag(TAG).d(
                                "MainViewModel: Folders for account $id just finished loading"
                            )
                        }
                    }

                    if (justLoaded && _uiState.value.selectedFolder == null) {
                        Timber.tag(TAG).d(
                            "MainViewModel: Folders just loaded and no folder selected, attempting default selection"
                        )
                        selectDefaultFolderIfNeeded(_uiState.value)
                    }
                }
            }.launchIn(viewModelScope)
        Timber.tag(TAG).d("MainViewModel: Finished setting up FolderRepository observation flow")
    }

    private fun observeMessageRepository() {
        Timber.tag(TAG).d("MainViewModel: observeMessageRepository() - Setting up flow")

        messageRepository.messageDataState
            .onEach { newMessageState ->
                if (_uiState.value.messageDataState != newMessageState) {
                    Timber.tag(TAG).d(
                        "MainViewModel: MessageRepo State Changed: ${newMessageState::class.simpleName}"
                    )
                    when (newMessageState) {
                        is MessageDataState.Success -> {
                            val messageCount = newMessageState.messages.size
                            Timber.tag(TAG).d(
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

    private fun observeThreadRepository() {
        Log.d(TAG, "MainViewModel: observeThreadRepository() - Setting up flow")
        threadRepository.threadDataState
            .onEach { newThreadState ->
                if (_uiState.value.threadDataState != newThreadState) {
                    Log.d(
                        TAG,
                        "MainViewModel: ThreadRepo State Changed: ${newThreadState::class.simpleName}"
                    )
                    _uiState.update { it.copy(threadDataState = newThreadState) }
                    Log.d(TAG, "MainViewModel: UI state updated with new thread state.")
                }
            }.launchIn(viewModelScope)
        Log.d(TAG, "MainViewModel: Finished setting up ThreadRepository observation flow.")
    }

    private fun observeUserPreferences() {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collect { preferences ->
                Log.d(TAG, "User preference for ViewMode loaded: ${preferences.mailViewMode}")
                val currentUiStateViewMode = _uiState.value.currentViewMode
                if (currentUiStateViewMode != preferences.mailViewMode) {
                    _uiState.update { it.copy(currentViewMode = preferences.mailViewMode) }
                    _uiState.value.selectedFolder?.let { folder ->
                        _uiState.value.accounts.find { it.id == _uiState.value.selectedFolderAccountId }
                            ?.let { account ->
                                Log.d(
                                    TAG,
                                    "Preference changed, re-selecting folder ${folder.displayName} for new view mode ${preferences.mailViewMode}"
                                )
                                selectFolder(folder, account)
                            }
                    }
                }
            }
        }
    }

    fun startSignInProcess(activity: Activity, providerType: String, loginHint: String? = null) {
        Timber.tag(TAG).i("startSignInProcess called for provider: $providerType, loginHint: $loginHint")
        _uiState.update { it.copy(isLoadingAccountAction = true) }
        viewModelScope.launch {
            defaultAccountRepository.signIn(activity, loginHint, providerType)
                .collect { result ->
                    val isLoading = result is GenericAuthResult.UiActionRequired
                    _uiState.update { it.copy(isLoadingAccountAction = isLoading) }

                    when (result) {
                        is GenericAuthResult.Success -> {
                            val message = if (result.account.needsReauthentication) {
                                "Signed in as ${result.account.username} (Some permissions were declined. You may need to sign in again later.)"
                            } else {
                                "Signed in as ${result.account.username}"
                            }
                            _uiState.update { it.copy(toastMessage = message) }
                            Timber.tag(TAG).i("Sign-in success for ${result.account.username}")
                        }
                        is GenericAuthResult.UiActionRequired -> {
                            _pendingAuthIntent.value = result.intent
                            Timber.tag(TAG).i("Sign-in UI Action Required. Intent posted.")
                        }
                        is GenericAuthResult.Error -> {
                            val errorMessage = when (result.type) {
                                GenericAuthErrorType.MSAL_INTERACTIVE_AUTH_REQUIRED -> {
                                    Timber.tag(TAG)
                                        .w("Sign-in error (MSAL): Interactive sign-in required. Message: ${result.message}")
                                    "Sign-in failed: Please try signing in again. (Interactive action needed)"
                                }

                                GenericAuthErrorType.OPERATION_CANCELLED -> {
                                    Timber.tag(TAG).i("Sign-in cancelled by user")
                                    "Sign-in cancelled"
                                }

                                GenericAuthErrorType.AUTHENTICATION_FAILED -> {
                                    Timber.tag(TAG)
                                        .e("Sign-in error: ${result.message}, type: ${result.type}")
                                    result.message
                                }

                                else -> {
                                    Timber.tag(TAG)
                                        .e("Sign-in error: ${result.message}, type: ${result.type}")
                                    "Error: ${result.message}"
                                }
                            }
                            _uiState.update { ui ->
                                ui.copy(toastMessage = errorMessage)
                            }
                        }
                        is GenericAuthResult.Cancelled -> {
                            Timber.tag(TAG).i("Sign-in cancelled (GenericAuthResult.Cancelled)")
                            _uiState.update { it.copy(toastMessage = "Sign-in cancelled") }
                        }
                    }
                }
        }
    }

    fun authIntentLaunched() {
        _pendingAuthIntent.value = null
    }

    fun handleAuthenticationResult(
        providerType: String,
        resultCode: Int,
        data: Intent?
    ) {
        Timber.tag(TAG)
            .d("handleAuthenticationResult in ViewModel. Provider: $providerType, ResultCode: $resultCode, Data: ${data != null}")
        viewModelScope.launch {
            try {
                defaultAccountRepository.handleAuthenticationResult(
                    providerType,
                    resultCode,
                    data
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error calling repository handleAuthenticationResult for $providerType")
                _uiState.update {
                    it.copy(
                        isLoadingAccountAction = false,
                        toastMessage = "Error processing sign-in: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun signOutAndRemoveAccount(account: Account) {
        Timber.tag(TAG).i("signOutAndRemoveAccount called for account: ${account.username}")
        _uiState.update { it.copy(isLoadingAccountAction = true) }
        viewModelScope.launch {
            defaultAccountRepository.signOut(account)
                .collect { result ->
                    _uiState.update { it.copy(isLoadingAccountAction = false) }
                    when (result) {
                        is GenericSignOutResult.Success -> {
                            _uiState.update { it.copy(toastMessage = "Signed out ${account.username}") }
                            Timber.tag(TAG).i("Sign-out success for ${account.username}")
                        }
                        is GenericSignOutResult.Error -> {
                            _uiState.update { ui ->
                                ui.copy(toastMessage = "Sign-out error: ${result.message}")
                            }
                            Timber.tag(TAG).e("Sign-out error for ${account.username}: ${result.message}")
                        }
                    }
                }
        }
    }

    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun tryEmitToastMessage(message: String?) {
        if (message != null && _uiState.value.toastMessage != message) {
            _uiState.update { it.copy(toastMessage = message) }
        }
    }

    private fun selectDefaultFolderIfNeeded(currentState: MainScreenState) {
        Log.d(TAG_DEBUG_DEFAULT_SELECT, "Checking if default folder selection is needed...")
        if ((currentState.overallApplicationAuthState == OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED ||
             currentState.overallApplicationAuthState == OverallApplicationAuthState.PARTIAL_ACCOUNTS_NEED_REAUTHENTICATION) &&
            currentState.accounts.isNotEmpty() &&
            currentState.selectedFolder == null &&
            !currentState.isAnyFolderLoading
        ) {
            Log.d(TAG_DEBUG_DEFAULT_SELECT, "Attempting default folder selection...")
            var folderToSelect: MailFolder? = null
            var accountForFolder: Account? = null

            val accountToConsider = currentState.accounts.firstOrNull()

            if (accountToConsider != null) {
                val folderState = currentState.foldersByAccountId[accountToConsider.id]
                if (folderState is FolderFetchState.Success && folderState.folders.isNotEmpty()) {
                    folderToSelect = folderState.folders.find { folder ->
                        folder.isInboxFolder()
                    }
                    if (folderToSelect == null) {
                        folderToSelect = folderState.folders.first()
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Inbox not found in account ${accountToConsider.username}, defaulting to first folder '${folderToSelect.displayName}'."
                        )
                    } else {
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Found Inbox in account ${accountToConsider.username}."
                        )
                    }
                    accountForFolder = accountToConsider
                } else {
                    Log.d(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "No folders or not in Success state for account ${accountToConsider.username}. State: $folderState"
                    )
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
            }
        } else {
            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "Default folder selection skipped. AuthState: ${currentState.overallApplicationAuthState}, Accounts: ${currentState.accounts.size}, SelectedFolder: ${currentState.selectedFolder}, AnyFolderLoading: ${currentState.isAnyFolderLoading}"
            )
        }
    }

    fun selectFolder(folder: MailFolder, account: Account) {
        val accountId = account.id
        Log.i(
            TAG,
            "Folder selected: '${folder.displayName}' from account ${account.username} (ID: $accountId), Current ViewMode: ${_uiState.value.currentViewMode}"
        )

        val isSameFolderAndAccount =
            folder.id == _uiState.value.selectedFolder?.id && accountId == _uiState.value.selectedFolderAccountId

        if (isSameFolderAndAccount) {
            if (_uiState.value.currentViewMode == ViewMode.THREADS && _uiState.value.threadDataState !is ThreadDataState.Initial) {
                Log.d(
                    TAG,
                    "Folder ${folder.displayName} already selected for THREADS view and data exists/loading. Skipping full re-fetch."
                )
                _uiState.update {
                    it.copy(
                        selectedFolder = folder,
                        selectedFolderAccountId = accountId
                    )
                }
                return
            }
            if (_uiState.value.currentViewMode == ViewMode.MESSAGES && _uiState.value.messageDataState !is MessageDataState.Initial) {
                Log.d(
                    TAG,
                    "Folder ${folder.displayName} already selected for MESSAGES view and data exists/loading. Skipping full re-fetch."
                )
                _uiState.update {
                    it.copy(
                        selectedFolder = folder,
                        selectedFolderAccountId = accountId
                    )
                }
                return
            }
        }

        _uiState.update { it.copy(selectedFolder = folder, selectedFolderAccountId = accountId) }

        viewModelScope.launch {
            if (_uiState.value.currentViewMode == ViewMode.THREADS) {
                Log.d(
                    TAG,
                    "selectFolder: Current view mode is THREADS. Clearing message state, setting target for threads."
                )
                if (_uiState.value.messageDataState !is MessageDataState.Initial) {
                    messageRepository.setTargetFolder(null, null)
                }
                threadRepository.setTargetFolderForThreads(account, folder)
            } else {
                Log.d(
                    TAG,
                    "selectFolder: Current view mode is MESSAGES. Clearing thread state, setting target for messages."
                )
                if (_uiState.value.threadDataState !is ThreadDataState.Initial) {
                    threadRepository.setTargetFolderForThreads(
                        null,
                        null,
                        null
                    )
                }
                messageRepository.setTargetFolder(account, folder)
            }
        }
    }

    fun refreshAllFolders(activity: Activity?) {
        Log.d(TAG, "Requesting refresh for ALL folders via FolderRepository...")
        viewModelScope.launch { folderRepository.refreshAllFolders(activity) }
    }

    fun refreshCurrentView(activity: Activity?) {
        val currentSelectedFolder = _uiState.value.selectedFolder
        val currentSelectedAccountId = _uiState.value.selectedFolderAccountId
        if (currentSelectedFolder == null || currentSelectedAccountId == null) {
            Log.w(TAG, "Refresh current view called but no folder/account selected.")
            tryEmitToastMessage("Select a folder first.")
            return
        }
        if (!isOnline()) {
            tryEmitToastMessage("No internet connection.")
            return
        }

        val account = _uiState.value.accounts.find { it.id == currentSelectedAccountId }
        if (account == null) {
            Log.e(
                TAG,
                "Refresh current view: selected account ID $currentSelectedAccountId not found in accounts list."
            )
            tryEmitToastMessage("Error: Selected account not found.")
            return
        }

        if (_uiState.value.currentViewMode == ViewMode.THREADS) {
            Log.d(
                TAG,
                "Requesting thread refresh via ThreadRepository for folder: ${currentSelectedFolder.displayName}"
            )
            viewModelScope.launch {
                threadRepository.setTargetFolderForThreads(account, currentSelectedFolder, activity)
            }

        } else {
            Log.d(
                TAG,
                "Requesting message refresh via MessageRepository for folder: ${currentSelectedFolder.displayName}"
            )
            viewModelScope.launch {
                messageRepository.setTargetFolder(account, currentSelectedFolder)
            }
        }
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

    fun setViewModePreference(newMode: ViewMode) {
        Log.i(TAG, "setViewModePreference called with new mode: $newMode")
        if (_uiState.value.currentViewMode == newMode) {
            Log.d(TAG, "setViewModePreference: View mode is already $newMode. No change.")
            return
        }

        viewModelScope.launch {
            userPreferencesRepository.updateMailViewMode(newMode)
            _uiState.update { currentState ->
                currentState.copy(
                    messageDataState = if (newMode == ViewMode.THREADS && currentState.messageDataState !is MessageDataState.Initial) {
                        Log.d(
                            TAG,
                            "setViewModePreference: Switching to THREADS, resetting MessageDataState."
                        )
                        MessageDataState.Initial
                    } else {
                        currentState.messageDataState
                    },
                    threadDataState = if (newMode == ViewMode.MESSAGES && currentState.threadDataState !is ThreadDataState.Initial) {
                        Log.d(
                            TAG,
                            "setViewModePreference: Switching to MESSAGES, resetting ThreadDataState."
                        )
                        ThreadDataState.Initial
                    } else {
                        currentState.threadDataState
                    }
                )
            }
        }
    }

    fun getSelectedAccount(): Account? {
        val selectedAccountId = _uiState.value.selectedFolderAccountId
        if (selectedAccountId != null) {
            return _uiState.value.accounts.find { it.id == selectedAccountId }
        }
        return null
    }

    fun refreshFoldersForAccount(accountId: String, activity: Activity? = null) {
        Timber.tag(TAG).d("Explicitly refreshing folders for account: $accountId")
        _uiState.update { it.copy(isLoadingAccountAction = true) } // Indicate some loading
        viewModelScope.launch {
            try {
                folderRepository.refreshFoldersForAccount(accountId, activity)
                //isLoadingAccountAction will be reset by the folder state flow observation
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error refreshing folders for account $accountId")
                _uiState.update {
                    it.copy(
                        toastMessage = "Error refreshing folders for $accountId.",
                        isLoadingAccountAction = false
                    )
                }
            }
        }
    }

    fun refreshCurrentFolderMessages() {
        Log.w(TAG, "refreshCurrentFolderMessages() - Not yet fully implemented from merge.")
    }

    fun refreshCurrentFolderThreads() {
        Log.w(TAG, "refreshCurrentFolderThreads() - Not yet fully implemented from merge.")
    }

    fun toggleViewMode() {
        Log.w(TAG, "toggleViewMode() called - Deprecated, use setViewModePreference.")
        val newMode =
            if (_uiState.value.currentViewMode == ViewMode.THREADS) ViewMode.MESSAGES else ViewMode.THREADS
        setViewModePreference(newMode)
    }

    fun markMessageAsRead(messageId: String, folderId: String, accountId: String) {
        Log.w(TAG, "markMessageAsRead - Not yet fully implemented.")
    }

    fun markMessageAsUnread(messageId: String, folderId: String, accountId: String) {
        Log.w(TAG, "markMessageAsUnread - Not yet fully implemented.")
    }

    fun deleteMessage(messageId: String, folderId: String, accountId: String) {
        Log.w(TAG, "deleteMessage - Not yet fully implemented.")
    }

    fun moveMessage(
        messageId: String,
        currentFolderId: String,
        targetFolderId: String,
        accountId: String
    ) {
        Log.w(TAG, "moveMessage - Not yet fully implemented.")
    }

    fun markThreadAsRead(threadId: String, folderId: String, accountId: String) {
        Log.w(TAG, "markThreadAsRead - Not yet fully implemented.")
    }

    fun markThreadAsUnread(threadId: String, folderId: String, accountId: String) {
        Log.w(TAG, "markThreadAsUnread - Not yet fully implemented.")
    }

    fun deleteThread(threadId: String, folderId: String, accountId: String) {
        Log.w(TAG, "deleteThread - Not yet fully implemented.")
    }

    fun moveThread(
        threadId: String,
        currentFolderId: String,
        targetFolderId: String,
        accountId: String
    ) {
        Log.w(TAG, "moveThread - Not yet fully implemented.")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel Cleared.")
    }

    fun initiateSignIn(providerType: String, activity: Activity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAccountAction = true) }
            // The GMAIL_SCOPES_FOR_LOGIN are internal to DefaultAccountRepository,
            // so we pass null for scopes and let the repository decide.
            defaultAccountRepository.getAuthenticationIntentRequest(
                providerType = providerType,
                activity = activity,
                scopes = null // Let repository use default scopes
            ).collect { result: GenericAuthResult ->
                _uiState.update { it.copy(isLoadingAccountAction = false) }
                when (result) {
                    is GenericAuthResult.Success -> {
                        // Handled by account observation flow, maybe post a toast?
                        _uiState.update { it.copy(toastMessage = "Sign-in flow for ${providerType} initiated.") }
                    }

                    is GenericAuthResult.Error -> {
                        _uiState.update { it.copy(toastMessage = "Sign-in error: ${result.type.name} - ${result.message}") }
                    }

                    is GenericAuthResult.UiActionRequired -> {
                        _pendingAuthIntent.value = result.intent
                    }

                    is GenericAuthResult.Cancelled -> {
                        _uiState.update { it.copy(toastMessage = "Sign-in cancelled.") }
                    }
                }
            }
        }
    }

    fun consumePendingAuthIntent() {
        _pendingAuthIntent.value = null
    }

    fun retryFetchMessagesForCurrentFolder() {
        val selectedAccount = getSelectedAccount()
        val selectedFolder = _uiState.value.selectedFolder

        if (selectedAccount != null && selectedFolder != null) {
            Timber.tag(TAG)
                .d("Retrying fetch messages for folder ${selectedFolder.id} on account ${selectedAccount.id}")
            viewModelScope.launch {
                messageRepository.setTargetFolder(selectedAccount, selectedFolder)
            }
        } else {
            Timber.tag(TAG).w("Cannot retry message fetch: no folder or account selected.")
            _uiState.update { it.copy(toastMessage = "Please select a folder and account first.") }
        }
    }

    fun retryFetchThreadsForCurrentFolder() {
        val selectedAccount = getSelectedAccount()
        val selectedFolder = _uiState.value.selectedFolder

        if (selectedAccount != null && selectedFolder != null) {
            Timber.tag(TAG)
                .d("Retrying fetch threads for folder ${selectedFolder.id} on account ${selectedAccount.id}")
            viewModelScope.launch {
                threadRepository.setTargetFolderForThreads(selectedAccount, selectedFolder, null)
            }
        } else {
            Timber.tag(TAG).w("Cannot retry thread fetch: no folder or account selected.")
            _uiState.update { it.copy(toastMessage = "Please select a folder and account first.") }
        }
    }
}

fun MailFolder.isInboxFolder(): Boolean {
    return this.type == WellKnownFolderType.INBOX ||
            this.displayName.equals("Inbox", ignoreCase = true) ||
            this.displayName.equals("Caixa de Entrada", ignoreCase = true)
}

