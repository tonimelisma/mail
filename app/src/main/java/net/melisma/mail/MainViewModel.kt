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
import net.melisma.core_data.repository.capabilities.GoogleAccountCapability // Added for Google capability
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

    // Gmail API scopes for Google accounts
    private val gmailReadScopes = listOf("https://www.googleapis.com/auth/gmail.readonly")

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
        Log.d(TAG, "MainViewModel: observeAccountRepository() - Setting up flows")

        // Observe Auth State
        accountRepository.authState
            .onEach { newAuthState ->
                Log.d(TAG, "MainViewModel: AccountRepo AuthState Changed: $newAuthState")
                val wasInitialized = _uiState.value.authState is AuthState.Initialized
                val isInitialized = newAuthState is AuthState.Initialized

                Log.d(
                    TAG,
                    "MainViewModel: Auth state transition: wasInitialized=$wasInitialized, isInitialized=$isInitialized"
                )

                _uiState.update { currentState ->
                    val clearSelection = (!isInitialized && wasInitialized) ||
                            (newAuthState is AuthState.InitializationError) ||
                            (newAuthState is AuthState.Initializing && wasInitialized)

                    if (clearSelection) {
                        Log.d(TAG, "MainViewModel: Auth state requires clearing selection")
                        // If clearing selection, also tell MessageRepository to clear its target
                        viewModelScope.launch {
                            Log.d(TAG, "MainViewModel: Clearing target folder in MessageRepository")
                            messageRepository.setTargetFolder(null, null)
                        }
                    }

                    val updatedState = currentState.copy(
                        authState = newAuthState,
                        selectedFolder = if (clearSelection) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (clearSelection) null else currentState.selectedFolderAccountId
                        // messageDataState comes from MessageRepository observation
                    )

                    Log.d(TAG, "MainViewModel: UI state updated with new auth state: $newAuthState")
                    if (clearSelection) {
                        Log.d(TAG, "MainViewModel: Folder selection cleared")
                    }

                    updatedState
                }
            }.launchIn(viewModelScope)

        // Observe Account list
        accountRepository.accounts
            .onEach { newAccountList ->
                Log.d(
                    TAG,
                    "MainViewModel: AccountRepo Accounts Changed: ${newAccountList.size} accounts"
                )
                if (newAccountList.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "MainViewModel: Account usernames: ${newAccountList.joinToString { it.username }}"
                    )
                }

                val previousAccounts = _uiState.value.accounts
                val previousSelectedAccountId = _uiState.value.selectedFolderAccountId

                Log.d(TAG, "MainViewModel: Informing FolderRepository about account changes")
                folderRepository.manageObservedAccounts(newAccountList) // Inform FolderRepo

                _uiState.update { currentState ->
                    val removedAccountIds =
                        previousAccounts.map { it.id } - newAccountList.map { it.id }.toSet()

                    if (removedAccountIds.isNotEmpty()) {
                        Log.d(TAG, "MainViewModel: Accounts removed: $removedAccountIds")
                    }

                    val selectedAccountRemoved =
                        previousSelectedAccountId != null && previousSelectedAccountId in removedAccountIds

                    // If clearing selection, also tell MessageRepository to clear its target
                    if (selectedAccountRemoved) {
                        Log.d(
                            TAG,
                            "MainViewModel: Selected account was removed: $previousSelectedAccountId"
                        )
                        viewModelScope.launch {
                            Log.d(
                                TAG,
                                "MainViewModel: Clearing target folder in MessageRepository due to account removal"
                            )
                            messageRepository.setTargetFolder(null, null)
                        }
                    }

                    val updatedState = currentState.copy(
                        accounts = newAccountList,
                        selectedFolder = if (selectedAccountRemoved) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (selectedAccountRemoved) null else currentState.selectedFolderAccountId
                        // messageDataState comes from MessageRepository observation
                    )

                    Log.d(
                        TAG,
                        "MainViewModel: UI state updated with ${newAccountList.size} accounts"
                    )
                    updatedState
                }

                if (_uiState.value.selectedFolder == null) {
                    Log.d(
                        TAG,
                        "MainViewModel: No folder selected, attempting to select default folder"
                    )
                    selectDefaultFolderIfNeeded(_uiState.value)
                }
            }.launchIn(viewModelScope)

        // Observe account action loading state
        accountRepository.isLoadingAccountAction
            .onEach { isLoading ->
                if (_uiState.value.isLoadingAccountAction != isLoading) {
                    Log.d(
                        TAG,
                        "MainViewModel: AccountRepo isLoadingAccountAction changed: $isLoading"
                    )
                    _uiState.update { it.copy(isLoadingAccountAction = isLoading) }
                }
            }.launchIn(viewModelScope)

        // Observe account action messages (for toasts)
        accountRepository.accountActionMessage
            .onEach { message ->
                if (_uiState.value.toastMessage != message) {
                    Log.d(TAG, "MainViewModel: AccountRepo accountActionMessage: $message")
                    _uiState.update { it.copy(toastMessage = message) }
                }
            }.launchIn(viewModelScope)

        Log.d(TAG, "MainViewModel: Finished setting up AccountRepository observation flows")
    }

    /** Observes FolderRepository state. */
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

                    // Log detail about each account's folder state
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

                            is FolderFetchState.Loading -> {
                                Log.d(TAG, "MainViewModel: Account $accountId folders are loading")
                            }

                            is FolderFetchState.Error -> {
                                Log.e(
                                    TAG,
                                    "MainViewModel: Account $accountId folders error: ${state.error}"
                                )
                            }
                        }
                    }

                    _uiState.update {
                        it.copy(foldersByAccountId = folderStatesMap)
                    }
                    Log.d(TAG, "MainViewModel: UI state updated with new folder states")

                    val justLoaded = folderStatesMap.any { (id, state) ->
                        val wasLoading = previousFolderMap[id] is FolderFetchState.Loading
                        val isNotLoadingNow = state !is FolderFetchState.Loading
                        val justFinishedLoading = wasLoading && isNotLoadingNow

                        if (justFinishedLoading) {
                            Log.d(
                                TAG,
                                "MainViewModel: Folders for account $id just finished loading"
                            )
                        }

                        justFinishedLoading
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

    /** Observes MessageRepository state. */
    private fun observeMessageRepository() {
        Log.d(TAG, "MainViewModel: observeMessageRepository() - Setting up flow")

        messageRepository.messageDataState
            .onEach { newMessageState ->
                if (_uiState.value.messageDataState != newMessageState) {
                    Log.d(
                        TAG,
                        "MainViewModel: MessageRepo State Changed: ${newMessageState::class.simpleName}"
                    )

                    // Log detailed information about the new message state
                    when (newMessageState) {
                        is MessageDataState.Success -> {
                            val messageCount = newMessageState.messages.size
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

                        is MessageDataState.Loading -> {
                            Log.d(TAG, "MainViewModel: Messages are loading")
                        }

                        is MessageDataState.Error -> {
                            Log.e(
                                TAG,
                                "MainViewModel: Message loading error: ${newMessageState.error}"
                            )
                        }

                        is MessageDataState.Initial -> {
                            Log.d(
                                TAG,
                                "MainViewModel: Message state is initial (no data loaded yet)"
                            )
                        }
                    }

                    _uiState.update {
                        it.copy(messageDataState = newMessageState) // Update message state directly
                    }
                    Log.d(TAG, "MainViewModel: UI state updated with new message state")
                }
            }.launchIn(viewModelScope)

        Log.d(TAG, "MainViewModel: Finished setting up MessageRepository observation flow")
    }


    // --- Account Actions ---
    fun addAccount(activity: Activity) {
        Log.d(TAG, "MainViewModel: addAccount() called for Microsoft account")
        viewModelScope.launch {
            Log.d(
                TAG,
                "MainViewModel: Add Microsoft account action triggered with scopes: $mailReadScopes"
            )
            accountRepository.addAccount(activity, mailReadScopes, "MS")
        }
    }

    fun addGoogleAccount(activity: Activity) {
        Log.d(TAG, "MainViewModel: addGoogleAccount() called")
        viewModelScope.launch {
            Log.d(
                TAG,
                "MainViewModel: Add Google account action triggered with scopes: $gmailReadScopes"
            )
            accountRepository.addAccount(activity, gmailReadScopes, "GOOGLE")
        }
    }

    fun removeAccount(activity: Activity, accountToRemove: Account?) {
        Log.d(
            TAG,
            "MainViewModel: removeAccount() called for account: ${accountToRemove?.username}"
        )
        if (accountToRemove == null) {
            Log.e(TAG, "MainViewModel: Remove account called with null account object")
            tryEmitToastMessage("Cannot remove null account.")
            return
        }
        Log.d(
            TAG,
            "MainViewModel: Requesting removal via repository for account: ${accountToRemove.username} (ID: ${accountToRemove.id}, Provider: ${accountToRemove.providerType})"
        )
        viewModelScope.launch {
            Log.d(TAG, "MainViewModel: Calling accountRepository.removeAccount()")
            accountRepository.removeAccount(accountToRemove)
        }
    }

    // For handling Google OAuth consent result
    private val _needGoogleConsent = MutableStateFlow(false)
    val needGoogleConsent: StateFlow<Boolean> = _needGoogleConsent.asStateFlow()

    // Safely cast to GoogleAccountCapability if supported
    private val googleAccountCapability = accountRepository as? GoogleAccountCapability

    init {
        // Observe Google consent intent from the repository if it supports the capability
        googleAccountCapability?.let { capability ->
            capability.googleConsentIntent
                .onEach { intentSender ->
                    if (intentSender != null) {
                        Log.d(
                            TAG,
                            "Google consent intent received. Signaling UI to launch consent."
                        )
                        _googleConsentIntentSender.value = intentSender
                        _needGoogleConsent.value = true
                    }
                }.launchIn(viewModelScope)
        } ?: run {
            Log.d(TAG, "GoogleAccountCapability not available from AccountRepository")
        }
    }

    // IntentSender for Google OAuth consent
    private val _googleConsentIntentSender = MutableStateFlow<android.content.IntentSender?>(null)
    val googleConsentIntentSender: StateFlow<android.content.IntentSender?> =
        _googleConsentIntentSender.asStateFlow()

    fun finalizeGoogleScopeConsent(
        account: Account,
        intent: android.content.Intent?,
        activity: Activity
    ) {
        // Check if the capability is available
        if (googleAccountCapability == null) {
            Log.e(TAG, "Cannot finalize Google consent: GoogleAccountCapability not available")
            tryEmitToastMessage("Error: Cannot complete Google account setup")
            return
        }

        viewModelScope.launch {
            googleAccountCapability.finalizeGoogleScopeConsent(account, intent, activity)
            _needGoogleConsent.value = false
            _googleConsentIntentSender.value = null
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
