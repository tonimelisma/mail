// File: app/src/main/java/net/melisma/mail/MainViewModel.kt
// Final Refactor for Step 3: MessageRepository

package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.Immutable // If used
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
import net.melisma.core_data.model.Account // Updated
import net.melisma.core_data.model.AuthState // Updated
import net.melisma.core_data.model.FolderFetchState // Updated
import net.melisma.core_data.model.MailFolder // Updated
import net.melisma.core_data.model.Message // Updated
import net.melisma.core_data.model.MessageDataState // Updated
import net.melisma.core_data.repository.AccountRepository // Updated
import net.melisma.core_data.repository.FolderRepository // Updated
import net.melisma.core_data.repository.MessageRepository // Updated
import javax.inject.Inject


// --- State Definition ---

/**
 * Immutable data class representing the complete state for the main mail screen UI.
 * Now uses MessageDataState for message status.
 */
@Immutable
data class MainScreenState(
    val authState: AuthState = AuthState.Initializing,
    val accounts: List<Account> = emptyList(),
    val isLoadingAccountAction: Boolean = false,
    val foldersByAccountId: Map<String, FolderFetchState> = emptyMap(),
    val selectedFolderAccountId: String? = null,
    val selectedFolder: MailFolder? = null,
    val messageDataState: MessageDataState = MessageDataState.Initial, // Use new sealed class
    val toastMessage: String? = null
) {
    // Derived state: Check if any folder state is Loading
    val isAnyFolderLoading: Boolean
        get() = foldersByAccountId.values.any { it is FolderFetchState.Loading }

    // Derived state: Check if message state is Loading
    val isMessageLoading: Boolean
        get() = messageDataState is MessageDataState.Loading

    // Derived state: Get messages list from Success state
    val messages: List<Message>?
        get() = (messageDataState as? MessageDataState.Success)?.messages

    // Derived state: Get message error from Error state
    val messageError: String?
        get() = (messageDataState as? MessageDataState.Error)?.error
}

