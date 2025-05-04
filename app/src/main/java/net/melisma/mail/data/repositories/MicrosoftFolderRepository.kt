package net.melisma.mail.data.repositories

// Import MSAL exceptions used in error mapping (still needed for type checking)
import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.melisma.mail.Account
import net.melisma.mail.GraphApiHelper
import net.melisma.mail.data.datasources.TokenProvider
import net.melisma.mail.data.errors.ErrorMapper
import net.melisma.mail.di.ApplicationScope
import net.melisma.mail.model.FolderFetchState
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Implementation of FolderRepository using Microsoft Graph API.
 * Fetches mail folders for Microsoft accounts. Responsible for managing fetch jobs,
 * observing account changes, handling token acquisition via [TokenProvider],
 * calling [GraphApiHelper], and emitting results as [FolderFetchState].
 */
@Singleton
class MicrosoftFolderRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope
) : FolderRepository {

    private val TAG = "MsFolderRepository"

    // Holds the latest fetch state for each observed account ID.
    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())

    // Tracks the active fetching job for each account ID to prevent duplicates and allow cancellation.
    private val folderFetchJobs = ConcurrentHashMap<String, Job>()

    // Keeps track of the accounts currently being observed.
    private val observedAccounts = ConcurrentHashMap<String, Account>()

    /**
     * Provides a [Flow] emitting the current map of account IDs to their [FolderFetchState].
     * UI layers can collect this flow to react to changes in folder loading status or results.
     */
    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    /**
     * Updates the set of accounts whose folders should be observed and fetched.
     * It handles starting fetches for new accounts and cleaning up resources for removed accounts.
     *
     * @param accounts The complete list of accounts currently active in the application.
     */
    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        // Ensure state consistency by performing updates within the repository's scope
        withContext(externalScope.coroutineContext + ioDispatcher) {
            val newMicrosoftAccounts = accounts.filter { it.providerType == "MS" }
            val newAccountIds = newMicrosoftAccounts.map { it.id }.toSet()
            val currentAccountIds = observedAccounts.keys.toSet()

            // 1. Handle removed accounts: Cancel jobs and remove state.
            val removedAccountIds = currentAccountIds - newAccountIds
            removedAccountIds.forEach { accountId ->
                cancelAndRemoveJob(accountId)
                observedAccounts.remove(accountId)
                _folderStates.update { it - accountId } // Remove the entry from the state map
            }

            // 2. Handle added accounts: Add to tracking and trigger initial fetch if needed.
            val addedAccounts = newMicrosoftAccounts.filter { it.id !in currentAccountIds }
            addedAccounts.forEach { account ->
                observedAccounts[account.id] = account
                val currentState = _folderStates.value[account.id]
                // Only fetch if we don't have state or if the previous state was an error.
                if (currentState == null || currentState is FolderFetchState.Error) {
                    launchFolderFetchJob(account, isInitialLoad = true)
                } else {
                    Log.d(
                        TAG,
                        "Skipping initial fetch for ${account.username}, state already exists: $currentState"
                    )
                }
            }
            Log.d(TAG, "Managed observed accounts. Now tracking: ${observedAccounts.keys}")
        }
    }

    /**
     * Triggers a forced refresh of folders for all currently observed Microsoft accounts.
     *
     * @param activity The optional [Activity] context, potentially needed for interactive token acquisition.
     */
    override suspend fun refreshAllFolders(activity: Activity?) {
        // Get a snapshot of accounts to refresh
        val accountsToRefresh = observedAccounts.values.toList()
        Log.d(TAG, "Refreshing folders for ${accountsToRefresh.size} observed accounts...")
        // Launch a fetch job for each, forcing a refresh even if data exists.
        accountsToRefresh.forEach { account ->
            launchFolderFetchJob(
                account,
                isInitialLoad = false, // It's a refresh, not initial load
                activity = activity,
                forceRefresh = true // Indicate that this should bypass "already loading" checks
            )
        }
    }

    /**
     * Internal function to launch or replace the coroutine job responsible for fetching folders
     * for a single account. Handles job cancellation, state updates, token acquisition,
     * API calls, and error mapping.
     *
     * This function MUST be called within the [externalScope] to ensure proper job management
     * and thread safety for state updates.
     *
     * @param account The account to fetch folders for.
     * @param isInitialLoad True if this is the first time fetching for this account session.
     * @param activity Optional [Activity] for interactive auth flows.
     * @param forceRefresh If true, bypasses checks for existing active jobs and potentially stale data.
     */
    private fun launchFolderFetchJob(
        account: Account,
        isInitialLoad: Boolean,
        activity: Activity? = null,
        forceRefresh: Boolean = false
    ) {
        val accountId = account.id

        // Avoid launching multiple jobs for the same account unless forcing a refresh
        if (!forceRefresh && folderFetchJobs[accountId]?.isActive == true) {
            Log.d(TAG, "Folder fetch job already active for account $accountId. Skipping.")
            return
        }

        // Cancel any existing job for this account before starting a new one.
        cancelAndRemoveJob(accountId)

        Log.d(
            TAG,
            "Launching folder fetch job for account ${account.username} (ID: $accountId). Force: $forceRefresh, Initial: $isInitialLoad"
        )

        // Update the state to Loading immediately. If forcing a refresh on existing success state,
        // keep showing the old data while loading by not setting Loading state here.
        // However, the current logic sets Loading regardless, providing immediate feedback.
        _folderStates.update { currentMap ->
            currentMap + (accountId to FolderFetchState.Loading)
        }

        // Launch the background job within the externalScope using the IO dispatcher.
        val job = externalScope.launch(ioDispatcher) {
            try {
                // Step 1: Acquire Access Token
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive() // Check for cancellation after suspend function

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(TAG, "Token acquired for ${account.username}, fetching folders...")

                    // Step 2: Fetch Folders from Graph API
                    val foldersResult = graphApiHelper.getMailFolders(accessToken)
                    ensureActive() // Check for cancellation

                    // Step 3: Process Result and Update State
                    val newState = if (foldersResult.isSuccess) {
                        val folders = foldersResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${folders.size} folders for ${account.username}"
                        )
                        FolderFetchState.Success(folders)
                    } else {
                        // Use centralized mapper for Graph errors
                        val errorMsg =
                            ErrorMapper.mapGraphExceptionToUserMessage(foldersResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch folders for ${account.username}: $errorMsg",
                            foldersResult.exceptionOrNull()
                        )
                        FolderFetchState.Error(errorMsg)
                    }
                    // Update the state flow with the result
                    _folderStates.update { it + (accountId to newState) }

                } else { // Token acquisition failed
                    // Use centralized mapper for Auth errors
                    val errorMsg =
                        ErrorMapper.mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for ${account.username}: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive() // Check for cancellation
                    // Update state flow with the error
                    _folderStates.update {
                        it + (accountId to FolderFetchState.Error(errorMsg))
                    }
                }

            } catch (e: CancellationException) {
                // Job was cancelled (e.g., account removed, new request started)
                Log.w(TAG, "Folder fetch job for ${account.username} cancelled.", e)
                // If the state was Loading, potentially remove it or set to an error/initial state.
                // Current logic removes the state entry entirely if cancelled while loading.
                if (_folderStates.value[accountId] is FolderFetchState.Loading) {
                    _folderStates.update { it - accountId }
                }
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                // Catch any other unexpected exceptions during the process
                Log.e(TAG, "Exception during folder fetch for ${account.username}", e)
                ensureActive() // Check for cancellation
                // Determine if it's an auth error or graph error using the mapper
                val errorMsg =
                    ErrorMapper.mapAuthExceptionToUserMessage(e) // mapAuth handles non-MsalException fallback
                // Update state flow with the error
                _folderStates.update {
                    it + (accountId to FolderFetchState.Error(errorMsg))
                }
            } finally {
                // Clean up job reference when the coroutine completes (successfully or exceptionally)
                // Only remove if the completed job is the one currently tracked for this account ID
                if (folderFetchJobs[accountId] == coroutineContext[Job]) {
                    folderFetchJobs.remove(accountId)
                    Log.d(TAG, "Removed completed/failed job reference for account $accountId")
                }
            }
        }
        // Store the reference to the newly launched job.
        folderFetchJobs[accountId] = job

        // Optional: Add completion handler for logging unhandled exceptions, though handled in catch block now.
        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                Log.e(
                    TAG,
                    "Unhandled error potentially missed in folder fetch job for $accountId",
                    cause
                )
                // Defensive state update if somehow error wasn't set
                if (_folderStates.value[accountId] !is FolderFetchState.Error) {
                    val errorMsg = ErrorMapper.mapAuthExceptionToUserMessage(cause)
                    _folderStates.update {
                        it + (accountId to FolderFetchState.Error(errorMsg))
                    }
                }
            }
        }
    }

    /**
     * Safely cancels the active job for a given account ID and removes its reference.
     *
     * @param accountId The ID of the account whose job should be cancelled.
     */
    private fun cancelAndRemoveJob(accountId: String) {
        folderFetchJobs.remove(accountId)?.apply {
            if (isActive) {
                // Provide a reason for cancellation
                cancel(CancellationException("Folder fetch job cancelled for account $accountId"))
                Log.d(TAG, "Cancelled active job for account $accountId")
            }
        }
    }
}