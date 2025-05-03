// File: app/src/main/java/net/melisma/mail/MainViewModel.kt
// Corrected AccountRepository import path

package net.melisma.mail

// *** CORRECTED IMPORT PATH ***
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
import com.microsoft.identity.client.exception.MsalUserCancelException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.feature_auth.AcquireTokenResult
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.data.repositories.FolderRepository
import net.melisma.mail.model.FolderFetchState
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject


// --- State Definitions ---

/** Enum representing the fetch state specifically for the message list. */
enum class DataState { INITIAL, LOADING, SUCCESS, ERROR }

/**
 * Immutable data class representing the complete state for the main mail screen UI.
 */
@Immutable
data class MainScreenState(
    val authState: AuthState = AuthState.Initializing,
    val accounts: List<Account> = emptyList(),
    val isLoadingAccountAction: Boolean = false,
    val foldersByAccountId: Map<String, FolderFetchState> = emptyMap(),
    val selectedFolderAccountId: String? = null,
    val selectedFolder: MailFolder? = null,
    val messageDataState: DataState = DataState.INITIAL,
    val messages: List<Message>? = null,
    val messageError: String? = null,
    val toastMessage: String? = null
) {
    val isAnyFolderLoading: Boolean
        get() = foldersByAccountId.values.any { it is FolderFetchState.Loading }
}

