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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.melisma.feature_auth.AcquireTokenResult
import net.melisma.feature_auth.AddAccountResult
import net.melisma.feature_auth.AuthStateListener
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.feature_auth.RemoveAccountResult
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject

// Represents the possible states for asynchronous data loading operations.
enum class DataState {
    INITIAL, LOADING, SUCCESS, ERROR
}

// Represents the state of fetching mail folders for a single account.
sealed class FolderFetchState {
    /** Indicates that folders for an account are currently being loaded. */
    data object Loading : FolderFetchState()
    /** Indicates that folders were successfully loaded. */
    data class Success(val folders: List<MailFolder>) : FolderFetchState()
    /** Indicates that an error occurred while loading folders. */
    data class Error(val error: String) : FolderFetchState()
}

/**
 * Represents the overall state of the main application screen.
 * It is immutable to encourage unidirectional data flow and ensure state consistency.
 */
@Immutable
data class MainScreenState(
    // --- Authentication State ---
    /** True if the MSAL authentication library has been successfully initialized. */
    val isAuthInitialized: Boolean = false,
    /** Holds any exception that occurred during MSAL initialization. */
    val authInitializationError: MsalException? = null,
    /** A user-friendly message derived from authentication errors. */
    val authErrorUserMessage: String? = null,
    /** The list of currently authenticated Microsoft accounts. */
    val accounts: List<IAccount> = emptyList(),
    /** True if an asynchronous account operation (add/remove) is in progress. */
    val isLoadingAuthAction: Boolean = false,

    // --- Folder State ---
    /** A map where keys are account IDs and values represent the fetch state of folders for that account. */
    val foldersByAccountId: Map<String, FolderFetchState> = emptyMap(),

    // --- Selection State ---
    /** The unique ID of the account that owns the currently selected folder. Null if no folder or a unified folder is selected. */
    val selectedFolderAccountId: String? = null,
    /** The currently selected mail folder object. Null if no folder is selected. */
    val selectedFolder: MailFolder? = null,

    // --- Message State (relevant to the selected folder) ---
    /** The current loading state for messages within the selected folder. */
    val messageDataState: DataState = DataState.INITIAL,
    /** The list of messages currently displayed (for the selected folder). Null if not loaded or cleared. */
    val messages: List<Message>? = null,
    /** An error message related to fetching messages for the selected folder. */
    val messageError: String? = null,

    // --- General UI State ---
    /** A message intended to be shown briefly to the user (e.g., in a Snackbar or Toast). */
    val toastMessage: String? = null
)

