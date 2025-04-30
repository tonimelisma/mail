package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException // Ensure this is imported if used for parameter types
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
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
    INITIAL, LOADING, SUCCESS, ERROR
}

@Immutable
data class MainScreenState(
    // Auth State
    val isAuthInitialized: Boolean = false,
    val authInitializationError: MsalException? = null, // Keep raw error
    val authErrorUserMessage: String? = null, // User-friendly message <--- Ensure this exists
    val currentAccount: IAccount? = null,
    val isLoadingAuthAction: Boolean = false,

    // Folder State
    val folderDataState: DataState = DataState.INITIAL,
    val folders: List<MailFolder>? = null,
    val folderError: String? = null,
    val selectedFolder: MailFolder? = null,

    // Message State
    val messageDataState: DataState = DataState.INITIAL,
    val messages: List<Message>? = null,
    val messageError: String? = null,

    // General UI State
    val toastMessage: String? = null
)

class MainViewModel(
    private val applicationContext: Context,
    private val microsoftAuthManager: MicrosoftAuthManager
) : ViewModel(), AuthStateListener {

    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    // Constants for API calls
    private val mailReadScopes = listOf("User.Read", "Mail.Read")
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    private val messageListPageSize = 25

    // Connectivity check helper
    private fun isOnline(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return when {
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    init {
        microsoftAuthManager.setAuthStateListener(this)
        Log.d("MainViewModel", "ViewModel Initialized.")
    }

    // AuthStateListener Implementation
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
        _uiState.update { currentState ->
            val resetNeeded = (account != previousAccount) || !isInitialized
            val authUserError =
                if (isInitialized && error != null) mapAuthExceptionToUserMessage(error) else null // Map error here
            currentState.copy(
                isAuthInitialized = isInitialized,
                currentAccount = account,
                authInitializationError = error,
                authErrorUserMessage = authUserError, // Assign mapped message
                isLoadingAuthAction = false,
                // Reset dependent state if needed
                folders = if (resetNeeded) null else currentState.folders,
                folderError = if (resetNeeded) null else currentState.folderError,
                selectedFolder = if (resetNeeded) null else currentState.selectedFolder,
                messages = if (resetNeeded) null else currentState.messages,
                messageError = if (resetNeeded) null else currentState.messageError,
                folderDataState = if (resetNeeded) DataState.INITIAL else currentState.folderDataState,
                messageDataState = if (resetNeeded) DataState.INITIAL else currentState.messageDataState
            )
        }
        if (error != null && isInitialized) { // Show toast only if init finished with error
            _uiState.update { it.copy(toastMessage = "Authentication Error: ${it.authErrorUserMessage ?: "Unknown error"}") }
        }
    }

    // Sign In/Out Actions
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
        _uiState.update {
            it.copy( // Reset UI state immediately
                folders = null, messages = null, selectedFolder = null,
                folderError = null, messageError = null,
                folderDataState = DataState.INITIAL, messageDataState = DataState.INITIAL
            )
        }
        microsoftAuthManager.signOut { result ->
            // isAuthLoading handled by onAuthStateChanged
            val message = when (result) {
                is SignOutResult.Success -> "Signed Out"
                is SignOutResult.Error -> "Sign Out Error: ${result.exception.message}" // Keep detailed for now
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

    // Folder/Message Actions
    fun selectFolder(folder: MailFolder, activity: Activity) {
        if (folder.id == _uiState.value.selectedFolder?.id && _uiState.value.messageDataState != DataState.INITIAL) return
        Log.d("ViewModel", "Selecting folder: ${folder.displayName}")
        _uiState.update {
            it.copy(
                selectedFolder = folder, messages = null, messageError = null,
                messageDataState = DataState.LOADING // Set loading before fetch
            )
        }
        fetchMessagesInternal(folder.id, activity, isRefresh = false)
    }

    fun refreshFolders(activity: Activity) {
        if (_uiState.value.currentAccount == null || _uiState.value.folderDataState == DataState.LOADING) return
        Log.d("ViewModel", "Requesting folder refresh...")
        if (!isOnline()) {
            val errorMsg = "No internet connection"
            _uiState.update {
                it.copy(
                    folderDataState = DataState.ERROR,
                    folderError = errorMsg,
                    toastMessage = errorMsg
                )
            }
            Log.w("ViewModel", "Folder refresh skipped: Offline")
            return
        }
        _uiState.update { it.copy(folderDataState = DataState.LOADING, folderError = null) }
        fetchFoldersInternal(activity) { foldersResult ->
            val currentSelectedFolderId = _uiState.value.selectedFolder?.id
            var newSelectedFolder: MailFolder? = null
            val newFolders = foldersResult.getOrNull()
            if (currentSelectedFolderId != null && newFolders != null) {
                newSelectedFolder = newFolders.find { it.id == currentSelectedFolderId }
            }
            val finalError =
                foldersResult.exceptionOrNull()?.let { e -> mapExceptionToUserMessage(e) }
            _uiState.update {
                it.copy(
                    folderDataState = if (foldersResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                    folders = newFolders ?: it.folders, // Keep old list on error
                    folderError = finalError,
                    selectedFolder = newSelectedFolder ?: it.selectedFolder
                )
            }
            if (finalError != null) _uiState.update { state -> state.copy(toastMessage = finalError) }
            Log.d("ViewModel", "Folder refresh complete. Success: ${foldersResult.isSuccess}")
        }
    }

    fun refreshMessages(activity: Activity) {
        val folderId = _uiState.value.selectedFolder?.id
        if (folderId == null || _uiState.value.currentAccount == null || _uiState.value.messageDataState == DataState.LOADING) return
        Log.d("ViewModel", "Requesting message refresh for folder: $folderId")
        if (!isOnline()) {
            val errorMsg = "No internet connection"
            _uiState.update {
                it.copy(
                    messageDataState = DataState.ERROR,
                    messageError = errorMsg,
                    toastMessage = errorMsg
                )
            }
            Log.w("ViewModel", "Message refresh skipped: Offline")
            return
        }
        _uiState.update { it.copy(messageDataState = DataState.LOADING, messageError = null) }
        fetchMessagesInternal(folderId, activity, isRefresh = true)
    }

    // Internal Fetch Logic
    private fun fetchFoldersInternal(
        activity: Activity?,
        onResult: (Result<List<MailFolder>>) -> Unit
    ) {
        if (_uiState.value.currentAccount == null) {
            val errorMsg = "Not signed in."
            _uiState.update { it.copy(folderDataState = DataState.ERROR, folderError = errorMsg) }
            onResult(Result.failure(IllegalStateException(errorMsg)))
            return
        }
        acquireTokenAndExecute(
            activity, mailReadScopes,
            onError = { userErrorMessage ->
                // Don't update state here, let the caller (refreshFolders) do it based on Result
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
            if (isRefresh) _uiState.update { it.copy(toastMessage = errorMsg) }
            return
        }
        // State should already be LOADING if called from selectFolder or refreshMessages

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
                    accessToken = accessToken, folderId = folderId,
                    selectFields = messageListSelectFields, top = messageListPageSize
                )
                _uiState.update {
                    it.copy(
                        messageDataState = if (messagesResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                        messages = messagesResult.getOrNull(),
                        messageError = messagesResult.exceptionOrNull()
                            ?.let { e -> mapExceptionToUserMessage(e) }
                    )
                }
                if (messagesResult.isFailure) Log.e(
                    "ViewModel",
                    "Failed messages for $folderId",
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

    // Error Mapping (Improved cause checking)
    private fun mapExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            "ViewModel",
            "Mapping exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}")
        var currentException = exception
        var depth = 0
        while (currentException != null && depth < 10) { // Limit depth
            when (currentException) {
                is UnknownHostException -> return "No internet connection"
                is IOException -> return "Couldn't reach server"
            }
            // Specific check for MSAL exceptions wrapping network issues
            if (currentException is MsalClientException && currentException.errorCode == MsalClientException.IO_ERROR) {
                return "Network error during authentication"
            }
            if (currentException is MsalServiceException && currentException.cause is IOException) {
                return "Network error contacting authentication service"
            }
            currentException = currentException.cause
            depth++
        }
        // Fallback mapping for original exception if no specific cause found
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalException -> exception.message ?: "Authentication error"
            else -> exception?.message?.takeIf { it.isNotEmpty() } ?: "An unknown error occurred"
        }
    }

    // Auth Error Mapping (now mostly relies on the general mapper)
    private fun mapAuthExceptionToUserMessage(exception: MsalException): String {
        val generalMessage = mapExceptionToUserMessage(exception)
        // Return general mapping if it successfully identified a network issue or provided a message
        if (generalMessage != "An unknown error occurred" || generalMessage == exception.message) {
            return generalMessage
        }
        // Fallback for specific non-network Auth logical errors
        Log.w(
            "ViewModel",
            "Mapping specific auth exception (fallback): ${exception::class.java.simpleName} - ${exception.errorCode}"
        )
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            // Add other specific non-network MSAL error codes here if necessary
            else -> exception.message?.takeIf { it.isNotEmpty() } ?: "Authentication failed"
        }
    }

    // Token Acquisition (uses updated error mapping)
    private fun acquireTokenAndExecute(
        activity: Activity?,
        scopes: List<String>,
        onError: ((String) -> Unit)?, // The lambda to call with the user-friendly error message
        onSuccess: (String) -> Unit
    ) {
        val currentAccount = _uiState.value.currentAccount
        if (currentAccount == null) {
            onError?.invoke("Not signed in."); return
        }

        microsoftAuthManager.acquireTokenSilent(scopes) { tokenResult ->
            when (tokenResult) {
                is AcquireTokenResult.Success -> onSuccess(tokenResult.result.accessToken)
                is AcquireTokenResult.Error -> {
                    val userMessage =
                        mapAuthExceptionToUserMessage(tokenResult.exception) // Map first
                    onError?.invoke(userMessage) // Then call onError with mapped message
                }

                is AcquireTokenResult.UiRequired -> {
                    val userMessage = mapAuthExceptionToUserMessage(
                        MsalUiRequiredException(
                            "",
                            ""
                        )
                    ) // Map the type
                    onError?.invoke(userMessage)
                }

                is AcquireTokenResult.Cancelled -> onError?.invoke("Action cancelled.")
                is AcquireTokenResult.NotInitialized -> onError?.invoke("Auth service not ready.")
                is AcquireTokenResult.NoAccount -> onError?.invoke("Not signed in.")
            }
        }
    }

    // Toast Management
    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // Cleanup
    override fun onCleared() {
        super.onCleared()
        microsoftAuthManager.setAuthStateListener(null)
        Log.d("MainViewModel", "ViewModel Cleared.")
    }

    // Factory
    companion object {
        fun provideFactory(
            appContext: Context, // Expect Application Context
            authManager: MicrosoftAuthManager,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    return MainViewModel(appContext, authManager) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}