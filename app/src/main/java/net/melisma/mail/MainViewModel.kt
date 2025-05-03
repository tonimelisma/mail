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

// Removed: import kotlin.coroutines.cancellation.CancellationException // Removed ambiguous import

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
 * email browsing interface of the application. It coordinates authentication,
 * data fetching (folders, messages), and UI state updates.
 */
class MainViewModel(
    // Application context is required for system services like ConnectivityManager.
    private val applicationContext: Context,
    // The authentication manager handles interactions with the MSAL library.
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

    init {
        // Register this ViewModel to listen for changes in authentication state from the MicrosoftAuthManager.
        microsoftAuthManager.setAuthStateListener(this)
        Log.d(TAG, "ViewModel Initialized.")
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
                        // Because launchFolderFetch now waits correctly, this join will work as expected.
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
                        // Decide if we should still attempt selection or not, maybe not if joining failed.
                    }
                    // Attempt default selection now that the initial fetches are complete.
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
     * This job will only complete after token acquisition and the Graph API call are finished.
     * It uses a CompletableDeferred to signal completion from within async callbacks.
     * Updates the UI state ([FolderFetchState]) throughout the process.
     *
     * @param account The account for which to fetch folders.
     * @param activity The Activity context, potentially needed for interactive token acquisition.
     * @param forceRefresh If true, cancels any ongoing fetch for this account and starts a new one.
     * @return The [Job] representing the entire asynchronous fetch operation (including waiting).
     */
    private fun launchFolderFetch(
        account: IAccount,
        activity: Activity?,
        forceRefresh: Boolean = false
    ): Job {
        val accountId = account.id
        // If forcing a refresh, cancel any existing job for this account first.
        if (forceRefresh) {
            // Provide a reason for cancellation for better debugging.
            folderFetchJobs[accountId]?.cancel(CancellationException("Forced refresh requested"))
        }
        // If a job is already active for this account and we are not forcing a refresh, return the existing job.
        if (folderFetchJobs[accountId]?.isActive == true) {
            Log.d(TAG, "Folder fetch already in progress for ${account.username}")
            return folderFetchJobs[accountId]!!
        }
        val currentFolderState = _uiState.value.foldersByAccountId[accountId]
        // If folders are already successfully loaded and not forcing refresh, do nothing and return a completed job.
        if (!forceRefresh && currentFolderState is FolderFetchState.Success) {
            Log.d(TAG, "Folders already loaded for ${account.username}, skipping fetch.")
            return Job().apply { complete() } // Represents an already completed operation.
        }

        Log.d(TAG, "Starting folder fetch for ${account.username} (Force: $forceRefresh)")
        // Update the state for this account to Loading.
        _uiState.update { it.copy(foldersByAccountId = it.foldersByAccountId + (accountId to FolderFetchState.Loading)) }

        // Launch the main coroutine for this fetch operation.
        val job = viewModelScope.launch {
            // Create a Deferred that will signal the completion of the *entire* async process inside.
            val completionSignal = CompletableDeferred<Unit>()
            val jobDescription = "FolderFetch Job for ${account.username}"
            Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Outer job started.")

            try {
                // Ensure network connectivity before attempting API calls.
                if (!isOnline()) {
                    throw IOException("No internet connection")
                }

                // Call acquireTokenAndExecute. Its callbacks will complete the deferred.
                acquireTokenAndExecute(
                    account = account, activity = activity, scopes = mailReadScopes,
                    onError = { userErrorMessage ->
                        // This block runs if token acquisition or the subsequent action fails.
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
                        completionSignal.complete(Unit) // Signal completion (failure case).
                    }
                ) { accessToken ->
                    // This block runs if token acquisition succeeds.
                    Log.d(TAG, "Got token for ${account.username}, fetching folders from Graph...")
                    // Perform the actual Graph API call.
                    val foldersResult = GraphApiHelper.getMailFolders(accessToken)
                    // Determine the new state based on the API call result.
                    val newState = if (foldersResult.isSuccess) {
                        FolderFetchState.Success(foldersResult.getOrThrow())
                    } else {
                        FolderFetchState.Error(mapExceptionToUserMessage(foldersResult.exceptionOrNull()))
                    }
                    // Update the UI state map with the result for this account.
                    _uiState.update { s -> s.copy(foldersByAccountId = s.foldersByAccountId + (accountId to newState)) }

                    // Log success or failure of the API call.
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
                    completionSignal.complete(Unit) // Signal completion (success or handled failure case).
                }

                // Wait here until the deferred is completed by one of the callbacks inside acquireTokenAndExecute.
                Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Waiting for completion signal...")
                completionSignal.await() // This makes the outer job wait.
                Log.d(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Completion signal received.")

            } catch (e: CancellationException) {
                // Catch cancellation specific to this outer job (e.g., forced refresh, ViewModel cleared).
                Log.w(TAG_DEBUG_DEFAULT_SELECT, "$jobDescription: Outer job cancelled.", e)
                // Ensure deferred is completed exceptionally if cancellation happens before callbacks.
                completionSignal.completeExceptionally(e)
                throw e // Re-throw cancellation for proper coroutine handling.
            } catch (e: Exception) {
                // Catch any other exceptions (like the IOException from isOnline check).
                Log.e(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "$jobDescription: Outer job exception: ${e.message}",
                    e
                )
                _uiState.update { s ->
                    s.copy(
                        foldersByAccountId = s.foldersByAccountId + (accountId to FolderFetchState.Error(
                            mapExceptionToUserMessage(e)
                        ))
                    )
                }
                // Ensure deferred is completed exceptionally on other errors.
                completionSignal.completeExceptionally(e)
            } finally {
                // This block executes regardless of whether the job completed successfully, failed, or was cancelled.
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "$jobDescription: Outer job finished (finally block)."
                )
                // Remove the job reference from the tracking map.
                // Check if the job instance being removed is the one currently stored for this account ID
                // to prevent race conditions if a new job was started very quickly.
                if (folderFetchJobs[accountId] == coroutineContext[Job]) {
                    folderFetchJobs.remove(accountId)
                    Log.d(
                        TAG_DEBUG_DEFAULT_SELECT,
                        "$jobDescription: Removed job reference from map."
                    )
                }
            }
        }
        // Store and return the reference to the outer job, which now correctly waits.
        folderFetchJobs[accountId] = job
        return job
    }


    /**
     * Attempts to select a default folder if none is currently selected.
     * Prioritizes selecting a folder named "Inbox" (case-insensitive) from any account.
     * If no "Inbox" is found, it selects the first folder found in the first account
     * that has successfully loaded its folders.
     * This function is intended to be called *after* initial folder fetches have completed.
     */
    private fun selectDefaultFolderIfNeeded() {
        Log.d(TAG_DEBUG_DEFAULT_SELECT, "selectDefaultFolderIfNeeded: Entered.")

        // Only proceed if no folder is currently selected and there are accounts available.
        if (_uiState.value.selectedFolder == null && _uiState.value.accounts.isNotEmpty()) {
            Log.d(
                TAG_DEBUG_DEFAULT_SELECT,
                "selectDefaultFolderIfNeeded: Condition met (no folder selected, accounts exist)."
            )

            var folderToSelect: MailFolder? = null
            var accountForFolder: IAccount? = null

            // --- Prioritize finding an "Inbox" folder ---
            // Iterate through each authenticated account.
            for (account in _uiState.value.accounts) {
                // Get the folder fetch state for the current account.
                val folderState = _uiState.value.foldersByAccountId[account.id]
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "selectDefaultFolderIfNeeded: Checking account ${account.username}. FolderState: ${folderState?.javaClass?.simpleName}"
                )
                // Only consider accounts where folders loaded successfully.
                if (folderState is FolderFetchState.Success) {
                    // Find the first folder named "Inbox" (case-insensitive).
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
                        break // Found the preferred folder, no need to check other accounts.
                    }
                }
            }

            // --- Fallback: Select the first available folder ---
            // If no "Inbox" was found across all accounts.
            if (folderToSelect == null) {
                Log.d(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "selectDefaultFolderIfNeeded: Inbox not found, searching for first available folder."
                )
                // Iterate through accounts again.
                for (account in _uiState.value.accounts) {
                    val folderState = _uiState.value.foldersByAccountId[account.id]
                    // Consider accounts with successfully loaded folders that are not empty.
                    if (folderState is FolderFetchState.Success && folderState.folders.isNotEmpty()) {
                        // Select the very first folder from this account's list.
                        folderToSelect = folderState.folders.first()
                        accountForFolder = account
                        Log.i(
                            TAG_DEBUG_DEFAULT_SELECT,
                            "selectDefaultFolderIfNeeded: Found first folder '${folderToSelect.displayName}' in account ${account.username}"
                        )
                        break // Found a folder, stop searching.
                    }
                }
            }

            // --- Perform Selection ---
            // If a suitable folder (Inbox or fallback) was identified.
            if (folderToSelect != null && accountForFolder != null) {
                Log.i(
                    TAG_DEBUG_DEFAULT_SELECT,
                    "selectDefaultFolderIfNeeded: Attempting to select folder '${folderToSelect.displayName}' from account ${accountForFolder.username}"
                )
                // Call the main selection function to update state and fetch messages.
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
     * Initiates the interactive flow to add a new Microsoft account using [MicrosoftAuthManager].
     * Updates UI state to indicate loading and shows toast messages based on the result.
     * Folder fetching for the new account is automatically handled by [onAuthStateChanged].
     */
    fun addAccount(activity: Activity) {
        // Prevent action if authentication library isn't ready.
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Authentication system not ready.") }
            return
        }
        // Set loading state for UI feedback.
        _uiState.update { it.copy(isLoadingAuthAction = true) }
        // Delegate the action to the authentication manager.
        microsoftAuthManager.addAccount(activity, mailReadScopes) { result ->
            // Process the result of the add account attempt.
            val message = when (result) {
                is AddAccountResult.Success -> "Account added: ${result.account.username}"
                is AddAccountResult.Error -> "Error adding account: ${
                    mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"
                is AddAccountResult.Cancelled -> "Account addition cancelled."
                is AddAccountResult.NotInitialized -> "Authentication system not ready." // Should be caught by initial guard
            }
            // Show result message to the user. isLoadingAuthAction is cleared by onAuthStateChanged.
            _uiState.update { it.copy(toastMessage = message) }
        }
    }

    /**
     * Initiates the process to remove an existing Microsoft account using [MicrosoftAuthManager].
     * Updates UI state to indicate loading and shows toast messages based on the result.
     * UI state regarding account list and selected folder is automatically handled by [onAuthStateChanged].
     */
    fun removeAccount(activity: Activity, accountToRemove: IAccount?) {
        // Prevent action if authentication library isn't ready or no account is specified.
        if (!_uiState.value.isAuthInitialized) {
            _uiState.update { it.copy(toastMessage = "Authentication system not ready.") }
            return
        }
        if (accountToRemove == null) {
            _uiState.update { it.copy(toastMessage = "No account specified for removal.") }
            return
        }
        // Set loading state for UI feedback.
        _uiState.update { it.copy(isLoadingAuthAction = true) }
        // Delegate the action to the authentication manager.
        microsoftAuthManager.removeAccount(accountToRemove) { result ->
            // Process the result of the remove account attempt.
            val message = when (result) {
                is RemoveAccountResult.Success -> "Account removed: ${accountToRemove.username}"
                is RemoveAccountResult.Error -> "Error removing account: ${
                    mapAuthExceptionToUserMessage(
                        result.exception
                    )
                }"
                is RemoveAccountResult.NotInitialized -> "Authentication system not ready." // Should be caught by initial guard
                is RemoveAccountResult.AccountNotFound -> "Account to remove not found." // Should be caught by null check
            }
            // Show result message. isLoadingAuthAction and account list updates are handled by onAuthStateChanged.
            _uiState.update { it.copy(toastMessage = message) }
        }
    }

    // --- Folder and Message Selection/Action Functions ---

    /**
     * Updates the application state to select a specific mail folder.
     * This clears previous messages and initiates fetching messages for the new folder.
     *
     * @param folder The [MailFolder] object representing the folder to be selected.
     * @param account The [IAccount] that owns the specified folder.
     */
    fun selectFolder(folder: MailFolder, account: IAccount) {
        Log.i(
            TAG_DEBUG_DEFAULT_SELECT,
            "selectFolder: Called for '${folder.displayName}' in account ${account.username}. Current selected: ${_uiState.value.selectedFolder?.displayName}"
        )

        // Avoid re-selecting the same folder to prevent unnecessary state updates and fetches.
        if (folder.id == _uiState.value.selectedFolder?.id && account.id == _uiState.value.selectedFolderAccountId) {
            Log.d(TAG, "Folder ${folder.displayName} already selected.")
            return
        }

        Log.d(TAG, "Selecting folder: ${folder.displayName} for account ${account.username}")
        // Update the state: set the selected folder and account ID, clear previous messages/errors, set message state to LOADING.
        _uiState.update {
            it.copy(
                selectedFolder = folder,
                selectedFolderAccountId = account.id,
                messages = null,
                messageError = null,
                messageDataState = DataState.LOADING
            )
        }
        // Trigger the internal function to fetch messages for this folder. Pass null activity as it's not user-interactive.
        fetchMessagesInternal(folder.id, account, isRefresh = false, activity = null)
    }

    /**
     * Initiates a forced refresh of mail folders for all authenticated accounts.
     * Useful for a manual "sync all" action.
     *
     * @param activity The Activity context, potentially needed for interactive token acquisition if tokens expired.
     */
    fun refreshAllFolders(activity: Activity?) {
        Log.d(TAG, "Requesting refresh for ALL folders...")
        // Iterate through all current accounts and launch a folder fetch job with forceRefresh = true.
        _uiState.value.accounts.forEach { account ->
            launchFolderFetch(account, activity, forceRefresh = true)
        }
    }

    /**
     * Initiates a refresh of the messages within the currently selected folder.
     * Does nothing if no folder is selected or if a refresh is already in progress.
     * Checks for network connectivity before proceeding.
     *
     * @param activity The Activity context, potentially needed for interactive token acquisition.
     */
    fun refreshMessages(activity: Activity?) {
        val folderId = _uiState.value.selectedFolder?.id
        val accountId = _uiState.value.selectedFolderAccountId
        // Find the IAccount object corresponding to the selected folder's account ID.
        val account = _uiState.value.accounts.find { it.id == accountId }

        // Ensure a folder and corresponding account are actually selected.
        if (account == null || folderId == null) {
            Log.w(TAG, "Refresh messages called but no folder/account selected.")
            _uiState.update { it.copy(toastMessage = "Select a folder first.") } // Provide user feedback.
            return
        }
        // Prevent triggering multiple refreshes simultaneously for the same folder.
        if (_uiState.value.messageDataState == DataState.LOADING) {
            Log.d(TAG, "Refresh messages skipped: Already loading.")
            return
        }
        Log.d(TAG, "Requesting message refresh for folder: $folderId, account: ${account.username}")
        // Check for internet connection before attempting a network operation.
        if (!isOnline()) {
            _uiState.update {
                it.copy(
                    toastMessage = "No internet connection.",
                    // Also update message state to Error immediately for UI feedback.
                    messageDataState = DataState.ERROR,
                    messageError = "No internet connection"
                )
            }
            return
        }
        // Set the message state to LOADING and clear any previous error message.
        _uiState.update { it.copy(messageDataState = DataState.LOADING, messageError = null) }
        // Trigger the internal function to fetch messages, marking it as a refresh.
        fetchMessagesInternal(folderId, account, isRefresh = true, activity = activity)
    }

    // --- Internal Helper Functions ---

    /**
     * Fetches messages for a specific folder after ensuring an access token is available.
     * Uses [acquireTokenAndExecute] for token handling and [GraphApiHelper] for the API call.
     * Updates the [MainScreenState] with the fetched messages or an error.
     * Checks if the selected folder context is still valid before applying state updates.
     *
     * @param folderId The ID of the folder whose messages are to be fetched.
     * @param account The account owning the folder.
     * @param isRefresh Indicates if this fetch is a user-initiated refresh (affects toast messages).
     * @param activity The Activity context, potentially needed for interactive token acquisition.
     */
    private fun fetchMessagesInternal(
        folderId: String,
        account: IAccount,
        isRefresh: Boolean,
        activity: Activity?
    ) {
        // --- Context Check ---
        // Before proceeding with the fetch (which involves async operations like token acquisition),
        // verify that the folder/account we *intend* to fetch for is still the one selected in the UI state.
        // This prevents updating the UI with data for a folder the user has already navigated away from.
        if (account.id != _uiState.value.selectedFolderAccountId || folderId != _uiState.value.selectedFolder?.id) {
            Log.w(
                TAG,
                "fetchMessagesInternal initiated for $folderId, but selection changed before execution. Ignoring."
            )
            return
        }

        // Step 1: Acquire Token, executing the API call in the onSuccess lambda.
        acquireTokenAndExecute(
            account = account, activity = activity, scopes = mailReadScopes,
            onError = { userErrorMessage ->
                // --- Context Check (within callback) ---
                // Critical: Check context *again* inside the async callback before updating state.
                if (_uiState.value.selectedFolderAccountId == account.id && _uiState.value.selectedFolder?.id == folderId) {
                    // Update state to ERROR if token acquisition fails.
                    _uiState.update {
                        it.copy(
                            messageDataState = DataState.ERROR,
                            messageError = userErrorMessage
                        )
                    }
                    // Show a toast only if it was a user-initiated refresh that failed at the token step.
                    if (isRefresh) _uiState.update { it.copy(toastMessage = userErrorMessage) }
                } else {
                    // Log if the context changed between starting the token request and getting the error.
                    Log.w(
                        TAG,
                        "Message fetch token error received, but context changed before state update."
                    )
                }
            }
        ) { accessToken -> // Step 2: This block executes if token acquisition is successful.
            // --- Context Check (within callback) ---
            // Critical: Check context *again* inside the async callback before updating state.
            if (_uiState.value.selectedFolderAccountId == account.id && _uiState.value.selectedFolder?.id == folderId) {
                Log.d(
                    TAG,
                    "Got token for ${account.username}, fetching messages for folder $folderId from Graph..."
                )
                // Perform the Graph API call to get messages.
                val messagesResult = GraphApiHelper.getMessagesForFolder(
                    accessToken = accessToken, folderId = folderId,
                    selectFields = messageListSelectFields, top = messageListPageSize
                )

                // Update the UI state based on the API call result (Success or Failure).
                _uiState.update {
                    it.copy(
                        messageDataState = if (messagesResult.isSuccess) DataState.SUCCESS else DataState.ERROR,
                        // Provide the message list on success, null on failure (or keep previous list if desired). Empty list if success but no messages.
                        messages = messagesResult.getOrNull()
                            ?: if (messagesResult.isFailure) null else emptyList(),
                        // Extract and map the error message on failure.
                        messageError = messagesResult.exceptionOrNull()
                            ?.let { e -> mapExceptionToUserMessage(e) }
                    )
                }
                // Log errors from the API call.
                if (messagesResult.isFailure) {
                    Log.e(
                        TAG,
                        "Failed to fetch messages for folder $folderId",
                        messagesResult.exceptionOrNull()
                    )
                }
                // Provide feedback via toast for user-initiated refreshes.
                if (isRefresh) {
                    val toastMsg =
                        if (messagesResult.isSuccess) "Messages refreshed" else "Refresh failed: ${_uiState.value.messageError}"
                    _uiState.update { it.copy(toastMessage = toastMsg) }
                }
            } else {
                // Log if the context changed between getting the token and getting the API result.
                Log.w(
                    TAG,
                    "Message fetch result received, but context changed before state update."
                )
            }
        }
    }

    /**
     * A helper function that abstracts the common pattern of acquiring an MSAL access token
     * (silently first, then interactively if necessary and possible) and then executing
     * a given suspendable action upon success.
     * It centralizes the logic for handling different `AcquireTokenResult` states.
     *
     * @param account The target [IAccount] for token acquisition.
     * @param activity The [Activity] context, required for interactive flows. Can be null if only silent acquisition is acceptable.
     * @param scopes The list of permission scopes required for the `onSuccess` action.
     * @param onError A lambda invoked with a user-friendly error message if token acquisition ultimately fails.
     * @param onSuccess A suspendable lambda invoked with the obtained access token if acquisition succeeds.
     */
    private fun acquireTokenAndExecute(
        account: IAccount?,
        activity: Activity?,
        scopes: List<String>,
        onError: ((String) -> Unit)?, // Callback for errors
        onSuccess: suspend (String) -> Unit // Suspend function to run on success
    ) {
        // Ensure an account is provided.
        if (account == null) {
            onError?.invoke("No account specified for operation.")
            return
        }
        // Start with a silent token request.
        microsoftAuthManager.acquireTokenSilent(account, scopes) { tokenResult ->
            // Handle the result within the ViewModel's scope for structured concurrency.
            viewModelScope.launch { // This launch handles the result callback
                when (tokenResult) {
                    // --- Silent Success ---
                    is AcquireTokenResult.Success -> {
                        Log.d(TAG, "Silent token acquired successfully for ${account.username}.")
                        try {
                            // Execute the main action with the obtained token.
                            onSuccess(tokenResult.result.accessToken)
                        } catch (e: CancellationException) {
                            // Catch cancellation specific to the onSuccess block execution
                            Log.w(TAG, "Operation cancelled during onSuccess execution", e)
                            onError?.invoke("Operation cancelled.")
                            throw e // Re-throw cancellation
                        } catch (e: Exception) {
                            // Catch other exceptions thrown by the onSuccess block itself.
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
                    // --- Silent Failure: UI Interaction Required ---
                    is AcquireTokenResult.UiRequired -> {
                        // Only attempt interactive flow if an Activity context is available.
                        if (activity != null) {
                            Log.i(
                                TAG,
                                "Silent token acquisition failed (UI Required for ${account.username}). Trying interactive."
                            )
                            // Delegate to the interactive token acquisition method.
                            microsoftAuthManager.acquireTokenInteractive(
                                activity,
                                account,
                                scopes
                            ) { interactiveResult ->
                                // Handle the interactive result in a new coroutine.
                                viewModelScope.launch { // This launch handles the interactive callback result
                                    when (interactiveResult) {
                                        // --- Interactive Success ---
                                        is AcquireTokenResult.Success -> {
                                            Log.d(
                                                TAG,
                                                "Interactive token acquired successfully for ${account.username}."
                                            )
                                            try {
                                                // Execute the main action with the interactively obtained token.
                                                onSuccess(interactiveResult.result.accessToken)
                                            } catch (e: CancellationException) {
                                                // Catch cancellation specific to the onSuccess block execution
                                                Log.w(
                                                    TAG,
                                                    "Operation cancelled during onSuccess execution (after interactive)",
                                                    e
                                                )
                                                onError?.invoke("Operation cancelled.")
                                                throw e // Re-throw cancellation
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
                                        // --- Interactive Failure Cases ---
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
                                        else -> { // Catch-all for unexpected interactive results
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
                            // UI interaction is required, but we don't have an Activity to start it.
                            Log.w(
                                TAG,
                                "UI Required for token for ${account.username}, but no activity provided."
                            )
                            onError?.invoke("Session expired. Please refresh or try again.") // User-friendly message
                        }
                    }
                    // --- Other Silent Failure Cases ---
                    is AcquireTokenResult.Error -> {
                        Log.e(
                            TAG,
                            "Silent token acquisition error for ${account.username}",
                            tokenResult.exception
                        )
                        onError?.invoke(mapAuthExceptionToUserMessage(tokenResult.exception))
                    }
                    is AcquireTokenResult.NoAccountProvided -> { // Should not happen due to initial null check
                        Log.e(
                            TAG,
                            "Internal error: No account provided for silent token acquisition."
                        )
                        onError?.invoke("Internal error: Account not provided.")
                    }
                    is AcquireTokenResult.Cancelled -> { // Should not happen for silent flow
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
     * Prioritizes network errors and specific MSAL conditions like UI required.
     *
     * @param exception The throwable to map.
     * @return A user-understandable error message string.
     */
    private fun mapExceptionToUserMessage(exception: Throwable?): String {
        Log.w(
            TAG,
            "Mapping exception: ${exception?.let { it::class.java.simpleName + " - " + it.message } ?: "null"}")
        // Check the cause chain for common network exceptions first (up to 10 levels deep).
        var currentException = exception
        var depth = 0
        while (currentException != null && depth < 10) {
            when (currentException) {
                is UnknownHostException -> return "No internet connection" // Specific DNS/Host error
                is IOException -> return "Couldn't reach server" // More general network I/O error
            }
            // Check for specific MSAL error codes related to network issues.
            if (currentException is MsalClientException && currentException.errorCode == MsalClientException.IO_ERROR) return "Network error during authentication"
            if (currentException is MsalServiceException && currentException.cause is IOException) return "Network error contacting authentication service"

            currentException = currentException.cause
            depth++
        }
        // If no network error found in chain, map based on the top-level exception type.
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in."
            is MsalException -> exception.message
                ?: "Authentication error" // Use MSAL's message if available.
            // Fallback for non-MSAL exceptions or those without messages.
            else -> exception?.message?.takeIf { it.isNotEmpty() } ?: "An unknown error occurred"
        }
    }

    /**
     * Maps MSAL-specific exceptions to user-friendly messages, providing more context
     * than the general mapping if possible. Falls back to [mapExceptionToUserMessage].
     *
     * @param exception The [MsalException] to map.
     * @return A user-understandable error message string.
     */
    private fun mapAuthExceptionToUserMessage(exception: MsalException): String {
        // Attempt general mapping first (catches network errors, UI required).
        val generalMessage = mapExceptionToUserMessage(exception)
        // If the general mapping provided a specific message (not a generic fallback), use it.
        if (generalMessage != "An unknown error occurred" && generalMessage != "Authentication error") {
            return generalMessage
        }
        // Log the specific MSAL error type and code for debugging if using fallback.
        Log.w(
            TAG,
            "Mapping specific auth exception (fallback): ${exception::class.java.simpleName} - ${exception.errorCode}"
        )
        // Provide more specific messages based on MSAL exception type as a fallback.
        return when (exception) {
            is MsalUiRequiredException -> "Session expired. Please retry or sign out/in." // Redundant but safe
            is MsalClientException -> exception.message ?: "Authentication client error"
            is MsalServiceException -> exception.message ?: "Authentication service error"
            // Generic fallback if MSAL exception has no message.
            else -> exception.message?.takeIf { it.isNotEmpty() } ?: "Authentication failed"
        }
    }

    // --- Toast Management ---

    /**
     * Should be called by the UI layer after a toast message from the state has been displayed,
     * to prevent it from being shown again on recomposition.
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
        // Unregister the auth state listener to avoid potential leaks if the manager holds a strong reference.
        microsoftAuthManager.setAuthStateListener(null)
    }
}