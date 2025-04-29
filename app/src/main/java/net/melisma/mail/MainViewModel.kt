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

// Defines the different states the UI can be in
@Immutable
data class MainScreenState(
    val isAuthInitialized: Boolean = false,
    val authInitializationError: MsalException? = null,
    val currentAccount: IAccount? = null,
    val isLoading: Boolean = false, // General loading indicator
    val isLoadingFolders: Boolean = false, // Specific loading for folders
    val folders: List<MailFolder>? = null,
    val folderError: String? = null, // Error message for folder fetching
    val toastMessage: String? = null // For showing transient messages
)

class MainViewModel(
    private val microsoftAuthManager: MicrosoftAuthManager
) : ViewModel(), AuthStateListener {

    private val _uiState = MutableStateFlow(
        MainScreenState(
            isAuthInitialized = microsoftAuthManager.isInitialized,
            authInitializationError = microsoftAuthManager.initializationError,
            currentAccount = microsoftAuthManager.currentAccount
        )
    )
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    private val mailScopes = listOf("User.Read", "Mail.Read") // Define scopes needed

    init {
        microsoftAuthManager.setAuthStateListener(this)
        Log.d(
            "MainViewModel",
            "ViewModel Initialized. Initial auth state: ${_uiState.value.isAuthInitialized}"
        )
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
        _uiState.update {
            it.copy(
                isAuthInitialized = isInitialized,
                currentAccount = account,
                authInitializationError = error,
                // Reset folder state if user logs out
                folders = if (account == null) null else it.folders,
                folderError = if (account == null) null else it.folderError
            )
        }
        if (error != null) {
            _uiState.update { it.copy(toastMessage = "Authentication library failed to initialize.") }
        }
    }

    // --- UI Actions ---

    fun signIn(activity: Activity) {
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Auth Service not ready.") }
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        microsoftAuthManager.signIn(activity, mailScopes) { result ->
            _uiState.update { it.copy(isLoading = false) } // Stop loading regardless of outcome
            val message = when (result) {
                is SignInResult.Success -> "Sign in Success: ${result.account.username}"
                is SignInResult.Error -> "Sign In Error: ${result.exception.message}"
                is SignInResult.Cancelled -> "Sign in Cancelled"
                is SignInResult.NotInitialized -> "Auth Service not ready."
            }
            _uiState.update { it.copy(toastMessage = message) }
            if (result is SignInResult.Error) {
                Log.e("MainViewModel", "Sign In Error", result.exception)
            }
        }
    }

    fun signOut() {
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Auth Service not ready.") }
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        microsoftAuthManager.signOut { result ->
            _uiState.update { it.copy(isLoading = false) } // Stop loading regardless of outcome
            val message = when (result) {
                is SignOutResult.Success -> "Signed Out"
                is SignOutResult.Error -> "Sign Out Error: ${result.exception.message}"
                is SignOutResult.NotInitialized -> "Auth Service not ready."
            }
            _uiState.update { it.copy(toastMessage = message) }
            if (result is SignOutResult.Error) {
                Log.e("MainViewModel", "Sign Out Error", result.exception)
            }
        }
    }

    fun fetchMailFolders(activity: Activity) {
        if (_uiState.value.currentAccount == null) {
            _uiState.update {
                it.copy(
                    toastMessage = "Not signed in.",
                    folderError = "Cannot fetch folders when not signed in."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isLoadingFolders = true,
                folderError = null,
                folders = null
            )
        } // Reset previous state

        // Try silent token acquisition first
        microsoftAuthManager.acquireTokenSilent(mailScopes) { tokenResult ->
            handleTokenResult(tokenResult, activity)
        }
    }

    // Process the result of token acquisition (silent or interactive)
    private fun handleTokenResult(tokenResult: AcquireTokenResult, activity: Activity) {
        when (tokenResult) {
            is AcquireTokenResult.Success -> {
                Log.d("MainViewModel", "Token acquired successfully.")
                // Launch coroutine to make the API call
                viewModelScope.launch {
                    val folderResult = GraphApiHelper.getMailFolders(tokenResult.result.accessToken)
                    _uiState.update {
                        it.copy(
                            isLoadingFolders = false,
                            folders = folderResult.getOrNull(),
                            folderError = folderResult.exceptionOrNull()?.message
                                ?: if (folderResult.isSuccess) null else "Unknown error fetching folders"
                        )
                    }
                    if (folderResult.isFailure) {
                        Log.e(
                            "MainViewModel",
                            "Failed to get folders",
                            folderResult.exceptionOrNull()
                        )
                        _uiState.update { it.copy(toastMessage = "Failed to get folders: ${folderResult.exceptionOrNull()?.message}") }
                    } else {
                        _uiState.update { it.copy(toastMessage = "Folders loaded.") }
                    }
                }
            }

            is AcquireTokenResult.UiRequired -> {
                Log.w("MainViewModel", "Silent token acquisition failed, UI interaction required.")
                // Optionally: Trigger interactive flow immediately
                // acquireTokenInteractive(activity)
                // For now, just show an error message
                _uiState.update {
                    it.copy(
                        isLoadingFolders = false,
                        folderError = "Could not get token silently. Please sign out and sign in again.",
                        toastMessage = "Authentication required. Please try signing in again."
                    )
                }
            }

            is AcquireTokenResult.Error -> {
                Log.e("MainViewModel", "Error acquiring token", tokenResult.exception)
                _uiState.update {
                    it.copy(
                        isLoadingFolders = false,
                        folderError = "Error getting token: ${tokenResult.exception.message}",
                        toastMessage = "Error getting credentials."
                    )
                }
            }

            is AcquireTokenResult.Cancelled -> {
                _uiState.update {
                    it.copy(
                        isLoadingFolders = false,
                        toastMessage = "Token request cancelled."
                    )
                }
            }

            is AcquireTokenResult.NotInitialized -> {
                _uiState.update {
                    it.copy(
                        isLoadingFolders = false,
                        folderError = "Auth not initialized.",
                        toastMessage = "Auth not ready."
                    )
                }
            }

            is AcquireTokenResult.NoAccount -> {
                _uiState.update {
                    it.copy(
                        isLoadingFolders = false,
                        folderError = "No account found.",
                        toastMessage = "Please sign in."
                    )
                }
            }
        }
    }

    // Call this if acquireTokenSilent fails with UiRequired
    // Currently not automatically called, user needs to re-login based on message
    fun acquireTokenInteractive(activity: Activity) {
        _uiState.update {
            it.copy(
                isLoadingFolders = true,
                folderError = null,
                folders = null
            )
        } // Reset previous state
        microsoftAuthManager.acquireTokenInteractive(activity, mailScopes) { tokenResult ->
            handleTokenResult(tokenResult, activity) // Reuse the same handler
        }
    }

    // Call this when a toast message has been shown
    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up listener to avoid leaks
        microsoftAuthManager.setAuthStateListener(null)
        Log.d("MainViewModel", "ViewModel Cleared, AuthStateListener removed.")
    }

    // Factory to create MainViewModel with MicrosoftAuthManager dependency
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