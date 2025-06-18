// File: app/src/main/java/net/melisma/mail/MainViewModel.kt
package net.melisma.mail

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageSyncState
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.preferences.CacheSizePreference
import net.melisma.core_data.preferences.MailViewModePreference
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.preferences.InitialSyncDurationPreference
import net.melisma.core_data.preferences.DownloadPreference
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.OverallApplicationAuthState
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.data.repository.DefaultAccountRepository
import net.melisma.domain.actions.SyncFolderUseCase
import net.melisma.domain.account.SignOutAllMicrosoftAccountsUseCase
import net.melisma.core_data.repository.GenericSignOutAllResult
import net.melisma.data.sync.SyncController
import net.melisma.core_data.connectivity.ConnectivityHealthTracker
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
    val messageSyncState: MessageSyncState = MessageSyncState.Idle,
    val threadDataState: ThreadDataState = ThreadDataState.Initial,
    val currentViewMode: ViewMode = MailViewModePreference.THREADS,
    val currentCacheSizePreference: CacheSizePreference = CacheSizePreference.MB_500,
    val availableCacheSizes: List<CacheSizePreference> = CacheSizePreference.entries.toList(),
    val currentInitialSyncDurationPreference: InitialSyncDurationPreference = InitialSyncDurationPreference.defaultPreference(),
    val availableInitialSyncDurations: List<InitialSyncDurationPreference> = InitialSyncDurationPreference.entries.toList(),
    val currentBodyDownloadPreference: DownloadPreference = DownloadPreference.ALWAYS,
    val currentAttachmentDownloadPreference: DownloadPreference = DownloadPreference.ON_WIFI,
    val availableDownloadPreferences: List<DownloadPreference> = DownloadPreference.entries.toList(),
    val toastMessage: String? = null,
    val isUnreadFilterActive: Boolean = false,
    val isStarredFilterActive: Boolean = false,
    val signature: String = "",
    val throttleState: ConnectivityHealthTracker.ThrottleState = ConnectivityHealthTracker.ThrottleState.NORMAL
) {
    val isAnyFolderLoading: Boolean
        get() = foldersByAccountId.values.any { it is FolderFetchState.Loading }
    val isMessageLoading: Boolean
        get() = messageSyncState is MessageSyncState.Syncing &&
                messageSyncState.accountId == selectedFolderAccountId &&
                messageSyncState.folderId == selectedFolder?.id
    val messageError: String?
        get() = (messageSyncState as? MessageSyncState.SyncError)?.let {
            if (it.accountId == selectedFolderAccountId && it.folderId == selectedFolder?.id) {
                it.error
            } else {
                null
            }
        }
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
    private val userPreferencesRepository: UserPreferencesRepository,
    private val syncFolderUseCase: SyncFolderUseCase,
    private val signOutAllMicrosoftAccountsUseCase: SignOutAllMicrosoftAccountsUseCase,
    private val syncController: SyncController,
    private val connectivityHealthTracker: ConnectivityHealthTracker
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    private val _pendingAuthIntent = MutableStateFlow<Intent?>(null)
    val pendingAuthIntent: StateFlow<Intent?> = _pendingAuthIntent.asStateFlow()

    // Replay = 1 so that the Activity can still receive the permission request event
    // even if it starts collecting slightly after the emission (race during cold start).
    private val _requestPostNotificationsPermission = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val requestPostNotificationsPermission = _requestPostNotificationsPermission.asSharedFlow()

    private var pendingSignInProvider: String? = null
    private var pendingLoginHint: String? = null
    private var signInJob: Job? = null

    // Flow for PagingData<Message>
    private val _messagesPagerFlow = MutableStateFlow<Flow<PagingData<Message>>>(emptyFlow())
    val messagesPagerFlow: StateFlow<Flow<PagingData<Message>>> = _messagesPagerFlow.asStateFlow()

    private var currentFolderMessagesJob: Job? = null

    init {
        Timber.d("ViewModel Initializing")
        observeAccountRepositoryState()
        observeFolderRepository()
        observeMessageRepositorySyncState()
        observeThreadRepository()
        observeUserPreferences()
        observeConnectivityHealth()
        // observeSelectedFolderAndMessages() // This responsibility is now part of onFolderSelected / selection changes
    }

    private fun observeAccountRepositoryState() {
        Timber.d("MainViewModel: observeAccountRepositoryState() - Setting up flows")

        // Observe accountActionInProgress for global loading state -- REMOVING THIS as DefaultAccountRepository doesn't expose it directly.
        // defaultAccountRepository.accountActionInProgress
        //     .onEach { isInProgress ->
        //         Timber.d("AccountRepo accountActionInProgress changed: $isInProgress")
        //         _uiState.update { it.copy(isLoadingAccountAction = isInProgress) }
        //     }.launchIn(viewModelScope)

        defaultAccountRepository.overallApplicationAuthState
            .onEach { newOverallAuthState ->
                Timber.d("AccountRepo OverallApplicationAuthState Changed: $newOverallAuthState. Current selected folder: ${_uiState.value.selectedFolder?.displayName}")
                _uiState.update { currentState ->
                    val clearSelection =
                        newOverallAuthState == OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED ||
                                newOverallAuthState == OverallApplicationAuthState.ALL_ACCOUNTS_NEED_REAUTHENTICATION ||
                                newOverallAuthState == OverallApplicationAuthState.UNKNOWN

                    Timber.d("clearSelection based on auth state $newOverallAuthState is $clearSelection. Previously selected account: ${currentState.selectedFolderAccountId}, folder: ${currentState.selectedFolder?.displayName}")

                    if (clearSelection && currentState.selectedFolder != null) {
                        Timber.d("Clearing folder selection due to auth state change to $newOverallAuthState")
                        viewModelScope.launch {
                            Timber.d("Clearing target folder in messageRepository and threadRepository due to auth state change.")
                            messageRepository.setTargetFolder(null, null)
                            threadRepository.setTargetFolderForThreads(null, null, null)
                            _messagesPagerFlow.value = emptyFlow() // Clear pager
                            Timber.d("Cleared _messagesPagerFlow.")
                        }
                    }
                    currentState.copy(
                        overallApplicationAuthState = newOverallAuthState,
                        selectedFolder = if (clearSelection) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (clearSelection) null else currentState.selectedFolderAccountId,
                        messageSyncState = if (clearSelection) MessageSyncState.Idle else currentState.messageSyncState,
                        threadDataState = if (clearSelection) ThreadDataState.Initial else currentState.threadDataState
                    )
                }
            }.launchIn(viewModelScope)

        defaultAccountRepository.getAccounts()
            .onEach { newAccountList ->
                val previousAccountIds = _uiState.value.accounts.map { it.id }.toSet()
                val previousSelectedAccountId = _uiState.value.selectedFolderAccountId

                Timber.d(
                    "AccountRepo Accounts list changed. New: ${newAccountList.size} (${newAccountList.joinToString { it.emailAddress }}), Previous: ${previousAccountIds.size}. Selected Acc ID: $previousSelectedAccountId. Selected Folder: ${_uiState.value.selectedFolder?.displayName}"
                )

                val newAccountIds = newAccountList.map { it.id }.toSet()
                val removedAccountIds = previousAccountIds - newAccountIds

                var shouldClearSelectedFolder = false
                if (previousSelectedAccountId != null && previousSelectedAccountId in removedAccountIds) {
                    Timber.d("Selected account $previousSelectedAccountId was removed. Clearing folder selection and pager.")
                    shouldClearSelectedFolder = true
                }

                if (newAccountList.isEmpty() && previousAccountIds.isNotEmpty()) {
                    Timber.d("All accounts removed. Clearing folder selection and pager.")
                    shouldClearSelectedFolder = true
                }

                _uiState.update { currentState ->
                    if (shouldClearSelectedFolder) {
                        viewModelScope.launch { // Launch for repository calls
                            _messagesPagerFlow.value = emptyFlow()
                            Timber.d("Cleared _messagesPagerFlow due to account removal or all accounts gone.")
                        }
                        currentState.copy(
                            accounts = newAccountList,
                            selectedFolder = null,
                            selectedFolderAccountId = null,
                            messageSyncState = MessageSyncState.Idle,
                            threadDataState = ThreadDataState.Initial
                        )
                    } else {
                        currentState.copy(accounts = newAccountList)
                    }
                }

                // After accounts update, and if no folder is selected, try default selection
                if (_uiState.value.selectedFolder == null && newAccountList.isNotEmpty()) {
                    Timber.d("Accounts list updated to ${newAccountList.size}, and no folder selected. Checking for default folder.")
                    selectDefaultFolderIfNeeded()
                } else if (newAccountList.isEmpty() && _uiState.value.selectedFolder != null) {
                    // This case should be covered by shouldClearSelectedFolder update above.
                    Timber.d("Accounts list is now empty, but a folder was selected. State should have been cleared.")
                }

            }.launchIn(viewModelScope)

        defaultAccountRepository.observeActionMessages()
            .onEach { message ->
                if (message != null) {
                    Timber.d("AccountRepo accountActionMessage: $message")
                    _uiState.update { it.copy(toastMessage = message) }
                }
            }.launchIn(viewModelScope)
        Timber.d("Finished setting up AccountRepository observation flows")
    }

    private fun observeFolderRepository() {
        Timber.d("MainViewModel: observeFolderRepository() - Setting up flow")

        folderRepository.observeFoldersState()
            .onEach { folderStatesMap ->
                val previousFolderMap = _uiState.value.foldersByAccountId
                // Basic logging for change detection
                if (previousFolderMap.keys != folderStatesMap.keys ||
                    folderStatesMap.any { (key, newState) -> previousFolderMap[key] != newState }
                ) {
                    Timber.d(
                        "MainViewModel: FolderRepo State Changed. New map: ${folderStatesMap.entries.joinToString { "${it.key}=${it.value::class.simpleName}" }}"
                    )
                }

                _uiState.update { it.copy(foldersByAccountId = folderStatesMap) }
                // Timber.d("MainViewModel: UI state updated with new folder states") // Can be too noisy

                val anyFoldersJustLoadedForAnyAccount = folderStatesMap.any { (id, newState) ->
                    val oldState = previousFolderMap[id]
                    (oldState is FolderFetchState.Loading || oldState == null) && newState is FolderFetchState.Success
                }

                if (anyFoldersJustLoadedForAnyAccount) {
                    Timber.d("Some folders just finished loading for at least one account.")
                }

                // Attempt default folder selection if:
                // 1. No folder is currently selected.
                // 2. There are accounts.
                // 3. No folders are globally in a loading state (debounce against rapid changes).
                // 4. At least one account has successfully loaded some folders.
                if (_uiState.value.selectedFolder == null &&
                    _uiState.value.accounts.isNotEmpty() &&
                    !_uiState.value.isAnyFolderLoading && // isAnyFolderLoading checks current _uiState
                    folderStatesMap.values.any { it is FolderFetchState.Success && it.folders.isNotEmpty() }
                ) {
                    Timber.d(
                        "Conditions met for default folder selection check after folder states update. AnyJustLoaded: $anyFoldersJustLoadedForAnyAccount"
                    )
                    selectDefaultFolderIfNeeded()
                }
            }.launchIn(viewModelScope)
    }

    private fun observeMessageRepositorySyncState() {
        messageRepository.messageSyncState
            .onEach { syncState ->
                _uiState.update { it.copy(messageSyncState = syncState) }
            }.launchIn(viewModelScope)
    }

    private fun observeSelectedFolderAndMessages() {
        // This logic is now effectively handled by how messagesPagerFlow is updated
        // in onFolderSelected and when selectedFolder/accountId changes.
        // The direct collection of List<Message> is replaced by collecting PagingData from messagesPagerFlow in the UI.
        Timber.d("observeSelectedFolderAndMessages is deprecated; Pager flow is now updated on selection change.")
    }

    private fun observeThreadRepository() {
        Timber.d("MainViewModel: observeThreadRepository() - Setting up flow")
        threadRepository.threadDataState
            .onEach { newThreadState ->
                if (_uiState.value.threadDataState != newThreadState) {
                    Timber.d(
                        "MainViewModel: ThreadRepo State Changed: ${newThreadState::class.simpleName}"
                    )
                    _uiState.update { it.copy(threadDataState = newThreadState) }
                    Timber.d("MainViewModel: UI state updated with new thread state.")
                }
            }.launchIn(viewModelScope)
        Timber.d("MainViewModel: Finished setting up ThreadRepository observation flow.")
    }

    private fun observeUserPreferences() {
        userPreferencesRepository.userPreferencesFlow
            .onEach { prefs ->
                _uiState.update {
                    it.copy(
                        currentViewMode = prefs.mailViewMode,
                        currentCacheSizePreference = CacheSizePreference.fromBytes(prefs.cacheSizeLimitBytes),
                        currentInitialSyncDurationPreference = InitialSyncDurationPreference.fromDays(
                            prefs.initialSyncDurationDays
                        ),
                        currentBodyDownloadPreference = prefs.bodyDownloadPreference,
                        currentAttachmentDownloadPreference = prefs.attachmentDownloadPreference,
                        signature = prefs.signature
                    )
                }
            }.launchIn(viewModelScope)
    }

    private fun observeConnectivityHealth() {
        connectivityHealthTracker.state.onEach { throttleState ->
            _uiState.update { it.copy(throttleState = throttleState) }
        }.launchIn(viewModelScope)
    }

    fun onNotificationsPermissionResult(granted: Boolean, activity: Activity) {
        viewModelScope.launch {
            if (granted) {
                Timber.d("POST_NOTIFICATIONS permission granted by user.")
                pendingSignInProvider?.let { provider ->
                    doSignIn(activity, provider, pendingLoginHint)
                }
            } else {
                Timber.w("POST_NOTIFICATIONS permission denied by user. Initial sync notification will not be shown, but proceeding.")
                pendingSignInProvider?.let { provider ->
                    doSignIn(activity, provider, pendingLoginHint)
                }
            }
            pendingSignInProvider = null
            pendingLoginHint = null
        }
    }

    fun signIn(activity: Activity, providerType: String, loginHint: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                Timber.d("POST_NOTIFICATIONS permission already granted. Proceeding with sign-in.")
                doSignIn(activity, providerType, loginHint)
            } else {
                Timber.d("POST_NOTIFICATIONS permission not yet granted. Requesting it.")
                pendingSignInProvider = providerType
                pendingLoginHint = loginHint
                viewModelScope.launch {
                    _requestPostNotificationsPermission.emit(Unit)
                }
            }
        } else {
            Timber.d("OS version is below Android 13. No notification permission needed. Proceeding with sign-in.")
            doSignIn(activity, providerType, loginHint)
        }
    }

    private fun doSignIn(activity: Activity, providerType: String, loginHint: String?) {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            Timber.d("doSignIn called for provider: $providerType")
            if (!isOnline()) {
                _uiState.update { it.copy(toastMessage = "No network connection available.") }
                return@launch
            }

            _uiState.update { it.copy(isLoadingAccountAction = true) }

            defaultAccountRepository.signIn(activity, loginHint, providerType)
                .onEach { result ->
                    _uiState.update { currentState ->
                        when (result) {
                            is GenericAuthResult.Success -> {
                                Timber.i("Sign-in success for ${result.account.emailAddress}")
                                currentState.copy(
                                    isLoadingAccountAction = false,
                                    toastMessage = "Successfully signed in as ${result.account.emailAddress}"
                                )
                            }
                            is GenericAuthResult.Error -> {
                                Timber.e(result.details.cause, "Sign-in failure: ${result.details.message}")
                                currentState.copy(
                                    isLoadingAccountAction = false,
                                    toastMessage = "Sign-in failed: ${result.details.message}"
                                )
                            }
                            is GenericAuthResult.UiActionRequired -> {
                                Timber.d("Sign-in UI Action Required. Emitting intent.")
                                _pendingAuthIntent.value = result.intent
                                currentState.copy(isLoadingAccountAction = true)
                            }
                            is GenericAuthResult.Loading -> {
                                Timber.d("Sign-in process loading...")
                                currentState.copy(isLoadingAccountAction = true)
                            }
                        }
                    }
                }.launchIn(viewModelScope)
        }
    }

    fun handleAuthenticationResult(providerType: String, resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAccountAction = true) }
            Timber.d("Handling auth result for $providerType. ResultCode: $resultCode. Data is null: ${data == null}")
            defaultAccountRepository.handleAuthenticationResult(providerType, resultCode, data)
            // The result will be collected by the flow in doSignIn
        }
    }

    fun consumePendingAuthIntent() {
        _pendingAuthIntent.value = null
    }

    fun signOut(account: Account) {
        Timber.d("signOut called for account: ${account.emailAddress}")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAccountAction = true) } // Set true at start of action
            defaultAccountRepository.signOut(account)
                .collect { result -> // GenericSignOutResult
                    _uiState.update { currentState ->
                        when (result) {
                            is GenericSignOutResult.Loading -> {
                                Timber.d("Sign-out process loading for ${account.emailAddress}...")
                                currentState.copy(isLoadingAccountAction = true) // Remain true
                            }

                            is GenericSignOutResult.Success -> {
                                Timber.d("Sign-out successful for ${account.emailAddress}")
                                currentState.copy(
                                    isLoadingAccountAction = false, // Set false on terminal success
                                    toastMessage = "Signed out ${account.displayName ?: account.emailAddress}"
                                )
                            }

                            is GenericSignOutResult.Error -> {
                                Timber.w(
                                    result.details.cause,
                                    "Sign-out error for ${account.emailAddress}: ${result.details.message} (Code: ${result.details.code})"
                                )
                                currentState.copy(
                                    isLoadingAccountAction = false, // Set false on terminal error
                                    toastMessage = "Sign-out error for ${account.displayName ?: account.emailAddress}: ${result.details.message}"
                                )
                            }
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

    private fun selectDefaultFolderIfNeeded() {
        // Use the most current state from the ViewModel directly
        val currentState = _uiState.value

        Timber.d("selectDefaultFolderIfNeeded: Checking. Current selected: ${currentState.selectedFolder?.displayName}, Accounts: ${currentState.accounts.size}, Folders Loading: ${currentState.isAnyFolderLoading}")

        if (currentState.selectedFolder == null && currentState.accounts.isNotEmpty() && !currentState.isAnyFolderLoading) {
            Timber.d("Attempting default folder selection for user: ${currentState.accounts.firstOrNull()?.emailAddress}")

            val accountToUseForDefault = currentState.accounts.firstOrNull { acc ->
                val folderState = currentState.foldersByAccountId[acc.id]
                folderState is FolderFetchState.Success && folderState.folders.isNotEmpty()
            }

            if (accountToUseForDefault != null) {
                val folderState =
                    currentState.foldersByAccountId[accountToUseForDefault.id] as FolderFetchState.Success // Safe cast
                val inboxFolder = folderState.folders.firstOrNull {
                    it.type == WellKnownFolderType.INBOX || it.displayName.equals(
                        "Inbox",
                        ignoreCase = true
                    )
                }
                if (inboxFolder != null) {
                    Timber.i("Found Inbox in account ${accountToUseForDefault.emailAddress}. Selecting it.")
                    selectFolder(
                        inboxFolder,
                        accountToUseForDefault
                    ) // This will call the main selectFolder
                } else {
                    Timber.w("No Inbox folder found in account ${accountToUseForDefault.emailAddress}. Folders: ${folderState.folders.joinToString { it.displayName }}. Will not select a default automatically here.")
                    // Optionally, select the first folder if no Inbox:
                    // val firstFolder = folderState.folders.firstOrNull()
                    // if (firstFolder != null) {
                    //     Timber.i("No Inbox, selecting first folder by default: '${firstFolder.displayName}' for ${accountToUseForDefault.emailAddress}")
                    //     selectFolder(firstFolder, accountToUseForDefault)
                    // }
                }
            } else {
                Timber.d("No account found with successfully loaded non-empty folders to select a default from at this moment.")
            }
        } else {
            Timber.d("Default folder selection not needed or conditions not met. Selected: ${currentState.selectedFolder?.displayName}, Accounts: ${currentState.accounts.size}, IsAnyFolderLoading: ${currentState.isAnyFolderLoading}")
        }
    }

    fun selectFolder(folder: MailFolder, account: Account) {
        val newAccountId = account.id
        val newFolderId = folder.id
        Timber.i("selectFolder CALLED for: Folder '${folder.displayName}' (ID: $newFolderId), Account '${account.emailAddress}' (ID: $newAccountId). Current ViewMode: ${_uiState.value.currentViewMode}")

        val previousAccountId = _uiState.value.selectedFolderAccountId
        val previousFolderId = _uiState.value.selectedFolder?.id

        // Update UI state for selected folder immediately, regardless of whether it's "new"
        _uiState.update {
            it.copy(
                selectedFolder = folder,
                selectedFolderAccountId = newAccountId
                // Consider resetting specific sync states if the folder *actually* changes
                // messageSyncState = if (newFolderId != previousFolderId || newAccountId != previousAccountId) MessageSyncState.Idle else it.messageSyncState,
                // threadDataState = if (newFolderId != previousFolderId || newAccountId != previousAccountId) ThreadDataState.Initial else it.threadDataState
            )
        }
        Timber.d("selectFolder: UI state updated with folder '${folder.displayName}', accId '$newAccountId'.")


        if (newFolderId != previousFolderId || newAccountId != previousAccountId) {
            Timber.d("selectFolder: Change DETECTED. Previous: $previousAccountId/$previousFolderId -> New: $newAccountId/$newFolderId.")
            Timber.d("selectFolder: Initiating Pager update for MESSAGES view.")
            val newMessagesPagerFlow = messageRepository.getMessagesPager(
                accountId = newAccountId,
                folderId = newFolderId,
                pagingConfig = PagingConfig(
                    pageSize = 30,           // How many items to load at once from the PagingSource
                    prefetchDistance = 10,     // How far from the edge of loaded data to start loading more
                    enablePlaceholders = false,// Typically false for network/db sources
                    initialLoadSize = 60       // Larger initial load for better user experience
                )
            ).cachedIn(viewModelScope)
            _messagesPagerFlow.value = newMessagesPagerFlow // This triggers collection in the UI
            Timber.d("selectFolder: New _messagesPagerFlow created and assigned for $newAccountId/$newFolderId. UI will collect this and Paging will trigger RemoteMediator.load(REFRESH).")

            // The call to messageRepository.setTargetFolder is part of the refactoring for Alternative 1 (Paging-first).
            // Its responsibility for *triggering* a sync will be removed or made conditional.
            // It might still be used to inform the repository of the current context for other operations (e.g., manual refresh outside Paging).
            viewModelScope.launch {
                Timber.d("selectFolder: Calling messageRepository.setTargetFolder (for context) for $newAccountId/$newFolderId.")
                messageRepository.setTargetFolder(
                    account,
                    folder
                ) // For Alt1, this won't auto-sync.
                Timber.d("selectFolder: messageRepository.setTargetFolder call completed.")

                // Handle thread view target setting
                if (_uiState.value.currentViewMode == ViewMode.THREADS) {
                    Timber.d("selectFolder: Current view mode is THREADS. Setting target for threads repository.")
                    threadRepository.setTargetFolderForThreads(account, folder, null)
                } else { // MESSAGES view or others
                    Timber.d("selectFolder: Current view mode is MESSAGES. Ensuring thread target is cleared if not already initial.")
                    if (_uiState.value.threadDataState !is ThreadDataState.Initial) {
                        threadRepository.setTargetFolderForThreads(null, null, null)
                    }
                }
            }

            // Cancel previous folder's pager job if any
            currentFolderMessagesJob?.cancel()

            // Update the pager flow for the new folder
            _messagesPagerFlow.value = messageRepository.getMessagesPager(
                accountId = account.id,
                folderId = folder.id,
                // Define a PagingConfig - this should probably be a constant or configurable
                pagingConfig = PagingConfig(pageSize = 20, enablePlaceholders = false)
            ).cachedIn(viewModelScope) // cachedIn is important for surviving config changes

            // Kick off a background sync if this folder looks empty or has never been synced.
            viewModelScope.launch {
                Timber.i("selectFolder: Triggering syncMessagesForFolder for account ${account.emailAddress}, folder ${folder.displayName}")
                try {
                    messageRepository.syncMessagesForFolder(account.id, folder.id, null)
                } catch (e: Exception) {
                    Timber.e(e, "selectFolder: Failed to queue syncMessagesForFolder job")
                }
            }
        } else {
            Timber.d("selectFolder: SAME folder and account selected ($newAccountId/$newFolderId). No change to Pager. Any explicit refresh should be handled by UI (e.g. pull-to-refresh).")
            // If the same folder is selected, the Pager instance is the same.
            // If a refresh is needed (e.g., user pulled to refresh), the UI would call lazyMessageItems.refresh(),
            // which would trigger the existing Pager's RemoteMediator.
        }
    }

    fun refreshAllFolders(activity: Activity?) {
        Timber.d("Requesting refresh for ALL folders.")
        // Trigger folder refresh for every configured account.
        viewModelScope.launch {
            _uiState.value.accounts.forEach { acc ->
                Timber.d("Conceptual: syncEngine.syncFolders(${acc.id}) for account ${acc.emailAddress} as part of refreshAllFolders.")
                // folderRepository.requestFoldersSync(acc.id) // Ideal future method in repo that uses SyncEngine
            }
            // If the repository method `refreshAllFolders` is kept and internally calls SyncEngine for each account,
            // then the direct loop above might be redundant and the single call below is fine.
            // This depends on how `folderRepository.refreshAllFolders` is refactored.
            // For now, keeping the call to the repository, assuming it will be made SyncEngine-aware.
            folderRepository.refreshAllFolders(activity)
        }
    }

    fun refreshCurrentView(activity: Activity?) {
        val currentSelectedFolder = _uiState.value.selectedFolder
        val currentSelectedAccountId = _uiState.value.selectedFolderAccountId
        if (currentSelectedFolder == null || currentSelectedAccountId == null) {
            Timber.w("Refresh current view called but no folder/account selected.")
            tryEmitToastMessage("Select a folder first.")
            return
        }
        if (!isOnline()) {
            tryEmitToastMessage("No internet connection.")
            return
        }

        val account = _uiState.value.accounts.find { it.id == currentSelectedAccountId }
        if (account == null) {
            Timber.e("Refresh current view: selected account ID $currentSelectedAccountId not found in accounts list.")
            tryEmitToastMessage("Error: Selected account not found.")
            return
        }

        if (_uiState.value.currentViewMode == ViewMode.THREADS) {
            Timber.d("Requesting thread refresh via ThreadRepository for folder: ${currentSelectedFolder.displayName}")
            viewModelScope.launch {
                threadRepository.setTargetFolderForThreads(account, currentSelectedFolder, activity)
            }

        } else {
            Timber.d("Requesting message refresh via MessageRepository for folder: ${currentSelectedFolder.displayName}")
            viewModelScope.launch {
                messageRepository.setTargetFolder(account, currentSelectedFolder)
            }
        }
    }

    private fun isOnline(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities =
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return true
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return true
            }
        }
        return false
    }

    fun setViewModePreference(viewMode: MailViewModePreference) {
        viewModelScope.launch {
            userPreferencesRepository.updateMailViewMode(viewMode)
        }
    }

    fun setCacheSizePreference(preference: CacheSizePreference) {
        viewModelScope.launch {
            userPreferencesRepository.updateCacheSizeLimit(preference)
        }
    }

    fun setInitialSyncDurationPreference(preference: InitialSyncDurationPreference) {
        viewModelScope.launch {
            userPreferencesRepository.updateInitialSyncDuration(preference)
        }
    }

    fun setBodyDownloadPreference(preference: DownloadPreference) {
        viewModelScope.launch {
            userPreferencesRepository.updateBodyDownloadPreference(preference)
        }
    }

    fun setAttachmentDownloadPreference(preference: DownloadPreference) {
        viewModelScope.launch {
            userPreferencesRepository.updateAttachmentDownloadPreference(preference)
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
        Timber.d("Explicitly refreshing folders for account: $accountId")
        _uiState.update { it.copy(isLoadingAccountAction = true) } // This state might need to be more granular for folder loading
        // The folderRepository.refreshFoldersForAccount method itself will be updated to use SyncController.
        viewModelScope.launch {
            try {
                // Conceptual: syncEngine.syncFolders(accountId)
                Timber.d("Conceptual: syncEngine.syncFolders($accountId) for specific account refresh.")
                // Assuming folderRepository.refreshFoldersForAccount will be updated to use SyncEngine:
                folderRepository.refreshFoldersForAccount(accountId, activity)
            } catch (e: Exception) {
                Timber.e(e, "Error refreshing folders for account $accountId")
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
        Timber.w("refreshCurrentFolderMessages() - Not yet fully implemented from merge.")
    }

    fun refreshCurrentFolderThreads() {
        Timber.w("refreshCurrentFolderThreads() - Not yet fully implemented from merge.")
    }

    fun toggleViewMode() {
        Timber.w("toggleViewMode() called - Deprecated, use setViewModePreference.")
        val newMode =
            if (_uiState.value.currentViewMode == ViewMode.THREADS) ViewMode.MESSAGES else ViewMode.THREADS
        setViewModePreference(newMode)
    }

    fun markMessageAsRead(messageId: String, folderId: String, accountId: String) {
        Timber.w("markMessageAsRead - Not yet fully implemented.")
    }

    fun markMessageAsUnread(messageId: String, folderId: String, accountId: String) {
        Timber.w("markMessageAsUnread - Not yet fully implemented.")
    }

    fun deleteMessage(messageId: String, folderId: String, accountId: String) {
        Timber.w("deleteMessage - Not yet fully implemented.")
    }

    fun moveMessage(
        messageId: String,
        currentFolderId: String,
        targetFolderId: String,
        accountId: String
    ) {
        Timber.w("moveMessage - Not yet fully implemented.")
    }

    fun markThreadAsRead(threadId: String, folderId: String, accountId: String) {
        Timber.w("markThreadAsRead - Not yet fully implemented.")
    }

    fun markThreadAsUnread(threadId: String, folderId: String, accountId: String) {
        Timber.w("markThreadAsUnread - Not yet fully implemented.")
    }

    fun deleteThread(threadId: String, folderId: String, accountId: String) {
        Timber.w("deleteThread - Not yet fully implemented.")
    }

    fun moveThread(
        threadId: String,
        currentFolderId: String,
        targetFolderId: String,
        accountId: String
    ) {
        Timber.w("moveThread - Not yet fully implemented.")
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("ViewModel Cleared.")
    }

    fun initiateSignIn(providerType: String, activity: Activity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAccountAction = true) } // Set true at start of action

            if (providerType.equals(Account.PROVIDER_TYPE_MS, ignoreCase = true)) {
                Timber.d("Initiating MSAL direct sign-in flow for provider: $providerType")
                defaultAccountRepository.signIn(
                    activity = activity,
                    providerType = providerType,
                    loginHint = null // Add loginHint if available/needed for MSAL
                ).collect { result: GenericAuthResult ->
                    _uiState.update { currentState ->
                        when (result) {
                            is GenericAuthResult.Loading -> {
                                Timber.d("MSAL Sign-in loading...")
                                currentState.copy(isLoadingAccountAction = true)
                            }

                            is GenericAuthResult.Success -> {
                                Timber.i("MSAL Sign-in success for ${result.account.emailAddress}")
                                currentState.copy(
                                    isLoadingAccountAction = false,
                                    toastMessage = "Signed in as ${result.account.displayName ?: result.account.emailAddress}"
                                    // Potentially navigate or refresh accounts list
                                )
                            }

                            is GenericAuthResult.Error -> {
                                Timber.w(
                                    result.details.cause,
                                    "MSAL Sign-in error: ${result.details.message} (Code: ${result.details.code})"
                                )
                                currentState.copy(
                                    isLoadingAccountAction = false,
                                    toastMessage = "Sign-in error: ${result.details.message}${if (result.details.code != null) " (${result.details.code})" else ""}"
                                )
                            }

                            is GenericAuthResult.UiActionRequired -> {
                                // This case should NOT be hit for MSAL if MSAL handles its own UI.
                                // If it is, it means our understanding of DefaultAccountRepository's MSAL signIn is wrong.
                                Timber.e("MSAL Sign-in flow unexpectedly emitted UiActionRequired.")
                                currentState.copy(
                                    isLoadingAccountAction = false,
                                    toastMessage = "Unexpected sign-in state for Microsoft account."
                                )
                            }
                        }
                    }
                }
            } else { // For Google or other providers that use getAuthenticationIntentRequest
                Timber.d("Initiating sign-in via getAuthenticationIntentRequest for provider: $providerType")
                defaultAccountRepository.getAuthenticationIntentRequest(
                    providerType = providerType,
                    activity = activity,
                    scopes = null
                ).collect { result: GenericAuthResult ->
                    _uiState.update { currentState ->
                        when (result) {
                            is GenericAuthResult.Loading -> {
                                Timber.d("Get auth intent loading...")
                                currentState.copy(isLoadingAccountAction = true) // Remain true
                            }

                            is GenericAuthResult.Success -> {
                                Timber.i("Get auth intent success for $providerType - unexpected by default for this call.")
                                currentState.copy(
                                    isLoadingAccountAction = false,
                                    toastMessage = "Sign-in flow for ${providerType} initiated."
                                )
                            }

                            is GenericAuthResult.Error -> {
                                Timber.w(
                                    result.details.cause,
                                    "Get auth intent error: ${result.details.message} (Code: ${result.details.code})"
                                )
                                currentState.copy(
                                    isLoadingAccountAction = false, // Set false on terminal error
                                    toastMessage = "Sign-in error: ${result.details.message}${if (result.details.code != null) " (${result.details.code})" else ""}"
                                )
                            }

                            is GenericAuthResult.UiActionRequired -> {
                                Timber.d("Get auth intent UI Action Required. Emitting intent.")
                                _pendingAuthIntent.value = result.intent
                                currentState.copy(isLoadingAccountAction = true) // Remain true, awaiting UI action
                            }
                        }
                    }
                }
            }
        }
    }

    fun retryFetchMessagesForCurrentFolder() {
        val selectedAccount = getSelectedAccount()
        val selectedFolder = _uiState.value.selectedFolder

        if (selectedAccount != null && selectedFolder != null) {
            Timber.d("Retrying fetch messages for folder ${selectedFolder.id} on account ${selectedAccount.id}")
            viewModelScope.launch {
                messageRepository.setTargetFolder(selectedAccount, selectedFolder)
            }
        } else {
            Timber.w("Cannot retry message fetch: no folder or account selected.")
            _uiState.update { it.copy(toastMessage = "Please select a folder and account first.") }
        }
    }

    fun retryFetchThreadsForCurrentFolder() {
        val selectedAccount = getSelectedAccount()
        val selectedFolder = _uiState.value.selectedFolder

        if (selectedAccount != null && selectedFolder != null) {
            Timber.d("Retrying fetch threads for folder ${selectedFolder.id} on account ${selectedAccount.id}")
            viewModelScope.launch {
                threadRepository.setTargetFolderForThreads(selectedAccount, selectedFolder, null)
            }
        } else {
            Timber.w("Cannot retry thread fetch: no folder or account selected.")
            _uiState.update { it.copy(toastMessage = "Please select a folder and account first.") }
        }
    }

    fun onFolderSelected(account: Account, folder: MailFolder) {
        Timber.d("Folder selected: ${folder.displayName} in account ${account.emailAddress}")
        _uiState.update {
            it.copy(
                selectedFolderAccountId = account.id,
                selectedFolder = folder
            )
        }
        // Cancel previous folder's message collection job
        currentFolderMessagesJob?.cancel()

        // Update the pager flow for the new folder
        _messagesPagerFlow.value = messageRepository.getMessagesPager(
            accountId = account.id,
            folderId = folder.id,
            // Define a PagingConfig - this should probably be a constant or configurable
            pagingConfig = PagingConfig(pageSize = 20, enablePlaceholders = false)
        ).cachedIn(viewModelScope) // cachedIn is important for surviving config changes

        // The old messageRepository.setTargetFolder() might still be relevant if it managed
        // other non-paging related state or triggered initial non-paging syncs. Review its role.
        // For Paging 3 with RemoteMediator, the mediator handles data fetching.
        // If setTargetFolder also clears selections or resets other states, that part might need to be preserved
        // or handled differently.
        // For now, let's assume Pager is the primary mechanism for message loading for the selected folder.
        // viewModelScope.launch {
        //    messageRepository.setTargetFolder(account, folder) // This might trigger the old non-paging sync
        // }

        if (_uiState.value.currentViewMode == MailViewModePreference.THREADS) {
            viewModelScope.launch {
                threadRepository.setTargetFolderForThreads(account, folder, null)
            }
        }

        // Kick off a background sync if this folder looks empty or has never been synced.
        viewModelScope.launch {
            Timber.i("selectFolder: Triggering syncMessagesForFolder for account ${account.emailAddress}, folder ${folder.displayName}")
            try {
                messageRepository.syncMessagesForFolder(account.id, folder.id, null)
            } catch (e: Exception) {
                Timber.e(e, "selectFolder: Failed to queue syncMessagesForFolder job")
            }
        }
    }

    fun refreshMessages() {
        Timber.d("refreshMessages triggered.")
        // For Paging 3, refreshing is typically handled by calling refresh() on the LazyPagingItems adapter in the UI.
        // The ViewModel doesn't directly trigger a Pager refresh usually, but it can if needed.
        // The current `messageRepository.refreshMessages` is likely for the old non-paging mechanism.
        // If we want the ViewModel to be able to trigger a Paging 3 refresh, we might need a new
        // signal or the UI should handle it.
        // For now, this method might become a no-op or re-trigger the Pager if we expose a way.

        // OLD Logic (may not apply directly to Paging 3 via RemoteMediator)
        // val currentAccount = _uiState.value.accounts.find { it.id == _uiState.value.selectedFolderAccountId }
        // val currentFolder = _uiState.value.selectedFolder
        // if (currentAccount != null && currentFolder != null) {
        //     viewModelScope.launch {
        //         messageRepository.refreshMessages() // This was for the old list-based sync
        //     }
        // } else {
        //     Timber.w("Cannot refresh messages, no folder selected or account not found.")
        // }
        // The Paging 3 refresh will be initiated by the UI (e.g., pull-to-refresh on LazyPagingItems.refresh()).
        // The RemoteMediator will then get a LoadType.REFRESH.
        // If _messageSyncState needs to be manually set to Syncing here, consider that.
        // However, the RemoteMediator itself updates _messageSyncState.
        _uiState.value.selectedFolderAccountId?.let { accId ->
            _uiState.value.selectedFolder?.id?.let { fId ->
                // We don't directly call a refresh on the pager flow here.
                // The UI will call adapter.refresh() which triggers the RemoteMediator's REFRESH load.
                // We can, however, ensure our sync state reflects an attempt if needed, though mediator does this.
                // _uiState.update { it.copy(messageSyncState = MessageSyncState.Syncing(accId, fId)) }
                // Potentially, if there's a global refresh button not tied to the list's adapter:
                // messageRepository.forcePagerRefresh(accId, fId) // This method would need to be added to repo
                // For now, assume UI triggers refresh on LazyPagingItems.
                Timber.i("Refresh for Paging 3 should be triggered by UI action on LazyPagingItems.")
            }
        }
    }

    fun refreshCurrentFolderView(activity: Activity?) {
        // Implementation of refreshCurrentFolderView method
    }

    /**
     * Selects the synthetic "All Inboxes" view which aggregates INBOX mail from every account.
     */
    fun selectUnifiedInbox() {
        Timber.i("selectUnifiedInbox() called – Switching to global inbox view")

        // Create a synthetic MailFolder domain object for UI purposes.
        val unifiedFolder = MailFolder(
            id = UNIFIED_INBOX_ID,
            displayName = "All Inboxes",
            totalItemCount = 0,
            unreadItemCount = 0,
            type = WellKnownFolderType.INBOX
        )

        // Update UI state – no single account context for unified inbox
        _uiState.update {
            it.copy(
                selectedFolderAccountId = null,
                selectedFolder = unifiedFolder
            )
        }

        // Cancel previous folder's pager job if any
        currentFolderMessagesJob?.cancel()

        // Supply a unified pager to the UI
        _messagesPagerFlow.value = messageRepository.getUnifiedInboxPager(
            pagingConfig = PagingConfig(
                pageSize = 30,
                prefetchDistance = 10,
                enablePlaceholders = false,
                initialLoadSize = 60
            ),
            filterUnread = _uiState.value.isUnreadFilterActive,
            filterStarred = _uiState.value.isStarredFilterActive
        ).cachedIn(viewModelScope)
    }

    /**
     * Toggles the 'Unread' filter for the unified inbox view and refreshes the message list.
     */
    fun toggleUnreadFilter() {
        val newFilterState = !_uiState.value.isUnreadFilterActive
        _uiState.update { it.copy(isUnreadFilterActive = newFilterState) }
        // Only refresh the pager if we are currently in the unified inbox view
        if (_uiState.value.selectedFolder?.id == UNIFIED_INBOX_ID) {
            selectUnifiedInbox()
        }
    }

    /**
     * Toggles the 'Starred' filter for the unified inbox view and refreshes the message list.
     */
    fun toggleStarredFilter() {
        val newFilterState = !_uiState.value.isStarredFilterActive
        _uiState.update { it.copy(isStarredFilterActive = newFilterState) }
        // Only refresh the pager if we are currently in the unified inbox view
        if (_uiState.value.selectedFolder?.id == UNIFIED_INBOX_ID) {
            selectUnifiedInbox()
        }
    }

    companion object {
        /** Special pseudo-folder ID representing "All Inboxes" */
        const val UNIFIED_INBOX_ID = "UNIFIED-INBOX"
    }

    fun onRefresh() {
        val accountId = _uiState.value.selectedFolderAccountId
        val folderId = _uiState.value.selectedFolder?.id
        if (accountId != null && folderId != null) {
            viewModelScope.launch {
                syncFolderUseCase(accountId, folderId)
            }
        }
    }

    fun onAddAccountClicked(activity: Activity) {
        // ... existing code ...
    }

    fun updateSignature(signature: String) {
        viewModelScope.launch {
            userPreferencesRepository.updateSignature(signature)
        }
    }

    fun signOutAllMicrosoftAccounts() {
        Timber.d("signOutAllMicrosoftAccounts called from ViewModel")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAccountAction = true) }
            signOutAllMicrosoftAccountsUseCase().collect { result ->
                _uiState.update { currentState ->
                    when (result) {
                        is GenericSignOutAllResult.Success -> {
                            Timber.d("Sign-out all successful. Removed: ${result.removedCount}, Failed: ${result.failedCount}")
                            currentState.copy(
                                isLoadingAccountAction = false,
                                toastMessage = "Cleared ${result.removedCount} Microsoft account(s)."
                            )
                        }
                        is GenericSignOutAllResult.Error -> {
                            Timber.w(result.cause, "Sign-out all error: ${result.message}")
                            currentState.copy(
                                isLoadingAccountAction = false,
                                toastMessage = "Error clearing accounts: ${result.message}"
                            )
                        }
                        is GenericSignOutAllResult.NotInitialized -> {
                            Timber.w("Sign-out all error: MSAL not initialized.")
                            currentState.copy(
                                isLoadingAccountAction = false,
                                toastMessage = "Error: MSAL not initialized."
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Submit a FullAccountBootstrap job for every currently configured account.
     * Useful from the Debug section to re-sync everything on demand.
     */
    fun forceFullSyncAllAccounts() {
        viewModelScope.launch {
            val accounts = _uiState.value.accounts
            accounts.forEach { acc ->
                syncController.submit(net.melisma.core_data.model.SyncJob.FullAccountBootstrap(acc.id))
            }
            tryEmitToastMessage("Queued full re-sync for ${accounts.size} account(s)")
        }
    }

    fun onAppStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                viewModelScope.launch {
                    _requestPostNotificationsPermission.emit(Unit)
                }
            }
        }
    }
}

fun MailFolder.isInboxFolder(): Boolean {
    return this.type == WellKnownFolderType.INBOX ||
            this.displayName.equals("Inbox", ignoreCase = true) ||
            this.displayName.equals("Caixa de Entrada", ignoreCase = true)
}