/**
 * ViewModel for the main mail screen. Manages UI state by observing repositories
 * and handling user actions. Delegates message fetching to MessageRepository.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val accountRepository: AccountRepository, // Correctly imported
    private val folderRepository: FolderRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val TAG = "MainViewModel"
    private val TAG_DEBUG_DEFAULT_SELECT = "MailAppDebug"

    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    // Scopes needed for addAccount action
    private val mailReadScopes = listOf("User.Read", "Mail.Read")

    // --- Initialization ---
    init {
        Log.d(TAG, "ViewModel Initializing")
        observeAccountRepository()
        observeFolderRepository()
        observeMessageRepository()

        viewModelScope.launch {
            val initialAccounts = accountRepository.accounts.value
            Log.d(TAG, "Initializing FolderRepository with ${initialAccounts.size} accounts.")
            folderRepository.manageObservedAccounts(initialAccounts)
            // Default selection attempt happens based on folder state observation
        }
    }

    // --- Repository Flow Observation ---

    /** Observes AccountRepository flows. */
    private fun observeAccountRepository() {
        // Observe Auth State
        accountRepository.authState
            .onEach { newAuthState ->
                Log.d(TAG, "AccountRepo AuthState Changed: $newAuthState")
                val wasInitialized = _uiState.value.authState is AuthState.Initialized
                val isInitialized = newAuthState is AuthState.Initialized
                _uiState.update { currentState ->
                    val clearSelection = (!isInitialized && wasInitialized) ||
                            (newAuthState is AuthState.InitializationError) ||
                            (newAuthState is AuthState.Initializing && wasInitialized)

                    // If clearing selection, also tell MessageRepository to clear its target
                    if (clearSelection) {
                        viewModelScope.launch { messageRepository.setTargetFolder(null, null) }
                    }

                    currentState.copy(
                        authState = newAuthState,
                        selectedFolder = if (clearSelection) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (clearSelection) null else currentState.selectedFolderAccountId
                        // messageDataState comes from MessageRepository observation
                    )
                }
            }.launchIn(viewModelScope)

        // Observe Account list
        accountRepository.accounts
            .onEach { newAccountList ->
                Log.d(TAG, "AccountRepo Accounts Changed: ${newAccountList.size} accounts")
                val previousAccounts = _uiState.value.accounts
                val previousSelectedAccountId = _uiState.value.selectedFolderAccountId
                folderRepository.manageObservedAccounts(newAccountList) // Inform FolderRepo

                _uiState.update { currentState ->
                    val removedAccountIds =
                        previousAccounts.map { it.id } - newAccountList.map { it.id }.toSet()
                    val selectedAccountRemoved =
                        previousSelectedAccountId != null && previousSelectedAccountId in removedAccountIds

                    // If clearing selection, also tell MessageRepository to clear its target
                    if (selectedAccountRemoved) {
                        viewModelScope.launch { messageRepository.setTargetFolder(null, null) }
                    }

                    currentState.copy(
                        accounts = newAccountList,
                        selectedFolder = if (selectedAccountRemoved) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (selectedAccountRemoved) null else currentState.selectedFolderAccountId
                        // messageDataState comes from MessageRepository observation
                    )
                }
                if (_uiState.value.selectedFolder == null) {
                    selectDefaultFolderIfNeeded(_uiState.value)
                }
            }.launchIn(viewModelScope)

        // Observe account action loading state
        accountRepository.isLoadingAccountAction
            .onEach { isLoading ->
                if (_uiState.value.isLoadingAccountAction != isLoading) {
                    Log.d(TAG, "AccountRepo isLoadingAccountAction: $isLoading")
                    _uiState.update { it.copy(isLoadingAccountAction = isLoading) }
                }
            }.launchIn(viewModelScope)

        // Observe account action messages (for toasts)
        accountRepository.accountActionMessage
            .onEach { message ->
                if (_uiState.value.toastMessage != message) {
                    Log.d(TAG, "AccountRepo accountActionMessage: $message")
                    _uiState.update { it.copy(toastMessage = message) }
                }
            }.launchIn(viewModelScope)
    }

    /** Observes FolderRepository state. */
    private fun observeFolderRepository() {
        folderRepository.observeFoldersState()
            .onEach { folderStatesMap ->
                val previousFolderMap = _uiState.value.foldersByAccountId
                if (previousFolderMap != folderStatesMap) {
                    Log.d(
                        TAG,
                        "FolderRepo State Changed: ${folderStatesMap.entries.joinToString { "${it.key}=${it.value::class.simpleName}" }}"
                    )
                    _uiState.update {
                        it.copy(foldersByAccountId = folderStatesMap)
                    }
                    val justLoaded = folderStatesMap.any { (id, state) ->
                        state !is FolderFetchState.Loading && previousFolderMap[id] is FolderFetchState.Loading
                    }
                    if (justLoaded && _uiState.value.selectedFolder == null) {
                        selectDefaultFolderIfNeeded(_uiState.value)
                    }
                }
            }.launchIn(viewModelScope)
    }

    /** Observes MessageRepository state. */
    private fun observeMessageRepository() {
        messageRepository.messageDataState
            .onEach { newMessageState ->
                if (_uiState.value.messageDataState != newMessageState) {
                    Log.d(TAG, "MessageRepo State Changed: ${newMessageState::class.simpleName}")
                    _uiState.update {
                        it.copy(messageDataState = newMessageState) // Update message state directly
                    }
                }
            }.launchIn(viewModelScope)
    }


    // --- Account Actions ---
    fun addAccount(activity: Activity) {
        viewModelScope.launch {
            Log.d(TAG, "Add account action triggered.")
            accountRepository.addAccount(activity, mailReadScopes)
        }
    }

    fun removeAccount(activity: Activity, accountToRemove: Account?) {
        if (accountToRemove == null) {
            Log.e(TAG, "Remove account called with null account object.")
            tryEmitToastMessage("Cannot remove null account.")
            return
        }
        Log.d(
            TAG,
            "Requesting removal via repository for account: ${accountToRemove.username} (ID: ${accountToRemove.id})"
        )
        viewModelScope.launch {
            accountRepository.removeAccount(accountToRemove)
        }
    }

    // --- Toast Management ---
    fun toastMessageShown() {
        if (_uiState.value.toastMessage != null) {
            Log.d(TAG, "Toast message shown, clearing UI state message.")
            _uiState.update { it.copy(toastMessage = null) }
            accountRepository.clearAccountActionMessage()
        }
    }

    // --- Folder/Message Selection and Refresh ---

    /** Selects a default folder if needed. */
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

            // Prioritize Inbox
            for (account in currentState.accounts) {
                val folderState = currentState.foldersByAccountId[account.id]
                if (folderState is FolderFetchState.Success) {
                    val inbox = folderState.folders.find { folder: MailFolder ->
                        folder.displayName.equals("Inbox", ignoreCase = true)
                    }
                    if (inbox != null) {
                        folderToSelect = inbox
                        accountForFolder = account
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Found Inbox in account ${account.username}"
                        )
                        break
                    }
                }
            }

            // Fallback: First available folder
            if (folderToSelect == null) {
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "Inbox not found, checking for first available folder."
                )
                for (account in currentState.accounts) {
                    val folderState = currentState.foldersByAccountId[account.id]
                    if (folderState is FolderFetchState.Success && folderState.folders.isNotEmpty()) {
                        folderToSelect = folderState.folders.firstOrNull()
                        accountForFolder = account
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Found first folder '${folderToSelect?.displayName}' in account ${account.username}"
                        )
                        break
                    }
                }
            }

            // Select if found
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


    /** Handles user selecting a folder. Updates selection state and tells MessageRepository the new target. */
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

        // Update selection state in UI
        _uiState.update {
            it.copy(
                selectedFolder = folder,
                selectedFolderAccountId = accountId
            )
        }

        // Inform the MessageRepository about the new target folder/account
        viewModelScope.launch {
            messageRepository.setTargetFolder(account, folder)
        }
    }

    /** Requests FolderRepository to refresh all folders. */
    fun refreshAllFolders(activity: Activity?) {
        Log.d(TAG, "Requesting refresh for ALL folders via FolderRepository...")
        viewModelScope.launch {
            folderRepository.refreshAllFolders(activity)
        }
    }

    /** Requests MessageRepository to refresh messages for the currently selected folder. */
    fun refreshMessages(activity: Activity?) {
        val currentSelectedFolder = _uiState.value.selectedFolder
        val currentSelectedAccountId = _uiState.value.selectedFolderAccountId

        if (currentSelectedFolder == null || currentSelectedAccountId == null) {
            Log.w(TAG, "Refresh messages called but no folder/account selected.")
            tryEmitToastMessage("Select a folder first.")
            return
        }

        if (!isOnline()) {
            // Let the repository handle the error state update via its flow
            // _uiState.update { it.copy(messageDataState = MessageDataState.Error("No internet connection.")) }
            tryEmitToastMessage("No internet connection.")
            return
        }

        Log.d(
            TAG,
            "Requesting message refresh via MessageRepository for folder: ${currentSelectedFolder.id}"
        )
        // Delegate refresh action to the MessageRepository
        viewModelScope.launch {
            messageRepository.refreshMessages(activity)
        }
    }

    // --- Network Check ---
    /** Checks for active network connection. */
    private fun isOnline(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // --- ViewModel Cleanup ---
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel Cleared.")
    }

    /** Updates toastMessage state if the message is new. */
    private fun tryEmitToastMessage(message: String?) {
        if (message != null && _uiState.value.toastMessage != message) {
            _uiState.update { it.copy(toastMessage = message) }
        }
    }
}