/**
 * ViewModel for the main mail screen.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val accountRepository: AccountRepository, // Now correctly resolved
    private val folderRepository: FolderRepository,
    // TODO: Remove direct dependency on MicrosoftAuthManager
    private val microsoftAuthManager: MicrosoftAuthManager
) : ViewModel() {

    private val TAG = "MainViewModel"
    private val TAG_DEBUG_DEFAULT_SELECT = "MailAppDebug"

    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    // Config for message fetching (TODO: move)
    private val mailReadScopes = listOf("User.Read", "Mail.Read")
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25

    // --- Initialization ---
    init {
        Log.d(TAG, "ViewModel Initializing")
        observeAccountRepository()
        observeFolderRepository()

        viewModelScope.launch {
            val initialAccounts = accountRepository.accounts.value
            Log.d(TAG, "Initializing FolderRepository with ${initialAccounts.size} accounts.")
            folderRepository.manageObservedAccounts(initialAccounts)
            selectDefaultFolderIfNeeded(_uiState.value)
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
                    currentState.copy(
                        authState = newAuthState,
                        selectedFolder = if (clearSelection) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (clearSelection) null else currentState.selectedFolderAccountId,
                        messageDataState = if (clearSelection) DataState.INITIAL else currentState.messageDataState,
                        messages = if (clearSelection) null else currentState.messages,
                        messageError = if (clearSelection) null else currentState.messageError
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
                    currentState.copy(
                        accounts = newAccountList,
                        selectedFolder = if (selectedAccountRemoved) null else currentState.selectedFolder,
                        selectedFolderAccountId = if (selectedAccountRemoved) null else currentState.selectedFolderAccountId,
                        messageDataState = if (selectedAccountRemoved) DataState.INITIAL else currentState.messageDataState,
                        messages = if (selectedAccountRemoved) null else currentState.messages,
                        messageError = if (selectedAccountRemoved) null else currentState.messageError
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

    // --- Folder/Message Fetching Logic ---

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
            }
        } else {
            Log.d(TAG_DEBUG_DEFAULT_SELECT, "Default folder selection skipped.")
        }
    }


    /** Handles user selecting a folder. */
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

        _uiState.update {
            it.copy(
                selectedFolder = folder,
                selectedFolderAccountId = accountId,
                messages = null,
                messageError = null,
                messageDataState = DataState.LOADING
            )
        }

        // --- Trigger message fetching (Temporary IAccount logic) ---
        // TODO: Replace when MessageRepository is implemented.
        val msalAccount = getMsalAccountById(accountId)
        if (msalAccount != null) {
            fetchMessagesInternal(folder.id, msalAccount, isRefresh = false, activity = null)
        } else {
            Log.e(TAG, "Cannot fetch messages: Failed to find IAccount for Account ID: $accountId")
            _uiState.update {
                it.copy(
                    messageDataState = DataState.ERROR,
                    messageError = "Internal account data error."
                )
            }
            tryEmitToastMessage("Error loading messages: Account mismatch.")
        }
        // --- End Temporary Block ---
    }

    /** Requests FolderRepository to refresh all folders. */
    fun refreshAllFolders(activity: Activity?) {
        Log.d(TAG, "Requesting refresh for ALL folders via FolderRepository...")
        viewModelScope.launch {
            folderRepository.refreshAllFolders(activity)
        }
    }

    /** Refreshes messages for the currently selected folder. */
    fun refreshMessages(activity: Activity?) {
        val currentSelectedFolder = _uiState.value.selectedFolder
        val currentSelectedAccountId = _uiState.value.selectedFolderAccountId

        if (currentSelectedFolder == null || currentSelectedAccountId == null) {
            Log.w(TAG, "Refresh messages called but no folder/account selected.")
            tryEmitToastMessage("Select a folder first.")
            return
        }
        if (_uiState.value.messageDataState == DataState.LOADING) {
            Log.d(TAG, "Refresh skipped: Already loading messages."); return
        }
        if (!isOnline()) {
            _uiState.update {
                it.copy(
                    messageDataState = DataState.ERROR,
                    messageError = "No internet connection."
                )
            }
            tryEmitToastMessage("No internet connection.")
            return
        }

        // --- Trigger message fetching (Temporary IAccount logic) ---
        // TODO: Replace when MessageRepository is implemented.
        val msalAccount = getMsalAccountById(currentSelectedAccountId)
        if (msalAccount == null) {
            Log.e(
                TAG,
                "Cannot refresh messages: Failed to find IAccount for Account ID: $currentSelectedAccountId"
            )
            _uiState.update {
                it.copy(
                    messageDataState = DataState.ERROR,
                    messageError = "Internal account data error."
                )
            }
            tryEmitToastMessage("Error refreshing messages: Account mismatch.")
            return
        }
        Log.d(
            TAG,
            "Requesting message refresh for folder: ${currentSelectedFolder.id}, account: ${msalAccount.username}"
        )
        _uiState.update { it.copy(messageDataState = DataState.LOADING, messageError = null) }
        fetchMessagesInternal(
            currentSelectedFolder.id,
            msalAccount,
            isRefresh = true,
            activity = activity
        )
        // --- End Temporary Block ---
    }

    /**
     * Internal function to fetch messages.
     * TODO: Move this to MessageRepository.
     */
    private fun fetchMessagesInternal(
        folderId: String,
        account: IAccount, // Takes IAccount temporarily
        isRefresh: Boolean,
        activity: Activity?
    ) {
        val accountId = account.id ?: run {
            Log.e(TAG, "fetchMessagesInternal: IAccount ID is null.")
            _uiState.update {
                it.copy(
                    messageDataState = DataState.ERROR,
                    messageError = "Internal account error."
                )
            }
            return
        }
        if (accountId != _uiState.value.selectedFolderAccountId || folderId != _uiState.value.selectedFolder?.id) {
            Log.w(TAG, "fetchMessagesInternal ignored for $folderId/$accountId, selection changed.")
            if (_uiState.value.messageDataState == DataState.LOADING) {
                _uiState.update { it.copy(messageDataState = DataState.INITIAL) }
            }
            return
        }
        Log.d(TAG, "Fetching messages for $folderId/${account.username}")

        // Use the temporary token acquisition helper.
        acquireTokenAndExecute(
            account = account,
            activity = activity,
            scopes = mailReadScopes,
            onError = { errorMsg ->
                // Context Check inside callback
                if (_uiState.value.selectedFolderAccountId == accountId && _uiState.value.selectedFolder?.id == folderId) {
                    Log.e(TAG, "Error fetching messages (token/other): $errorMsg")
                    _uiState.update {
                        it.copy(
                            messageDataState = DataState.ERROR,
                            messageError = errorMsg
                        )
                    }
                    if (isRefresh) tryEmitToastMessage("Refresh failed: $errorMsg")
                } else {
                    Log.w(TAG, "Message fetch token error received, but context changed.")
                }
            }
        ) { accessToken -> // Token acquired successfully.
            // Context Check inside callback
            if (_uiState.value.selectedFolderAccountId == accountId && _uiState.value.selectedFolder?.id == folderId) {
                Log.d(TAG, "Got token for ${account.username}, fetching messages from Graph...")
                // Use GraphApiHelper directly (TODO: move to MessageRepository).
                val messagesResult = GraphApiHelper.getMessagesForFolder(
                    accessToken, folderId, messageListSelectFields, messageListPageSize
                )
                // Context Check *again* after network call.
                if (_uiState.value.selectedFolderAccountId == accountId && _uiState.value.selectedFolder?.id == folderId) {
                    _uiState.update { currentState ->
                        currentState.copy(
                            messageDataState = if (messagesResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                            messages = messagesResult.getOrNull(),
                            messageError = messagesResult.exceptionOrNull()
                                ?.let { mapGraphExceptionToUserMessage(it) }
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
                        tryEmitToastMessage(toastMsg)
                    }
                } else {
                    Log.w(TAG, "Message fetch graph result received, but context changed.")
                }
            } else {
                Log.w(TAG, "Message fetch token success callback ignored, context changed.")
            }
        }
    }


    // --- Temporary acquireTokenAndExecute Helper ---
    // TODO: Remove when MessageRepository handles token acquisition.
    /** Acquires token using MicrosoftAuthManager and executes an action. */
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
                            Log.e(TAG, "onSuccess (silent) failed:", e); onError?.invoke(
                                mapGraphExceptionToUserMessage(e)
                            )
                        }
                    }
                    is AcquireTokenResult.UiRequired -> {
                        if (activity != null) {
                            Log.w(
                                TAG,
                                "UI required for token, attempting interactive for ${account.username}"
                            )
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
                                                    "onSuccess (interactive) failed:",
                                                    e
                                                ); onError?.invoke(mapGraphExceptionToUserMessage(e))
                                            }
                                        }

                                        is AcquireTokenResult.Error -> onError?.invoke(
                                            mapAuthExceptionToUserMessage(interactiveResult.exception)
                                        )

                                        is AcquireTokenResult.Cancelled -> onError?.invoke("Authentication cancelled.") // Rely on result type
                                        else -> {
                                            Log.e(
                                                TAG,
                                                "Unexpected interactive result: $interactiveResult"
                                            ); onError?.invoke("Authentication failed (interactive).")
                                        }
                                    }
                                }
                            }
                        } else {
                            Log.e(
                                TAG,
                                "UI required for token but no Activity provided for ${account.username}"
                            )
                            onError?.invoke("Session expired. Please refresh or try again.")
                        }
                    }

                    is AcquireTokenResult.Error -> onError?.invoke(
                        mapAuthExceptionToUserMessage(
                            tokenResult.exception
                        )
                    )
                    // Handle cancellation based on the result type
                    is AcquireTokenResult.Cancelled -> onError?.invoke("Authentication cancelled.")
                    is AcquireTokenResult.NotInitialized -> onError?.invoke("Authentication system not ready.")
                    is AcquireTokenResult.NoAccountProvided -> onError?.invoke("Internal error: Account not provided.")
                    else -> {
                        Log.e(
                            TAG,
                            "Unexpected silent result: $tokenResult"
                        ); onError?.invoke("Unknown authentication error.")
                    }
                }
            }
        }
    }


    // --- Error Mapping Helpers ---
    // TODO: Centralize.

    /** Maps Graph API or network exceptions to user messages. */
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

    /** Maps MSAL exceptions to user messages. */
    private fun mapAuthExceptionToUserMessage(exception: MsalException): String {
        Log.w(
            TAG,
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        val code = exception.errorCode ?: "UNKNOWN"
        return when (exception) {
            // Check for specific exception types first
            is MsalUserCancelException -> "Authentication cancelled." // Handle specific cancellation type
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalClientException -> when (exception.errorCode) {
                // Use documented constant for no account found
                MsalClientException.NO_CURRENT_ACCOUNT -> "Account not found or session invalid."
                // Add checks for other specific MsalClientException codes based on documentation/needs
                // MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE -> "No active network is available." // Example
                // MsalClientException.BROKER_NOT_INSTALLED -> "Broker app required." // Example
                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: "Authentication client error ($code)"
            }

            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error ($code)"

            else -> exception.message?.takeIf { it.isNotBlank() } ?: "Authentication failed ($code)"
        }
    }

    // --- Temporary IAccount Helper ---
    // TODO: Remove when message fetching uses generic Account.
    /** Finds MSAL IAccount by ID via MicrosoftAuthManager. */
    internal fun getMsalAccountById(accountId: String?): IAccount? {
        if (accountId == null) return null
        return microsoftAuthManager.accounts.find { it.id == accountId }
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
