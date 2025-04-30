package net.melisma.mail

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.feature_auth.AcquireTokenResult
import net.melisma.feature_auth.AuthStateListener
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.feature_auth.SignInResult
import net.melisma.feature_auth.SignOutResult

@Immutable
data class MainScreenState(
    val isAuthInitialized: Boolean = false,
    val authInitializationError: MsalException? = null,
    val currentAccount: IAccount? = null,
    val isLoadingAuthAction: Boolean = false,
    val isLoadingFolders: Boolean = false,
    val folders: List<MailFolder>? = null,
    val folderError: String? = null,
    val selectedFolder: MailFolder? = null,
    val isLoadingMessages: Boolean = false,
    val messages: List<Message>? = null,
    val messageError: String? = null,
    val toastMessage: String? = null
)

class MainViewModel(
    private val microsoftAuthManager: MicrosoftAuthManager
) : ViewModel(), AuthStateListener {

    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    private val mailReadScopes = listOf("User.Read", "Mail.Read")
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25

    init {
        microsoftAuthManager.setAuthStateListener(this)
        Log.d(
            "MainViewModel",
            "ViewModel Initialized. Initial auth state: ${_uiState.value.isAuthInitialized}"
        )
        // Initial folder fetch is now triggered by LaunchedEffect in UI when account appears
    }

    // --- AuthStateListener Implementation ---
    override fun onAuthStateChanged(
        isInitialized: Boolean,
        account: IAccount?,
        error: MsalException?
    ) {
        Log.d(
            "MainViewModel",
            "onAuthStateChanged: init=$isInitialized, account=${account?.username}, error=$error"
        )
        val previousAccount = _uiState.value.currentAccount
        _uiState.update {
            it.copy(
                isAuthInitialized = isInitialized,
                currentAccount = account,
                authInitializationError = error,
                folders = if (account != previousAccount) null else it.folders,
                folderError = if (account != previousAccount) null else it.folderError,
                selectedFolder = if (account != previousAccount) null else it.selectedFolder,
                messages = if (account != previousAccount) null else it.messages,
                messageError = if (account != previousAccount) null else it.messageError,
                isLoadingFolders = false,
                isLoadingMessages = false
            )
        }

        if (error != null) {
            _uiState.update { it.copy(toastMessage = "Authentication library failed to initialize.") }
        }
        // NOTE: Initial folder fetch is now triggered by UI's LaunchedEffect
    }

    // --- Public Actions from UI ---

    fun signIn(activity: Activity) {
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Auth Service not ready.") }
            return
        }
        _uiState.update { it.copy(isLoadingAuthAction = true) }
        microsoftAuthManager.signIn(activity, mailReadScopes) { result ->
            _uiState.update { it.copy(isLoadingAuthAction = false) }
            val message = when (result) {
                is SignInResult.Success -> "Sign in Success: ${result.account.username}"
                is SignInResult.Error -> "Sign In Error: ${result.exception.message}"
                is SignInResult.Cancelled -> "Sign in Cancelled"
                is SignInResult.NotInitialized -> "Auth Service not ready."
            }
            _uiState.update { it.copy(toastMessage = message) }
            if (result is SignInResult.Error) Log.e(
                "MainViewModel",
                "Sign In Error",
                result.exception
            )
        }
    }

    fun signOut(activity: Activity) {
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Auth Service not ready.") }
            return
        }
        _uiState.update { it.copy(isLoadingAuthAction = true) }
        microsoftAuthManager.signOut { result ->
            _uiState.update { it.copy(isLoadingAuthAction = false) }
            val message = when (result) {
                is SignOutResult.Success -> "Signed Out"
                is SignOutResult.Error -> "Sign Out Error: ${result.exception.message}"
                is SignOutResult.NotInitialized -> "Auth Service not ready."
            }
            _uiState.update { it.copy(toastMessage = message) }
            if (result is SignOutResult.Error) Log.e(
                "MainViewModel",
                "Sign Out Error",
                result.exception
            )
        }
    }

    // Called when a folder is selected in the UI (Drawer)
    fun selectFolder(folder: MailFolder, activity: Activity) {
        if (folder.id == _uiState.value.selectedFolder?.id) {
            Log.d("ViewModel", "Folder ${folder.displayName} already selected.")
            // Optionally trigger a refresh even if selected?
            // refreshMessages(activity)
            return
        }

        Log.d("ViewModel", "Selecting folder: ${folder.displayName} (ID: ${folder.id})")
        _uiState.update {
            it.copy(
                selectedFolder = folder,
                messages = null, // Clear previous messages immediately
                messageError = null
            )
        }
        // Fetch messages for the newly selected folder
        fetchMessagesForFolderInternal(folder.id, activity) { result ->
            _uiState.update { it.copy(isLoadingMessages = false) }
            // State (messages or error) was updated inside fetchMessagesForFolderInternal
            if (result.isFailure) {
                _uiState.update { it.copy(toastMessage = "Failed to load messages for ${folder.displayName}") }
            }
        }
    }

    // Called when user triggers folder refresh (e.g., pull-to-refresh on drawer?)
    fun refreshFolders(activity: Activity) {
        if (_uiState.value.currentAccount == null || _uiState.value.isLoadingFolders) return
        Log.d("ViewModel", "Refreshing folders...")
        _uiState.update { it.copy(isLoadingFolders = true, folderError = null) }
        fetchFoldersInternal(activity) { foldersResult ->
            val currentSelectedFolderId = _uiState.value.selectedFolder?.id
            var newSelectedFolder: MailFolder? = null
            // Try to find the previously selected folder in the new list by ID
            if (currentSelectedFolderId != null) {
                newSelectedFolder =
                    foldersResult.getOrNull()?.find { it.id == currentSelectedFolderId }
            }
            // Update state with new folders and potentially updated selected folder reference
            _uiState.update {
                it.copy(
                    isLoadingFolders = false,
                    folders = foldersResult.getOrNull()
                        ?: it.folders, // Keep old if error? or clear?
                    folderError = foldersResult.exceptionOrNull()?.message,
                    selectedFolder = newSelectedFolder
                        ?: it.selectedFolder // Keep old selection if not found? Or clear?
                )
            }
            Log.d("ViewModel", "Folder refresh complete. Result: ${foldersResult.isSuccess}")
        }
    }

    // Called when user triggers message refresh (e.g., pull-to-refresh on message list)
    fun refreshMessages(activity: Activity) {
        val folderId = _uiState.value.selectedFolder?.id
        if (folderId == null || _uiState.value.currentAccount == null || _uiState.value.isLoadingMessages) return
        Log.d("ViewModel", "Refreshing messages for folder: $folderId")
        _uiState.update { it.copy(isLoadingMessages = true, messageError = null) }
        fetchMessagesForFolderInternal(folderId, activity) {
            _uiState.update { it.copy(isLoadingMessages = false) }
            Log.d("ViewModel", "Message refresh complete for folder $folderId")
        }
    }


    // --- Internal Data Fetching Logic ---

    // Fetches folders, NO LONGER automatically selects Inbox or fetches messages.
    private fun fetchFoldersInternal(
        activity: Activity?,
        onResult: (Result<List<MailFolder>>) -> Unit
    ) {
        if (_uiState.value.currentAccount == null) {
            onResult(Result.failure(IllegalStateException("Not signed in.")))
            return
        }
        // Use acquireTokenAndExecute helper
        acquireTokenAndExecute(
            activity, mailReadScopes,
            onError = { errMsg ->
                _uiState.update { it.copy(isLoadingFolders = false, folderError = errMsg) }
                onResult(Result.failure(Exception(errMsg)))
            }
        ) { accessToken -> // onSuccess
            viewModelScope.launch {
                Log.d("ViewModel", "Got token, fetching folders from Graph...")
                val foldersResult = GraphApiHelper.getMailFolders(accessToken)
                _uiState.update {
                    it.copy(
                        // Update folders state, keep old on error for now? Or clear? Let's clear on error.
                        folders = foldersResult.getOrNull(),
                        folderError = foldersResult.exceptionOrNull()?.message
                        // Do not update isLoadingFolders here, let the caller do it
                    )
                }
                if (foldersResult.isFailure) Log.e(
                    "ViewModel",
                    "Failed to get folders",
                    foldersResult.exceptionOrNull()
                )
                onResult(foldersResult) // Notify caller with the result
            }
        }
    }

    // Fetches messages for a given folder ID
    private fun fetchMessagesForFolderInternal(
        folderId: String,
        activity: Activity?,
        onResult: (Result<List<Message>>) -> Unit
    ) {
        if (_uiState.value.currentAccount == null) {
            onResult(Result.failure(IllegalStateException("Not signed in.")))
            return
        }
        // Ensure loading state is set before starting async operation
        _uiState.update { it.copy(isLoadingMessages = true, messageError = null) }

        acquireTokenAndExecute(
            activity, mailReadScopes,
            onError = { errMsg ->
                _uiState.update { it.copy(isLoadingMessages = false, messageError = errMsg) }
                onResult(Result.failure(Exception(errMsg)))
            }
        ) { accessToken -> // onSuccess
            viewModelScope.launch {
                Log.d(
                    "ViewModel",
                    "Got token, fetching messages for folder $folderId from Graph..."
                )
                val messagesResult = GraphApiHelper.getMessagesForFolder(
                    accessToken = accessToken,
                    folderId = folderId,
                    selectFields = messageListSelectFields,
                    top = messageListPageSize
                )
                _uiState.update {
                    it.copy(
                        messages = messagesResult.getOrNull(), // Clear messages on error
                        messageError = messagesResult.exceptionOrNull()?.message
                        // Do not update isLoadingMessages here, let the caller do it
                    )
                }
                if (messagesResult.isFailure) Log.e(
                    "ViewModel",
                    "Failed to get messages for $folderId",
                    messagesResult.exceptionOrNull()
                )
                onResult(messagesResult) // Notify caller
            }
        }
    }


    // Helper to acquire token (handling UI required if activity is present)
    private fun acquireTokenAndExecute(
        activity: Activity?,
        scopes: List<String>,
        onError: ((String) -> Unit)? = { errorMessage ->
            _uiState.update {
                it.copy(
                    isLoadingFolders = false,
                    isLoadingMessages = false,
                    toastMessage = errorMessage
                )
            }
        },
        onSuccess: (String) -> Unit
    ) {
        // Check if account is available first
        val currentAccount = _uiState.value.currentAccount
        if (currentAccount == null) {
            Log.e("ViewModel", "acquireTokenAndExecute: No account available.")
            onError?.invoke("Not signed in.")
            return
        }

        microsoftAuthManager.acquireTokenSilent(scopes) { tokenResult ->
            when (tokenResult) {
                is AcquireTokenResult.Success -> {
                    onSuccess(tokenResult.result.accessToken)
                }

                is AcquireTokenResult.UiRequired -> {
                    Log.w("ViewModel", "Silent token acquisition failed, UI interaction required.")
                    // Don't automatically trigger interactive flow here, let UI decide or prompt user
                    onError?.invoke("Authentication session expired. Please try selecting the folder again, or sign out and back in.")
                    // If activity was available, could potentially trigger interactive here, but needs careful state management
                    // if (activity != null) { microsoftAuthManager.acquireTokenInteractive(...) }
                }

                is AcquireTokenResult.Error -> {
                    Log.e("ViewModel", "Error acquiring token", tokenResult.exception)
                    onError?.invoke("Error getting credentials: ${tokenResult.exception.message}")
                }

                is AcquireTokenResult.Cancelled -> onError?.invoke("Token request cancelled.")
                is AcquireTokenResult.NotInitialized -> onError?.invoke("Auth not ready.")
                is AcquireTokenResult.NoAccount -> onError?.invoke("Please sign in.") // Should not happen if initial check passed
            }
        }
    }

    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        microsoftAuthManager.setAuthStateListener(null)
        Log.d("MainViewModel", "ViewModel Cleared, AuthStateListener removed.")
    }

    companion object {
        fun provideFactory(
            authManager: MicrosoftAuthManager,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    return MainViewModel(authManager) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}