/**
 * The ViewModel responsible for managing the state and business logic for the main
 * email Browse interface.
 * Marked with @HiltViewModel to allow Hilt to provide instances of this ViewModel
 * and inject its dependencies.
 *
 * @property applicationContext Provided by Hilt, used for system services like ConnectivityManager.
 * @property microsoftAuthManager Provided by Hilt (via AuthModule), handles interactions with MSAL.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    // Use @ApplicationContext to tell Hilt to inject the application context.
    @ApplicationContext private val applicationContext: Context,
    // Hilt will inject this dependency based on the @Provides function in a Hilt Module (e.g., AuthModule).
    private val microsoftAuthManager: MicrosoftAuthManager
) : ViewModel(), AuthStateListener {

    // Log tag for this ViewModel.
    private val TAG = "MainViewModel"

    // Specific tag for debugging default selection logic.
    private val TAG_DEBUG_DEFAULT_SELECT = "MailAppDebug"

    // Private mutable state flow that holds the current UI state. Only the ViewModel can update it.
    private val _uiState = MutableStateFlow(MainScreenState())
    // Publicly exposed, read-only state flow for the UI to observe.
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()

    // Stores active background jobs fetching folders, keyed by account ID, to prevent duplicates and allow cancellation.
    private val folderFetchJobs = mutableMapOf<String, Job>()
    // Stores the job responsible for coordinating the initial folder fetches after authentication, used to trigger default folder selection upon completion.
    private var initialFolderLoadJob: Job? = null

    // List of permission scopes required for reading user profile and mail via Microsoft Graph API.
    private val mailReadScopes = listOf("User.Read", "Mail.Read")
    // Specific fields to request when fetching messages for the list view, optimizing the API call.
    private val messageListSelectFields = listOf(
        "id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview"
    )
    // Maximum number of messages to retrieve in a single API request for pagination.
    private val messageListPageSize = 25

    /** Checks if the device currently has an active network connection (Wi-Fi, Cellular, Ethernet). */
    private fun isOnline(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Use activeNetwork for modern API levels.
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            // Note: Could add other transports like VPN, Bluetooth if needed.
            else -> false
        }
    }

    // The init block runs when the ViewModel is created.
    init {
        // Register this ViewModel to listen for changes in authentication state from the injected MicrosoftAuthManager.
        microsoftAuthManager.setAuthStateListener(this)
        Log.d(TAG, "ViewModel Initialized by Hilt.")
    }

    /**
     * Callback from [AuthStateListener]. This function is invoked by [MicrosoftAuthManager]
     * whenever the authentication state changes (e.g., initialization completes,
     * accounts are added or removed). It updates the [MainScreenState] accordingly
     * and triggers necessary follow-up actions like fetching folders.
     *
     * @param isInitialized True if MSAL initialization was successful.
     * @param accounts The current list of authenticated accounts.
     * @param error An exception if MSAL initialization failed, otherwise null.
     */
    override fun onAuthStateChanged(
        isInitialized: Boolean,
        accounts: List<IAccount>,
        error: MsalException?
    ) {
        Log.d(
            TAG_DEBUG_DEFAULT_SELECT,
            "onAuthStateChanged: isInitialized=$isInitialized, accounts=${accounts.size}, error=$error"
        )

        // Store previous state values to detect changes.
        val previousAccounts = _uiState.value.accounts
        val previousSelectedFolderAccountId = _uiState.value.selectedFolderAccountId
        // Flag to determine if default folder selection logic should run after this state update.
        var needsDefaultFolderSelection = false

        // Update the UI state atomically based on the new authentication information.
        _uiState.update { currentState ->
            // Map initialization error to a user-friendly message if needed.
            val authUserError =
                if (isInitialized && error != null) mapAuthExceptionToUserMessage(error) else null
            // Find account IDs that were present before but are not now.
            val removedAccountIds = previousAccounts.map { it.id } - accounts.map { it.id }.toSet()
            // Filter the folder state map to only include accounts that still exist.
            val newFoldersByAccount =
                currentState.foldersByAccountId.filterKeys { it !in removedAccountIds }
                    .toMutableMap()
            // Determine if the currently selected folder should be cleared.
            val clearSelectedFolder = !isInitialized || // Clear if auth not initialized
                    (previousSelectedFolderAccountId != null && previousSelectedFolderAccountId in removedAccountIds) // Clear if selected account was removed

            // Check if a default folder needs to be selected after this update.
            needsDefaultFolderSelection = isInitialized && // Only if auth is ready
                    accounts.isNotEmpty() && // Only if there are accounts
                    (clearSelectedFolder || currentState.selectedFolder == null) // If selection was cleared or never set

            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "onAuthStateChanged update: clearSelectedFolder=$clearSelectedFolder, needsDefaultFolderSelection=$needsDefaultFolderSelection"
            )

            // Return the new state object.
            currentState.copy(
                isAuthInitialized = isInitialized,
                accounts = accounts,
                authInitializationError = error,
                authErrorUserMessage = authUserError,
                isLoadingAuthAction = false, // Assume any pending add/remove action is now complete.
                foldersByAccountId = newFoldersByAccount,
                // Clear selection and message list if needed.
                selectedFolder = if (clearSelectedFolder) null else currentState.selectedFolder,
                selectedFolderAccountId = if (clearSelectedFolder) null else currentState.selectedFolderAccountId,
                messageDataState = if (clearSelectedFolder) DataState.INITIAL else currentState.messageDataState,
                messages = if (clearSelectedFolder) null else currentState.messages,
                messageError = if (clearSelectedFolder) null else currentState.messageError
            )
        }

        // If initialization succeeded but produced an error (e.g., loading accounts failed), show a toast.
        if (error != null && isInitialized) {
            _uiState.update { it.copy(toastMessage = "Authentication Error: ${it.authErrorUserMessage ?: "Unknown error"}") }
        }

        // Perform actions only if authentication is successfully initialized.
        if (isInitialized) {
            // Cancel any running folder fetch jobs for accounts that have been removed.
            folderFetchJobs.filterKeys { it !in accounts.map { acc -> acc.id } }
                .forEach { (id, job) ->
                    Log.d(TAG, "Cancelling folder fetch job for removed account $id")
                    job.cancel()
                    folderFetchJobs.remove(id)
                }

            // For each current account, launch a job to fetch its folders if they aren't already loaded or failed.
            val fetchJobs = mutableListOf<Job>()
            accounts.forEach { account ->
                val currentState = _uiState.value.foldersByAccountId[account.id]
                if (currentState == null || currentState is FolderFetchState.Error) {
                    // Launch and track the job. `launchFolderFetch` handles preventing duplicates.
                    // Pass null activity as this is triggered internally, not direct user action requiring UI.
                    val job = launchFolderFetch(account, activity = null, forceRefresh = false)
                    fetchJobs.add(job)
                }
            }

            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "onAuthStateChanged: needsDefaultFolderSelection=$needsDefaultFolderSelection, fetchJobs started=${fetchJobs.size}"
            )

            // If default selection logic needs to run AND new folder fetch jobs were started,
            // wait for those jobs to complete before attempting selection.
            if (needsDefaultFolderSelection && fetchJobs.isNotEmpty()) {
                initialFolderLoadJob?.cancel() // Cancel any previous waiting job.
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "Launching initialFolderLoadJob to wait for ${fetchJobs.size} jobs."
                )
                initialFolderLoadJob = viewModelScope.launch {
                    Log.d(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "initialFolderLoadJob: Waiting for ${fetchJobs.size} fetch jobs to join..."
                    )
                    try {
                        // Wait for all the launched folder fetch jobs to complete.
                        fetchJobs.forEach { it.join() }
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "initialFolderLoadJob: Fetch jobs joined successfully."
                        )
                    } catch (e: CancellationException) {
                        Log.w(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "initialFolderLoadJob: Waiting job cancelled.",
                            e
                        )
                        throw e // Re-throw cancellation
                    } catch (e: Exception) {
                        Log.e(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "initialFolderLoadJob: Error joining fetch jobs.",
                            e
                        )
                        // Error joining, likely cannot select default folder reliably.
                    }
                    // Attempt default selection now that the initial fetches are complete (or failed).
                    selectDefaultFolderIfNeeded()
                }
            } else if (needsDefaultFolderSelection) {
                // If selection is needed but no new fetches were started (folders already loaded),
                // attempt selection immediately.
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "onAuthStateChanged: No new fetch jobs needed, calling selectDefaultFolderIfNeeded directly."
                )
                selectDefaultFolderIfNeeded()
            }

        } else {
            // If authentication is not initialized, cancel all ongoing fetch jobs.
            initialFolderLoadJob?.cancel()
            folderFetchJobs.values.forEach { it.cancel() }
            folderFetchJobs.clear()
        }
    }

    /**
     * Launches a coroutine job to fetch mail folders for a given account.
     * This job manages its own lifecycle, updates UI state ([FolderFetchState]),
     * and handles token acquisition and Graph API calls internally.
     *
     * @param account The account for which to fetch folders.
     * @param activity The Activity context, potentially needed for interactive token acquisition.
     * @param forceRefresh If true, cancels any ongoing fetch for this account and starts a new one.
     * @return The [Job] representing the fetch operation.
     */
    private fun launchFolderFetch(
        account: IAccount,
        activity: Activity?,
        forceRefresh: Boolean = false
    ): Job {
        val accountId = account.id
        // If forcing a refresh, cancel any existing job for this account first.
        if (forceRefresh) {
            folderFetchJobs[accountId]?.cancel(CancellationException("Forced refresh requested"))
        }
        // If a job is already active for this account and not forcing refresh, return the existing job.
        if (folderFetchJobs[accountId]?.isActive == true) {
            Log.d(TAG, "Folder fetch already in progress for ${account.username}")
            return folderFetchJobs[accountId]!!
        }
        val currentFolderState = _uiState.value.foldersByAccountId[accountId]
        // If folders are already successfully loaded and not forcing refresh, return a completed job.
        if (!forceRefresh && currentFolderState is FolderFetchState.Success) {
            Log.d(TAG, "Folders already loaded for ${account.username}, skipping fetch.")
            return Job().apply { complete() } // Represents an already completed operation.
        }

        Log.d(TAG, "Starting folder fetch for ${account.username} (Force: $forceRefresh)")
        // Update the state for this account to Loading.
        _uiState.update { it.copy(foldersByAccountId = it.foldersByAccountId + (accountId to FolderFetchState.Loading)) }

        // Launch the coroutine for the fetch operation.
        val job = viewModelScope.launch {
            val completionSignal = CompletableDeferred<Unit>()
            val jobDescription = "FolderFetch Job for ${account.username}"
            Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Outer job started.")

            try {
                if (!isOnline()) {
                    throw IOException("No internet connection")
                }
                // Acquire token and execute the Graph API call inside the success lambda.
                acquireTokenAndExecute(
                    account = account, activity = activity, scopes = mailReadScopes,
                    onError = { userErrorMessage ->
                        // Update state to Error on failure.
                        _uiState.update { s ->
                            s.copy(
                                foldersByAccountId = s.foldersByAccountId + (accountId to FolderFetchState.Error(
                                    userErrorMessage
                                ))
                            )
                        }
                        Log.e(TAG, "$jobDescription: Error - $userErrorMessage")
                        Log.e(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "$jobDescription: Completing deferred due to error."
                        )
                        completionSignal.complete(Unit) // Signal completion (failure).
                    }
                ) { accessToken ->
                    // Token acquired successfully, now fetch folders.
                    Log.d(TAG, "Got token for ${account.username}, fetching folders from Graph...")
                    val foldersResult = GraphApiHelper.getMailFolders(accessToken)
                    // Determine the new state based on the API result.
                    val newState = if (foldersResult.isSuccess) {
                        FolderFetchState.Success(foldersResult.getOrThrow())
                    } else {
                        FolderFetchState.Error(mapExceptionToUserMessage(foldersResult.exceptionOrNull()))
                    }
                    // Update UI state with the result.
                    _uiState.update { s -> s.copy(foldersByAccountId = s.foldersByAccountId + (accountId to newState)) }

                    if (foldersResult.isSuccess) {
                        Log.d(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "$jobDescription: API Call Success. Completing deferred."
                        )
                    } else {
                        Log.e(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "$jobDescription: API Call Error. Completing deferred. Error: ${foldersResult.exceptionOrNull()?.message}"
                        )
                    }
                    completionSignal.complete(Unit) // Signal completion (success or handled failure).
                }
                // Wait for the callback within acquireTokenAndExecute to complete the Deferred.
                Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Waiting for completion signal...")
                completionSignal.await()
                Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Completion signal received.")

            } catch (e: CancellationException) {
                Log.w(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Outer job cancelled.", e)
                completionSignal.completeExceptionally(e) // Ensure deferred completes on cancellation
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                Log.e(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "$jobDescription: Outer job exception: ${e.message}",
                    e
                )
                // Update state to Error on unexpected exceptions.
                _uiState.update { s ->
                    s.copy(
                        foldersByAccountId = s.foldersByAccountId + (accountId to FolderFetchState.Error(
                            mapExceptionToUserMessage(e)
                        ))
                    )
                }
                completionSignal.completeExceptionally(e) // Ensure deferred completes on exception
            } finally {
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "$jobDescription: Outer job finished (finally block)."
                )
                // Remove the job reference only if it's the same instance, avoiding race conditions.
                if (folderFetchJobs[accountId] == coroutineContext[Job]) {
                    folderFetchJobs.remove(accountId)
                    Log.d(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "$jobDescription: Removed job reference from map."
                    )
                }
            }
        }
        // Store the job reference for tracking.
        folderFetchJobs[accountId] = job
        return job
    }


    /**
     * Attempts to select a default folder (preferring "Inbox") if none is currently selected.
     * This is called after initial authentication state is confirmed and folder fetches initiated.
     */
    private fun selectDefaultFolderIfNeeded() {
        Log.d(TAG_DEBUG_DEFAULT_SELECT, "selectDefaultFolderIfNeeded: Entered.")
        // Only proceed if no folder is selected and there are accounts.
        if (_uiState.value.selectedFolder == null && _uiState.value.accounts.isNotEmpty()) {
            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "selectDefaultFolderIfNeeded: Condition met (no folder selected, accounts exist)."
            )
            var folderToSelect: MailFolder? = null
            var accountForFolder: IAccount? = null

            // Prioritize finding an "Inbox" folder in any account with successfully loaded folders.
            for (account in _uiState.value.accounts) {
                val folderState = _uiState.value.foldersByAccountId[account.id]
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "selectDefaultFolderIfNeeded: Checking account ${account.username}. FolderState: ${folderState?.javaClass?.simpleName}"
                )
                if (folderState is FolderFetchState.Success) {
                    val inbox = folderState.folders.find {
                        it.displayName.equals(
                            "Inbox",
                            ignoreCase = true
                        )
                    }
                    if (inbox != null) {
                        folderToSelect = inbox
                        accountForFolder = account
                        Log.i(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "selectDefaultFolderIfNeeded: Found Inbox in account ${account.username}"
                        )
                        break // Found preferred folder
                    }
                }
            }

            // Fallback: If no Inbox found, select the first folder from the first account with loaded folders.
            if (folderToSelect == null) {
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "selectDefaultFolderIfNeeded: Inbox not found, searching for first available folder."
                )
                for (account in _uiState.value.accounts) {
                    val folderState = _uiState.value.foldersByAccountId[account.id]
                    if (folderState is FolderFetchState.Success && folderState.folders.isNotEmpty()) {
                        folderToSelect = folderState.folders.first()
                        accountForFolder = account
                        Log.i(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "selectDefaultFolderIfNeeded: Found first folder '${folderToSelect.displayName}' in account ${account.username}"
                        )
                        break // Found a fallback folder
                    }
                }
            }

            // If a folder was found (either Inbox or fallback), select it.
            if (folderToSelect != null && accountForFolder != null) {
                Log.i(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "selectDefaultFolderIfNeeded: Attempting to select folder '${folderToSelect.displayName}' from account ${accountForFolder.username}"
                )
                selectFolder(folderToSelect, accountForFolder)
            } else {
                Log.w(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "selectDefaultFolderIfNeeded: No suitable folder found to select (FolderState might still be Loading or Error, or folders list empty)."
                )
            }
        } else {
            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "selectDefaultFolderIfNeeded: Condition NOT met. selectedFolder=${_uiState.value.selectedFolder?.displayName}, accounts=${_uiState.value.accounts.size}"
            )
        }
    }

    // --- Account Management Functions (exposed to UI, e.g., SettingsScreen) ---

    /**
     * Initiates the interactive flow to add a new Microsoft account.
     * Delegates the call to the injected [MicrosoftAuthManager].
     * Updates UI state for loading feedback and shows results via toast messages.
     */
    fun addAccount(activity: Activity) {
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Authentication system not ready.") }
            return
        }
        _uiState.update { it.copy(isLoadingAuthAction = true) }
        // Delegate to the authentication manager injected by Hilt.
        microsoftAuthManager.addAccount(activity, mailReadScopes) { result ->
            val message = when (result) {
                is AddAccountResult.Success -> "Account added: ${result.account.username}"
                is AddAccountResult.Error -> "Error adding account: ${
                    mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"
                is AddAccountResult.Cancelled -> "Account addition cancelled."
                is AddAccountResult.NotInitialized -> "Authentication system not ready."
            }
            // isLoadingAuthAction is cleared by onAuthStateChanged triggered by the manager.
            _uiState.update { it.copy(toastMessage = message) }
        }
    }

    /**
     * Initiates the process to remove an existing Microsoft account.
     * Delegates the call to the injected [MicrosoftAuthManager].
     * Updates UI state for loading feedback and shows results via toast messages.
     */
    fun removeAccount(activity: Activity, accountToRemove: IAccount?) {
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Authentication system not ready.") }
            return
        }
        if (accountToRemove == null) {
            _uiState.update { it.copy(toastMessage = "No account specified for removal.") }
            return
        }
        _uiState.update { it.copy(isLoadingAuthAction = true) }
        // Delegate to the authentication manager injected by Hilt.
        microsoftAuthManager.removeAccount(accountToRemove) { result ->
            val message = when (result) {
                is RemoveAccountResult.Success -> "Account removed: ${accountToRemove.username}"
                is RemoveAccountResult.Error -> "Error removing account: ${
                    mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"

                is RemoveAccountResult.NotInitialized -> "Authentication system not ready."
                is RemoveAccountResult.AccountNotFound -> "Account to remove not found."
            }
            // isLoadingAuthAction and account list updates are handled by onAuthStateChanged.
            _uiState.update { it.copy(toastMessage = message) }
        }
    }

    // --- Folder and Message Selection/Action Functions ---

    /**
     * Updates the application state to select a specific mail folder.
     * Clears previous messages and initiates fetching messages for the new folder.
     */
    fun selectFolder(folder: MailFolder, account: IAccount) {
        Log.i(
            TAG_DEBUG_DEFAULT_SELECT,
            "selectFolder: Called for '${folder.displayName}' in account ${account.username}. Current selected: ${_uiState.value.selectedFolder?.displayName}"
        )
        // Avoid re-selecting the same folder.
        if (folder.id == _uiState.value.selectedFolder?.id && account.id == _uiState.value.selectedFolderAccountId) {
            Log.d(TAG, "Folder ${folder.displayName} already selected.")
            return
        }
        Log.d(TAG, "Selecting folder: ${folder.displayName} for account ${account.username}")
        // Update state: set selection, clear messages, set loading state.
        _uiState.update {
            it.copy(
                selectedFolder = folder,
                selectedFolderAccountId = account.id,
                messages = null,
                messageError = null,
                messageDataState = DataState.LOADING
            )
        }
        // Trigger message fetch for the newly selected folder.
        fetchMessagesInternal(folder.id, account, isRefresh = false, activity = null)
    }

    /**
     * Initiates a forced refresh of mail folders for all authenticated accounts.
     */
    fun refreshAllFolders(activity: Activity?) {
        Log.d(TAG, "Requesting refresh for ALL folders...")
        _uiState.value.accounts.forEach { account ->
            // Force refresh for each account's folders.
            launchFolderFetch(account, activity, forceRefresh = true)
        }
    }

    /**
     * Initiates a refresh of the messages within the currently selected folder.
     */
    fun refreshMessages(activity: Activity?) {
        val folderId = _uiState.value.selectedFolder?.id
        val accountId = _uiState.value.selectedFolderAccountId
        val account = _uiState.value.accounts.find { it.id == accountId }

        if (account == null || folderId == null) {
            Log.w(TAG, "Refresh messages called but no folder/account selected.")
            _uiState.update { it.copy(toastMessage = "Select a folder first.") }
            return
        }
        if (_uiState.value.messageDataState == DataState.LOADING) {
            Log.d(TAG, "Refresh messages skipped: Already loading.")
            return
        }
        Log.d(TAG, "Requesting message refresh for folder: $folderId, account: ${account.username}")
        if (!isOnline()) {
            _uiState.update {
                it.copy(
                    toastMessage = "No internet connection.",
                    messageDataState = DataState.ERROR,
                    messageError = "No internet connection"
                )
            }
            return
        }
        // Set loading state and clear previous error.
        _uiState.update { it.copy(messageDataState = DataState.LOADING, messageError = null) }
        // Trigger internal message fetch, marking it as a refresh.
        fetchMessagesInternal(folderId, account, isRefresh = true, activity = activity)
    }

    // --- Internal Helper Functions ---

    /**
     * Fetches messages for a specific folder after ensuring an access token is available.
     * Handles context checks to avoid updating state for a no-longer-selected folder.
     */
    private fun fetchMessagesInternal(
        folderId: String,
        account: IAccount,
        isRefresh: Boolean,
        activity: Activity?
    ) {
        // Context Check: Ensure the folder/account we're fetching for is still selected.
        if (account.id != _uiState.value.selectedFolderAccountId || folderId != _uiState.value.selectedFolder?.id) {
            Log.w(
                TAG,
                "fetchMessagesInternal initiated for $folderId, but selection changed before execution. Ignoring."
            )
            return
        }

        // Acquire token and execute the Graph API call in the success lambda.
        acquireTokenAndExecute(
            account = account, activity = activity, scopes = mailReadScopes,
            onError = { userErrorMessage ->
                // Context Check inside callback: Update state only if selection hasn't changed.
                if (_uiState.value.selectedFolderAccountId == account.id && _uiState.value.selectedFolder?.id == folderId) {
                    _uiState.update {
                        it.copy(
                            messageDataState = DataState.ERROR,
                            messageError = userErrorMessage
                        )
                    }
                    if (isRefresh) _uiState.update { it.copy(toastMessage = userErrorMessage) }
                } else {
                    Log.w(
                        TAG,
                        "Message fetch token error received, but context changed before state update."
                    )
                }
            }
        ) { accessToken -> // Token acquired successfully.
            // Context Check inside callback: Update state only if selection hasn't changed.
            if (_uiState.value.selectedFolderAccountId == account.id && _uiState.value.selectedFolder?.id == folderId) {
                Log.d(
                    TAG,
                    "Got token for ${account.username}, fetching messages for folder $folderId from Graph..."
                )
                // Perform the Graph API call.
                val messagesResult = GraphApiHelper.getMessagesForFolder(
                    accessToken = accessToken, folderId = folderId,
                    selectFields = messageListSelectFields, top = messageListPageSize
                )
                // Update UI state based on the API result.
                _uiState.update {
                    it.copy(
                        messageDataState = if (messagesResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                        messages = messagesResult.getOrNull()
                            ?: if (messagesResult.isFailure) null else emptyList(),
                        messageError = messagesResult.exceptionOrNull()
                            ?.let { e -> mapExceptionToUserMessage(e) }
                    )
                }
                if (messagesResult.isFailure) {
                    Log.e(
                        TAG,
                        "Failed to fetch messages for folder $folderId",
                        messagesResult.exceptionOrNull()
                    )
                }
                // Show toast feedback for user-initiated refreshes.
                if (isRefresh) {
                    val toastMsg =
                        if (messagesResult.isSuccess) "Messages refreshed" else "Refresh failed: ${_uiState.value.messageError}"
                    _uiState.update { it.copy(toastMessage = toastMsg) }
                }
            } else {
                Log.w(
                    TAG,
                    "Message fetch result received, but context changed before state update."
                )
            }
        }
    }

    /**
     * Helper function to acquire an MSAL access token (silently first, then interactively).
     * Executes the provided suspendable [onSuccess] action with the token upon success.
     * Calls [onError] with a user-friendly message upon failure.
     */
    private fun acquireTokenAndExecute(
        account: IAccount?,
        activity: Activity?,
        scopes: List<String>,
        onError: ((String) -> Unit)?,
        onSuccess: suspend (String) -> Unit
    ) {
        if (account == null) {
            onError?.invoke("No account specified for operation.")
            return
        }
        // Request token silently from the injected auth manager.
        microsoftAuthManager.acquireTokenSilent(account, scopes) { tokenResult ->
            // Handle the result within the ViewModel's scope.
            viewModelScope.launch {
                when (tokenResult) {
                    is AcquireTokenResult.Success -> {
                        Log.d(TAG, "Silent token acquired successfully for ${account.username}.")
                        try {
                            // Execute the action with the acquired token.
                            onSuccess(tokenResult.result.accessToken)
                        } catch (e: CancellationException) {
                            Log.w(TAG, "Operation cancelled during onSuccess execution", e)
                            onError?.invoke("Operation cancelled.")
                            throw e // Re-throw
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Exception within onSuccess block after silent token success",
                                e
                            )
                            onError?.invoke(
                                "Operation failed after token acquisition: ${
                                    mapExceptionToUserMessage(
                                        e
                                    )
                                }"
                            )
                        }
                    }
                    is AcquireTokenResult.UiRequired -> {
                        // Silent failed, try interactive if activity is available.
                        if (activity != null) {
                            Log.i(
                                TAG,
                                "Silent token acquisition failed (UI Required for ${account.username}). Trying interactive."
                            )
                            microsoftAuthManager.acquireTokenInteractive(
                                activity,
                                account,
                                scopes
                            ) { interactiveResult ->
                                viewModelScope.launch {
                                    when (interactiveResult) {
                                        is AcquireTokenResult.Success -> {
                                            Log.d(
                                                TAG,
                                                "Interactive token acquired successfully for ${account.username}."
                                            )
                                            try {
                                                // Execute action with the interactively acquired token.
                                                onSuccess(interactiveResult.result.accessToken)
                                            } catch (e: CancellationException) {
                                                Log.w(
                                                    TAG,
                                                    "Operation cancelled during onSuccess execution (after interactive)",
                                                    e
                                                )
                                                onError?.invoke("Operation cancelled.")
                                                throw e // Re-throw
                                            } catch (e: Exception) {
                                                Log.e(
                                                    TAG,
                                                    "Exception within onSuccess block after interactive token success",
                                                    e
                                                )
                                                onError?.invoke(
                                                    "Operation failed after token acquisition: ${
                                                        mapExceptionToUserMessage(
                                                            e
                                                        )
                                                    }"
                                                )
                                            }
                                        }
                                        // Handle interactive failure cases.
                                        is AcquireTokenResult.Error -> {
                                            Log.e(
                                                TAG,
                                                "Interactive token acquisition error for ${account.username}",
                                                interactiveResult.exception
                                            )
                                            onError?.invoke(
                                                mapAuthExceptionToUserMessage(
                                                    interactiveResult.exception
                                                )
                                            )
                                        }
                                        is AcquireTokenResult.Cancelled -> {
                                            Log.w(
                                                TAG,
                                                "Interactive token acquisition cancelled by user for ${account.username}"
                                            )
                                            onError?.invoke("Authentication cancelled.")
                                        }
                                        is AcquireTokenResult.NotInitialized -> {
                                            Log.e(
                                                TAG,
                                                "Interactive token acquisition failed: MSAL not initialized."
                                            )
                                            onError?.invoke("Authentication system not ready.")
                                        }

                                        else -> {
                                            Log.e(
                                                TAG,
                                                "Unexpected interactive token result for ${account.username}: $interactiveResult"
                                            )
                                            onError?.invoke("Authentication failed (interactive).")
                                        }
                                    }
                                } // End launch for interactive result
                            } // End interactive callback
                        } else {
                            // UI Required but no activity available for interactive flow.
                            Log.w(
                                TAG,
                                "UI Required for token for ${account.username}, but no activity provided."
                            )
                            onError?.invoke("Session expired. Please refresh or try again.")
                        }
                    }
                    // Handle other silent failure cases.
                    is AcquireTokenResult.Error -> {
                        Log.e(
                            TAG,
                            "Silent token acquisition error for ${account.username}",
                            tokenResult.exception
                        )
                        onError?.invoke(mapAuthExceptionToUserMessage(tokenResult.exception))
                    }

                    is AcquireTokenResult.NoAccountProvided -> {
                        Log.e(
                            TAG,
                            "Internal error: No account provided for silent token acquisition."
                        )
                        onError?.invoke("Internal error: Account not provided.")
                    }

                    is AcquireTokenResult.Cancelled -> {
                        Log.w(TAG, "Silent token acquisition cancelled? Unexpected.")
                        onError?.invoke("Authentication cancelled.")
                    }
                    is AcquireTokenResult.NotInitialized -> {
                        Log.e(TAG, "Silent token acquisition failed: MSAL not initialized.")
                        onError?.invoke("Authentication system not ready.")
                    }
                }
            } // End launch for silent result
        } // End silent callback
    }


    /**
     * Maps common exceptions (network, MSAL) to user-friendly error strings.
     */
    private fun mapExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}")
        var currentException = exception
        var depth = 0
        while (currentException != null && depth < 10) {
            when (currentException) {
                is UnknownHostException -> return "No internet connection"
                is IOException -> return "Couldn't reach server"
            }
            if (currentException is MsalClientException && currentException.errorCode == MsalClientException.IO_ERROR) return "Network error during authentication"
            if (currentException is MsalServiceException && currentException.cause is IOException) return "Network error contacting authentication service"

            currentException = currentException.cause
            depth++
        }
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication error"

            else -> exception?.message?.takeIf { it.isNotBlank() } ?: "An unknown error occurred"
        }
    }

    /**
     * Maps MSAL-specific exceptions to user-friendly messages, providing more context
     * than the general mapping if possible. Falls back to [mapExceptionToUserMessage].
     */
    private fun mapAuthExceptionToUserMessage(exception: MsalException): String {
        val generalMessage = mapExceptionToUserMessage(exception)
        if (generalMessage != "An unknown error occurred" && generalMessage != "Authentication error" && generalMessage != "Authentication failed") {
            return generalMessage
        }
        Log.w(
            TAG,
            "Mapping specific auth exception (fallback): ${exception::class.java.simpleName} - ${exception.errorCode}"
        )
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in." // Redundant but safe
            is MsalClientException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication client error"

            is MsalServiceException -> exception.message?.takeIf { it.isNotBlank() }
                ?: "Authentication service error"
            else -> exception.message?.takeIf { it.isNotEmpty() } ?: "Authentication failed"
        }
    }

    // --- Toast Management ---

    /**
     * Called by the UI after a toast message from the state has been displayed,
     * to clear the message and prevent it from being shown again on recomposition.
     */
    fun toastMessageShown() {
        // Update the state to clear the toast message.
        _uiState.update { it.copy(toastMessage = null) }
    }

    // --- ViewModel Cleanup ---

    /**
     * Called when the ViewModel is no longer used and is about to be destroyed.
     * Perform cleanup here, such as cancelling ongoing coroutines or removing listeners.
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel Cleared. Cancelling jobs.")
        // Cancel all active folder fetch jobs to prevent leaks and unnecessary work.
        folderFetchJobs.values.forEach { it.cancel() }
        folderFetchJobs.clear()
        // Cancel the job waiting for initial folder loads.
        initialFolderLoadJob?.cancel()
        // Unregister the auth state listener from the injected manager to prevent potential leaks.
        microsoftAuthManager.setAuthStateListener(null)
    }
}