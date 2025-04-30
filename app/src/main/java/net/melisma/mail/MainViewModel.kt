package net.melisma.mail

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException // Correct import
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
import java.io.IOException
import java.net.UnknownHostException

// Represents the possible UI states for data lists
enum class DataState {
    INITIAL, // Not yet loaded
    LOADING, // Actively loading (initial or refresh)
    SUCCESS, // Loaded successfully (might be empty)
    ERROR    // Failed to load
}

@Immutable
data class MainScreenState(
    // Auth State
    val isAuthInitialized: Boolean = false,
    val authInitializationError: MsalException? = null,
    val currentAccount: IAccount? = null,
    val isLoadingAuthAction: Boolean = false, // For Sign In/Out

    // Folder State
    val folderDataState: DataState = DataState.INITIAL,
    val folders: List<MailFolder>? = null,
    val folderError: String? = null, // User-friendly error message
    val selectedFolder: MailFolder? = null, // Track the selected folder

    // Message State
    val messageDataState: DataState = DataState.INITIAL,
    val messages: List<Message>? = null,
    val messageError: String? = null, // User-friendly error message

    // General UI State
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
    }

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
                isLoadingAuthAction = false,
                folderDataState = if (account != previousAccount) DataState.INITIAL else it.folderDataState,
                messageDataState = if (account != previousAccount) DataState.INITIAL else it.messageDataState
            )
        }
        if (error != null) _uiState.update { it.copy(toastMessage = "Auth library init failed.") }
    }

    fun signIn(activity: Activity) {
        if (!_uiState.value.isAuthInitialized) return _uiState.update { it.copy(toastMessage = "Auth Service not ready.") }
        _uiState.update { it.copy(isLoadingAuthAction = true) }
        microsoftAuthManager.signIn(activity, mailReadScopes) { result ->
            _uiState.update { it.copy(isLoadingAuthAction = false) }
            val message = when (result) {
                is SignInResult.Success -> "Signed in: ${result.account.username}"
                is SignInResult.Error -> mapAuthExceptionToUserMessage(result.exception)
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
        if (!_uiState.value.isAuthInitialized) return _uiState.update { it.copy(toastMessage = "Auth Service not ready.") }
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

    fun selectFolder(folder: MailFolder, activity: Activity) {
        if (folder.id == _uiState.value.selectedFolder?.id && _uiState.value.messageDataState != DataState.INITIAL) {
            Log.d("ViewModel", "Folder ${folder.displayName} already selected and not initial.")
            // Maybe trigger refresh if user re-selects?
            // refreshMessages(activity)
            return
        }
        Log.d("ViewModel", "Selecting folder: ${folder.displayName} (ID: ${folder.id})")
        _uiState.update {
            it.copy(
                selectedFolder = folder,
                messages = null,
                messageError = null,
                messageDataState = DataState.LOADING
            )
        }
        fetchMessagesInternal(folder.id, activity, isRefresh = false)
    }

    fun refreshFolders(activity: Activity) {
        if (_uiState.value.currentAccount == null || _uiState.value.folderDataState == DataState.LOADING) return
        Log.d("ViewModel", "Refreshing folders...")
        _uiState.update { it.copy(folderDataState = DataState.LOADING, folderError = null) }
        fetchFoldersInternal(activity) { foldersResult ->
            val currentSelectedFolderId = _uiState.value.selectedFolder?.id
            var newSelectedFolder: MailFolder? = null
            val newFolders = foldersResult.getOrNull() // Get the potentially new list
            if (currentSelectedFolderId != null && newFolders != null) {
                newSelectedFolder = newFolders.find { it.id == currentSelectedFolderId }
            }
            _uiState.update {
                it.copy(
                    // Update state based on the result
                    folderDataState = if (foldersResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                    // Store the new list on success, otherwise keep the old one? Or clear? Let's update on success.
                    folders = newFolders ?: it.folders,
                    folderError = foldersResult.exceptionOrNull()
                        ?.let { e -> mapExceptionToUserMessage(e) },
                    // Update selected folder reference if found in new list, otherwise keep old
                    selectedFolder = newSelectedFolder ?: it.selectedFolder
                )
            }
            Log.d("ViewModel", "Folder refresh complete. Success: ${foldersResult.isSuccess}")
        }
    }

    fun refreshMessages(activity: Activity) {
        val folderId = _uiState.value.selectedFolder?.id
        if (folderId == null || _uiState.value.currentAccount == null || _uiState.value.messageDataState == DataState.LOADING) return
        Log.d("ViewModel", "Refreshing messages for folder: $folderId")
        _uiState.update { it.copy(messageDataState = DataState.LOADING, messageError = null) }
        fetchMessagesInternal(folderId, activity, isRefresh = true)
    }

    private fun fetchFoldersInternal(
        activity: Activity?,
        onResult: (Result<List<MailFolder>>) -> Unit
    ) {
        if (_uiState.value.currentAccount == null) {
            val errorMsg = "Not signed in."
            _uiState.update {
                it.copy(
                    folderDataState = DataState.ERROR,
                    folderError = errorMsg
                )
            } // Update state immediately
            onResult(Result.failure(IllegalStateException(errorMsg)))
            return
        }
        acquireTokenAndExecute(
            activity, mailReadScopes,
            onError = { userErrorMessage ->
                _uiState.update {
                    it.copy(
                        folderDataState = DataState.ERROR,
                        folderError = userErrorMessage
                    )
                }
                onResult(Result.failure(Exception(userErrorMessage)))
            }
        ) { accessToken ->
            viewModelScope.launch {
                Log.d("ViewModel", "Got token, fetching folders from Graph...")
                val foldersResult = GraphApiHelper.getMailFolders(accessToken)
                onResult(foldersResult)
            }
        }
    }

    private fun fetchMessagesInternal(folderId: String, activity: Activity?, isRefresh: Boolean) {
        if (_uiState.value.currentAccount == null) {
            val errorMsg = "Not signed in."
            _uiState.update { it.copy(messageDataState = DataState.ERROR, messageError = errorMsg) }
            return
        }
        // Ensure loading state is set (should already be done by callers selectFolder/refreshMessages)
        if (_uiState.value.messageDataState != DataState.LOADING) {
            _uiState.update { it.copy(messageDataState = DataState.LOADING, messageError = null) }
        }

        acquireTokenAndExecute(
            activity, mailReadScopes,
            onError = { userErrorMessage ->
                _uiState.update {
                    it.copy(
                        messageDataState = DataState.ERROR,
                        messageError = userErrorMessage
                    )
                }
                if (isRefresh) _uiState.update { it.copy(toastMessage = userErrorMessage) }
            }
        ) { accessToken ->
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
                        // Update state based on result
                        messageDataState = if (messagesResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                        messages = messagesResult.getOrNull(), // Set messages on success, null on error
                        messageError = messagesResult.exceptionOrNull()
                            ?.let { e -> mapExceptionToUserMessage(e) }
                    )
                }
                if (messagesResult.isFailure) Log.e(
                    "ViewModel",
                    "Failed to get messages for $folderId",
                    messagesResult.exceptionOrNull()
                )
                if (isRefresh) _uiState.update {
                    it.copy(
                        toastMessage = if (messagesResult.isSuccess) "Messages updated" else _uiState.value.messageError
                            ?: "Couldn't refresh messages"
                    )
                }
            }
        }
    }

    private fun mapExceptionToUserMessage(exception: Throwable): String {
        Log.w(
            "ViewModel",
            "Mapping exception: ${exception::class.java.simpleName} - ${exception.message}"
        )
        return when (exception) {
            is UnknownHostException -> "No internet connection"
            is IOException -> "Couldn't reach server"
            else -> exception.message?.takeIf { it.isNotEmpty() } ?: "An unknown error occurred"
        }
    }

    private fun mapAuthExceptionToUserMessage(exception: MsalException): String {
        Log.w(
            "ViewModel",
            "Mapping auth exception: ${exception::class.java.simpleName} - ${exception.errorCode} - ${exception.message}"
        )
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry action or sign out/in."
            else -> exception.message?.takeIf { it.isNotEmpty() } ?: "Authentication failed"
        }
    }

    private fun acquireTokenAndExecute(
        activity: Activity?,
        scopes: List<String>,
        onError: ((String) -> Unit)? = { errorMessage -> _uiState.update { it.copy(toastMessage = errorMessage) } },
        onSuccess: (String) -> Unit
    ) {
        val currentAccount = _uiState.value.currentAccount
        if (currentAccount == null) {
            onError?.invoke("Not signed in."); return
        }

        microsoftAuthManager.acquireTokenSilent(scopes) { tokenResult ->
            when (tokenResult) {
                is AcquireTokenResult.Success -> onSuccess(tokenResult.result.accessToken)
                is AcquireTokenResult.UiRequired -> {
                    Log.w("ViewModel", "Silent token acquisition failed, UI interaction required.")
                    onError?.invoke("Session expired. Please retry action.")
                }
                is AcquireTokenResult.Error -> {
                    val userMessage = mapAuthExceptionToUserMessage(tokenResult.exception)
                    onError?.invoke("Auth Error: $userMessage")
                }
                is AcquireTokenResult.Cancelled -> onError?.invoke("Token request cancelled.")
                is AcquireTokenResult.NotInitialized -> onError?.invoke("Auth not ready.")
                is AcquireTokenResult.NoAccount -> onError?.invoke("Please sign in.")
            }
        }
    }

    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        microsoftAuthManager.setAuthStateListener(null)
        Log.d("MainViewModel", "ViewModel Cleared.")
    }

    // Companion object for Factory
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