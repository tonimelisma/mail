// File: app/src/main/java/net/melisma/mail/MainViewModel.kt
// Complete, Corrected Version for Step 1.4

package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.feature_auth.AcquireTokenResult
import net.melisma.feature_auth.MicrosoftAuthManager
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject

// --- State Definitions ---
enum class DataState { INITIAL, LOADING, SUCCESS, ERROR }

sealed class FolderFetchState {
    data object Loading : FolderFetchState()
    data class Success(val folders: List<MailFolder>) : FolderFetchState()
    data class Error(val error: String) : FolderFetchState()
}

@Immutable
data class MainScreenState(
    val authState: AuthState = AuthState.Initializing,
    val accounts: List<Account> = emptyList(), // Generic Account list
    val isLoadingAccountAction: Boolean = false, // Loading state for add/remove
    val foldersByAccountId: Map<String, FolderFetchState> = emptyMap(),
    val selectedFolderAccountId: String? = null,
    val selectedFolder: MailFolder? = null,
    val messageDataState: DataState = DataState.INITIAL,
    val messages: List<Message>? = null,
    val messageError: String? = null,
    val toastMessage: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val accountRepository: AccountRepository,
    // Keep MicrosoftAuthManager ONLY for acquireTokenAndExecute helper (temporary)
    private val microsoftAuthManager: MicrosoftAuthManager
) : ViewModel() {

    private val TAG = "MainViewModel"
    private val TAG_DEBUG_DEFAULT_SELECT = "MailAppDebug"

    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    private val folderFetchJobs = mutableMapOf<String, Job>()
    private var initialFolderLoadJob: Job? = null

    private val mailReadScopes = listOf("User.Read", "Mail.Read")
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25

    // --- Initialization ---
    init {
        Log.d(TAG, "ViewModel Initializing - Observing AccountRepository")
        observeRepositoryAuthState()
        observeRepositoryAccounts()
        observeRepositoryLoadingState()
        observeRepositoryMessages()
        // Trigger initial check (safe to call even if state isn't fully ready yet)
        triggerInitialFolderFetchesIfNeeded(accountRepository.accounts.value)
    }

    // --- Repository Flow Observation ---
    private fun observeRepositoryAuthState() {
        accountRepository.authState
            .onEach { newAuthState ->
                Log.d(TAG, "Repo AuthState: $newAuthState")
                val previousInitialized = _uiState.value.authState is AuthState.Initialized
                val currentlyInitialized = newAuthState is AuthState.Initialized
                _uiState.update { currentState ->
                    val clearSelectedFolder = (!currentlyInitialized && previousInitialized) ||
                            (newAuthState is AuthState.InitializationError) ||
                            (newAuthState is AuthState.Initializing && previousInitialized)
                    currentState.copy(
                        authState = newAuthState,
                        selectedFolder = if (clearSelectedFolder) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (clearSelectedFolder) null else currentState.selectedFolderAccountId,
                        messageDataState = if (clearSelectedFolder) DataState.INITIAL else currentState.messageDataState,
                        messages = if (clearSelectedFolder) null else currentState.messages,
                        messageError = if (clearSelectedFolder) null else currentState.messageError,
                        foldersByAccountId = if (!currentlyInitialized) emptyMap() else currentState.foldersByAccountId
                    )
                }
                if (currentlyInitialized) {
                    // Trigger folder fetches if needed (accounts might have changed before state)
                    triggerInitialFolderFetchesIfNeeded(accountRepository.accounts.value)
                } else {
                    cancelAllFolderFetches()
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeRepositoryAccounts() {
        accountRepository.accounts
            .onEach { newAccountList ->
                Log.d(TAG, "Repo Accounts: ${newAccountList.size} accounts")
                val previousAccounts = _uiState.value.accounts
                val previousSelectedAccountId = _uiState.value.selectedFolderAccountId
                _uiState.update { currentState ->
                    val removedAccountIds =
                        previousAccounts.map { it.id } - newAccountList.map { it.id }.toSet()
                    val selectedAccountRemoved =
                        previousSelectedAccountId != null && previousSelectedAccountId in removedAccountIds
                    val newFoldersByAccount =
                        currentState.foldersByAccountId.filterKeys { it !in removedAccountIds }
                    currentState.copy(
                        accounts = newAccountList,
                        foldersByAccountId = newFoldersByAccount,
                        selectedFolder = if (selectedAccountRemoved) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (selectedAccountRemoved) null else currentState.selectedFolderAccountId,
                        messageDataState = if (selectedAccountRemoved) DataState.INITIAL else currentState.messageDataState,
                        messages = if (selectedAccountRemoved) null else currentState.messages,
                        messageError = if (selectedAccountRemoved) null else currentState.messageError
                    )
                }
                if (_uiState.value.authState is AuthState.Initialized) {
                    val removedAccountIds =
                        previousAccounts.map { it.id } - newAccountList.map { it.id }.toSet()
                    removedAccountIds.forEach { cancelFolderFetch(it) }
                    triggerInitialFolderFetchesIfNeeded(newAccountList)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeRepositoryLoadingState() {
        accountRepository.isLoadingAccountAction
            .onEach { isLoading ->
                Log.d(TAG, "Repo isLoadingAccountAction: $isLoading")
                _uiState.update { it.copy(isLoadingAccountAction = isLoading) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeRepositoryMessages() {
        accountRepository.accountActionMessage
            .onEach { message ->
                Log.d(TAG, "Repo accountActionMessage: $message")
                _uiState.update { it.copy(toastMessage = message) }
            }
            .launchIn(viewModelScope)
    }

    // --- Account Actions (Delegated) ---
    fun addAccount(activity: Activity) {
        viewModelScope.launch {
            accountRepository.addAccount(activity, mailReadScopes)
        }
    }

    fun removeAccount(activity: Activity, accountToRemove: Account?) {
        if (accountToRemove == null) {
            Log.e(TAG, "Remove account called with null account object.")
            // Clear any previous message from repo and show specific error
            viewModelScope.launch { accountRepository.clearAccountActionMessage() }
            _uiState.update { it.copy(toastMessage = "Cannot remove null account.") }
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
            Log.d(TAG, "Toast message shown, clearing state and notifying repository.")
            _uiState.update { it.copy(toastMessage = null) }
            accountRepository.clearAccountActionMessage()
        }
    }

    // --- Folder/Message Fetching Logic (Temporary state using IAccount) ---
    private fun triggerInitialFolderFetchesIfNeeded(currentAccounts: List<Account>) {
        if (uiState.value.authState !is AuthState.Initialized) return

        Log.d(
            TAG_DEBUG_DEFAULT_SELECT,
            "triggerInitialFolderFetchesIfNeeded checking ${currentAccounts.size} accounts"
        )
        val fetchJobs = mutableListOf<Job>()
        val accountsWeAreFetchingFor = mutableListOf<Account>()

        currentAccounts.forEach { account ->
            val currentState = _uiState.value.foldersByAccountId[account.id]
            // Fetch only if not already loaded or failed previously
            if (currentState == null /*|| currentState is FolderFetchState.Error */) { // Optionally retry errors here too
                val msalAccount = getMsalAccountById(account.id) // Use helper
                if (msalAccount != null) {
                    Log.d(TAG, "Queueing folder fetch for ${account.username}")
                    val job = launchFolderFetch(msalAccount, activity = null, forceRefresh = false)
                    fetchJobs.add(job)
                    accountsWeAreFetchingFor.add(account)
                } else {
                    Log.w(
                        TAG,
                        "Cannot fetch folders for account ${account.username}, IAccount not found (ViewModel refactor needed)."
                    )
                    // Update state to show error for this account's folders?
                    _uiState.update { s ->
                        s.copy(
                            foldersByAccountId = s.foldersByAccountId + (account.id to FolderFetchState.Error(
                                "Internal account data missing"
                            ))
                        )
                    }
                }
            }
        }

        val needsDefaultSelection = _uiState.value.selectedFolder == null &&
                currentAccounts.isNotEmpty() // Already checked auth state earlier

        if (needsDefaultSelection && fetchJobs.isNotEmpty()) {
            initialFolderLoadJob?.cancel()
            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "Launching initialFolderLoadJob to wait for ${fetchJobs.size} jobs."
            )
            initialFolderLoadJob = viewModelScope.launch {
                try {
                    fetchJobs.forEach { it.join() }
                    Log.d(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "initialFolderLoadJob: Fetch jobs joined successfully."
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "initialFolderLoadJob: Error joining fetch jobs.",
                        e
                    )
                }
                // Attempt selection using the list we just tried fetching for
                selectDefaultFolderIfNeeded(accountsWeAreFetchingFor)
            }
        } else if (needsDefaultSelection) {
            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "triggerInitialFolderFetchesIfNeeded: No new fetch jobs needed, calling selectDefaultFolderIfNeeded directly."
            )
            // Attempt selection using the full current list if no fetches were started
            selectDefaultFolderIfNeeded(currentAccounts)
        }
    }

    private fun launchFolderFetch(
        account: IAccount,
        activity: Activity?,
        forceRefresh: Boolean = false
    ): Job {
        val accountId = account.id ?: return Job().apply {
            completeExceptionally(
                IllegalArgumentException("Account ID missing")
            )
        }

        if (forceRefresh) {
            folderFetchJobs[accountId]?.cancel(CancellationException("Forced refresh requested"))
        }
        // Re-check active job *after* potential cancellation
        if (folderFetchJobs[accountId]?.isActive == true) {
            Log.d(TAG, "Folder fetch already in progress for ${account.username}")
            return folderFetchJobs[accountId]!!
        }

        val currentFolderState = _uiState.value.foldersByAccountId[accountId]
        // Skip if successfully loaded and not forcing refresh
        if (!forceRefresh && currentFolderState is FolderFetchState.Success) {
            Log.d(TAG, "Folders already loaded for ${account.username}, skipping fetch.")
            return Job().apply { complete() }
        }

        Log.d(TAG, "Starting folder fetch for ${account.username} (Force: $forceRefresh)")
        // Set loading state *before* launching async work
        _uiState.update { it.copy(foldersByAccountId = it.foldersByAccountId + (accountId to FolderFetchState.Loading)) }

        val job = viewModelScope.launch {
            val completionSignal = CompletableDeferred<Unit>()
            val jobDescription = "FolderFetch Job for ${account.username}"
            Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Outer job started.")

            try {
                if (!isOnline()) throw IOException("No internet connection")

                acquireTokenAndExecute(
                    account = account, activity = activity, scopes = mailReadScopes,
                    onError = { userErrorMessage ->
                        // Update state only if still relevant
                        if (shouldUpdateStateFor(accountId, forceRefresh)) {
                            _uiState.update { s ->
                                s.copy(
                                    foldersByAccountId = s.foldersByAccountId + (accountId to FolderFetchState.Error(
                                        userErrorMessage
                                    ))
                                )
                            }
                            Log.e(TAG, "$jobDescription: Error (Token) - $userErrorMessage")
                        } else {
                            Log.w(
                                TAG,
                                "$jobDescription: Token Error received but state no longer Loading/relevant for $accountId."
                            )
                        }
                        Log.e(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "$jobDescription: Completing deferred due to token error."
                        )
                        completionSignal.complete(Unit) // Signal completion on error
                    }
                ) { accessToken -> // Token acquired successfully
                    Log.d(TAG, "Got token for ${account.username}, fetching folders from Graph...")
                    // Assume GraphApiHelper is available directly or injected later
                    val foldersResult = GraphApiHelper.getMailFolders(accessToken)

                    // Update state only if still relevant
                    if (shouldUpdateStateFor(accountId, forceRefresh)) {
                        val newState = if (foldersResult.isSuccess) {
                            FolderFetchState.Success(foldersResult.getOrThrow())
                        } else {
                            FolderFetchState.Error(mapGraphExceptionToUserMessage(foldersResult.exceptionOrNull()))
                        }
                        _uiState.update { s -> s.copy(foldersByAccountId = s.foldersByAccountId + (accountId to newState)) }

                        if (foldersResult.isSuccess) {
                            Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: API Call Success.")
                        } else {
                            Log.e(
                                TAG_DEBUG_DEFAULT_SELECT,
                                "$jobDescription: API Call Error: ${foldersResult.exceptionOrNull()?.message}"
                            )
                        }
                    } else {
                        Log.w(
                            TAG,
                            "$jobDescription: API Result received but state no longer Loading/relevant for $accountId."
                        )
                    }
                    Log.d(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "$jobDescription: Completing deferred after API call."
                    )
                    completionSignal.complete(Unit) // Signal completion after API call
                }

                Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Waiting for completion signal...")
                completionSignal.await() // Wait for callback to complete the deferred
                Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Completion signal received.")

            } catch (e: CancellationException) {
                Log.w(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Outer job cancelled.", e)
                // Ensure deferred completes if cancelled before callbacks do
                completionSignal.completeExceptionally(e)
                // Reset state only if it was cancelled while actively loading
                if (shouldResetStateOnCancel(accountId)) {
                    _uiState.update { s -> s.copy(foldersByAccountId = s.foldersByAccountId - accountId) }
                }
                throw e // Re-throw cancellation
            } catch (e: Exception) { // Catch other exceptions like IOException
                Log.e(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "$jobDescription: Outer job exception: ${e.message}",
                    e
                )
                // Ensure deferred completes if exception occurs before callbacks do
                completionSignal.completeExceptionally(e)
                // Update state only if still relevant
                if (shouldUpdateStateFor(accountId, forceRefresh)) {
                    _uiState.update { s ->
                        s.copy(
                            foldersByAccountId = s.foldersByAccountId + (accountId to FolderFetchState.Error(
                                mapGraphExceptionToUserMessage(e)
                            ))
                        )
                    }
                }
            } finally {
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "$jobDescription: Outer job finished (finally block)."
                )
                // Clean up job reference only if it's the current job instance
                if (folderFetchJobs[accountId] == coroutineContext[Job]) {
                    folderFetchJobs.remove(accountId)
                    Log.d(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "$jobDescription: Removed job reference from map."
                    )
                }
            }
        }
        folderFetchJobs[accountId] = job
        return job
    }

    private fun shouldUpdateStateFor(accountId: String, forceRefresh: Boolean): Boolean {
        // Update if forced, or if the current state for this ID is still Loading
        return forceRefresh || _uiState.value.foldersByAccountId[accountId] is FolderFetchState.Loading
    }

    private fun shouldResetStateOnCancel(accountId: String): Boolean {
        // Reset (remove entry) only if cancelled while Loading
        return _uiState.value.foldersByAccountId[accountId] is FolderFetchState.Loading
    }

    private fun cancelFolderFetch(accountId: String) {
        folderFetchJobs[accountId]?.apply {
            Log.d(TAG, "Cancelling folder fetch job for account $accountId")
            cancel(CancellationException("Account removed or auth state changed"))
        }
        folderFetchJobs.remove(accountId) // Remove reference regardless
    }

    private fun cancelAllFolderFetches() {
        if (folderFetchJobs.isNotEmpty()) {
            Log.d(TAG, "Cancelling ALL active folder fetch jobs.")
            // Create a copy of keys to avoid ConcurrentModificationException
            val accountIds = folderFetchJobs.keys.toList()
            accountIds.forEach { cancelFolderFetch(it) } // Cancel and remove each
        }
        initialFolderLoadJob?.cancel(CancellationException("Auth state lost"))
        initialFolderLoadJob = null
    }

    private fun selectDefaultFolderIfNeeded(accountsToCheck: List<Account>) {
        Log.d(
            TAG_DEBUG_DEFAULT_SELECT,
            "selectDefaultFolderIfNeeded: Checking ${accountsToCheck.size} accounts."
        )
        if (_uiState.value.selectedFolder == null && _uiState.value.authState is AuthState.Initialized) {
            var folderToSelect: MailFolder? = null
            var msalAccountForFolder: IAccount? = null // Still need IAccount for selectFolder

            // Prioritize Inbox
            for (account in accountsToCheck) {
                val folderState = _uiState.value.foldersByAccountId[account.id]
                if (folderState is FolderFetchState.Success) {
                    val inbox = folderState.folders.find {
                        it.displayName.equals(
                            "Inbox",
                            ignoreCase = true
                        )
                    }
                    if (inbox != null) {
                        val msalAccount = getMsalAccountById(account.id) // Use helper
                        if (msalAccount != null) {
                            folderToSelect = inbox; msalAccountForFolder = msalAccount; break
                        } else Log.w(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Found Inbox for ${account.username} but IAccount mapping failed."
                        )
                    }
                }
            }
            // Fallback: First available folder
            if (folderToSelect == null) {
                for (account in accountsToCheck) {
                    val folderState = _uiState.value.foldersByAccountId[account.id]
                    if (folderState is FolderFetchState.Success && folderState.folders.isNotEmpty()) {
                        val msalAccount = getMsalAccountById(account.id) // Use helper
                        if (msalAccount != null) {
                            folderToSelect = folderState.folders.first(); msalAccountForFolder =
                                msalAccount; break
                        } else Log.w(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "Found folder for ${account.username} but IAccount mapping failed."
                        )
                    }
                }
            }
            // Select if found
            if (folderToSelect != null && msalAccountForFolder != null) {
                Log.i(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "Selecting default folder '${folderToSelect.displayName}' from ${msalAccountForFolder.username}"
                )
                selectFolder(folderToSelect, msalAccountForFolder) // Use IAccount here
            } else Log.w(
                TAG_DEBUG_DEFAULT_SELECT,
                "selectDefaultFolderIfNeeded: No suitable folder found."
            )
        }
    }

    // selectFolder still takes IAccount
    fun selectFolder(folder: MailFolder, account: IAccount) {
        val accountId = account.id ?: return // Use IAccount's ID
        Log.i(
            TAG_DEBUG_DEFAULT_SELECT,
            "selectFolder: User selected '${folder.displayName}' from account ${account.username}"
        )
        if (folder.id == _uiState.value.selectedFolder?.id && accountId == _uiState.value.selectedFolderAccountId) {
            Log.d(TAG, "Folder ${folder.displayName} already selected.")
            return
        }
        _uiState.update {
            it.copy(
                selectedFolder = folder, selectedFolderAccountId = accountId,
                messages = null, messageError = null, messageDataState = DataState.LOADING
            )
        }
        // Fetch messages still needs IAccount
        fetchMessagesInternal(folder.id, account, isRefresh = false, activity = null)
    }

    // refreshAllFolders needs to map generic Account back to IAccount
    fun refreshAllFolders(activity: Activity?) {
        Log.d(TAG, "Requesting refresh for ALL folders...")
        _uiState.value.accounts.forEach { genericAccount ->
            val msalAccount = getMsalAccountById(genericAccount.id) // Get IAccount
            if (msalAccount != null) launchFolderFetch(msalAccount, activity, forceRefresh = true)
            else Log.w(
                TAG,
                "Cannot refresh folders for ${genericAccount.username}, IAccount not found."
            )
        }
    }

    // refreshMessages needs to get IAccount from ID
    fun refreshMessages(activity: Activity?) {
        val folderId = _uiState.value.selectedFolder?.id
        val accountId = _uiState.value.selectedFolderAccountId
        val account = getMsalAccountById(accountId) // Use helper

        if (account == null || folderId == null) {
            Log.w(
                TAG,
                "Refresh messages called but no folder/account selected or IAccount mapping failed."
            )
            _uiState.update { it.copy(toastMessage = "Select a folder first or account data missing.") }
            return
        }
        if (_uiState.value.messageDataState == DataState.LOADING) {
            Log.d(TAG, "Refresh skipped: Already loading."); return
        }
        if (!isOnline()) {
            _uiState.update {
                it.copy(
                    toastMessage = "No internet connection.",
                    messageDataState = DataState.ERROR,
                    messageError = "No internet connection"
                )
            }; return
        }

        Log.d(TAG, "Requesting message refresh for folder: $folderId, account: ${account.username}")
        _uiState.update { it.copy(messageDataState = DataState.LOADING, messageError = null) }
        // Fetch messages still needs IAccount
        fetchMessagesInternal(folderId, account, isRefresh = true, activity = activity)
    }

    // fetchMessagesInternal still takes IAccount
    private fun fetchMessagesInternal(
        folderId: String,
        account: IAccount,
        isRefresh: Boolean,
        activity: Activity?
    ) {
        val accountId = account.id ?: return // Use IAccount ID
        // Context Check: Ensure the folder/account we're fetching for is still selected.
        if (accountId != _uiState.value.selectedFolderAccountId || folderId != _uiState.value.selectedFolder?.id) {
            Log.w(TAG, "fetchMessagesInternal ignored for $folderId/$accountId, selection changed.")
            return
        }
        Log.d(TAG, "Fetching messages for $folderId/${account.username}")
        acquireTokenAndExecute(
            account = account, // Use IAccount
            activity = activity, scopes = mailReadScopes,
            onError = { errorMsg ->
                // Context Check inside callback
                if (_uiState.value.selectedFolderAccountId == accountId && _uiState.value.selectedFolder?.id == folderId) {
                    _uiState.update {
                        it.copy(
                            messageDataState = DataState.ERROR,
                            messageError = errorMsg
                        )
                    }
                    if (isRefresh) tryEmitToastMessage(errorMsg) // Use helper
                } else Log.w(TAG, "Message fetch token error received, but context changed.")
            }
        ) { accessToken -> // Token acquired successfully.
            // Context Check inside callback
            if (_uiState.value.selectedFolderAccountId == accountId && _uiState.value.selectedFolder?.id == folderId) {
                Log.d(
                    TAG,
                    "Got token for ${account.username}, fetching messages for folder $folderId from Graph..."
                )
                // Use GraphApiHelper directly for now
                val messagesResult = GraphApiHelper.getMessagesForFolder(
                    accessToken,
                    folderId,
                    messageListSelectFields,
                    messageListPageSize
                )
                _uiState.update {
                    it.copy(
                        messageDataState = if (messagesResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                        messages = messagesResult.getOrNull()
                            ?: if (messagesResult.isFailure) null else emptyList(),
                        messageError = messagesResult.exceptionOrNull()
                            ?.let { e -> mapGraphExceptionToUserMessage(e) }
                    )
                }
                if (messagesResult.isFailure) Log.e(
                    TAG,
                    "Failed fetch msg for $folderId",
                    messagesResult.exceptionOrNull()
                )
                if (isRefresh) {
                    val toastMsg =
                        if (messagesResult.isSuccess) "Messages refreshed" else "Refresh failed: ${_uiState.value.messageError}"
                    tryEmitToastMessage(toastMsg) // Use helper
                }
            } else Log.w(TAG, "Message fetch result received, but context changed.")
        }
    }

    // --- acquireTokenAndExecute Helper (Fixed duplicate 'when' branch) ---
    private fun acquireTokenAndExecute(
        account: IAccount?, activity: Activity?, scopes: List<String>,
        onError: ((String) -> Unit)?, onSuccess: suspend (String) -> Unit
    ) {
        if (account == null) {
            onError?.invoke("No account specified."); return
        }
        microsoftAuthManager.acquireTokenSilent(account, scopes) { tokenResult ->
            viewModelScope.launch {
                when (tokenResult) {
                    is AcquireTokenResult.Success -> {
                        try {
                            onSuccess(tokenResult.result.accessToken)
                        } catch (e: Exception) {
                            Log.e(TAG, "onSuccess err(silent):", e); onError?.invoke(
                                mapGraphExceptionToUserMessage(e)
                            )
                        }
                    }
                    is AcquireTokenResult.UiRequired -> {
                        if (activity != null) {
                            microsoftAuthManager.acquireTokenInteractive(
                                activity,
                                account,
                                scopes
                            ) { interactiveResult ->
                                viewModelScope.launch {
                                    when (interactiveResult) {
                                        is AcquireTokenResult.Success -> {
                                            try {
                                                onSuccess(interactiveResult.result.accessToken)
                                            } catch (e: Exception) {
                                                Log.e(
                                                    TAG,
                                                    "onSuccess err(inter):",
                                                    e
                                                ); onError?.invoke(mapGraphExceptionToUserMessage(e))
                                            }
                                        }

                                        is AcquireTokenResult.Error -> onError?.invoke(
                                            mapAuthExceptionToUserMessage(interactiveResult.exception)
                                        )

                                        is AcquireTokenResult.Cancelled -> onError?.invoke("Authentication cancelled.")
                                        else -> onError?.invoke("Authentication failed (interactive).")
                                    }
                                }
                            }
                        } else {
                            onError?.invoke("Session expired. Please refresh or try again.")
                        }
                    }

                    is AcquireTokenResult.Error -> onError?.invoke(
                        mapAuthExceptionToUserMessage(
                            tokenResult.exception
                        )
                    )

                    is AcquireTokenResult.Cancelled -> onError?.invoke("Authentication cancelled.") // Should be rare for silent
                    is AcquireTokenResult.NotInitialized -> onError?.invoke("Authentication system not ready.")
                    is AcquireTokenResult.NoAccountProvided -> onError?.invoke("Internal error: Account not provided.")
                    // UiRequired handled above, no need for duplicate branch
                }
            }
        }
    }

    // --- Error Mapping Helpers (Single definition) ---
    private fun mapGraphExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping graph exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}")
        return when (exception) {
            is UnknownHostException -> "No internet connection"
            is IOException -> "Couldn't reach server"
            else -> exception?.message?.takeIf { it.isNotBlank() } ?: "An unknown error occurred"
        }
    }
    private fun mapAuthExceptionToUserMessage(exception: MsalException): String {
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalClientException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication client error (${exception.errorCode})"

            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error (${exception.errorCode})"

            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication failed (${exception.errorCode})"
        }
    }

    // --- Temporary IAccount Helper (Made internal) ---
    /** Finds the original IAccount based on ID. Requires direct access to the manager. */
    internal fun getMsalAccountById(accountId: String?): IAccount? { // Changed to internal
        if (accountId == null) return null
        return microsoftAuthManager.accounts.find { it.id == accountId }
    }

    // --- Network Check ---
    private fun isOnline(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    // --- ViewModel Cleanup ---
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel Cleared.")
        cancelAllFolderFetches()
        // No listener to remove from manager here anymore
    }

    /** Helper to emit toast message via state update */
    private fun tryEmitToastMessage(message: String?) {
        if (message != null) {
            _uiState.update { it.copy(toastMessage = message) }
        }
    }
}