package net.melisma.data.repository

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
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.repository.FolderRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default implementation of the [FolderRepository] interface.
 * Currently only handles Microsoft accounts using Microsoft Graph API.
 * Future: Will be extended to support multiple account types (Google, etc.).
 */
@Singleton
class DefaultFolderRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper, // Will be replaced with a Map<String, ApiHelper> in future
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapperService
) : FolderRepository {

    private val TAG = "DefaultFolderRepo"

    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())
    private val folderFetchJobs = ConcurrentHashMap<String, Job>()
    private val observedAccounts = ConcurrentHashMap<String, Account>()

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(externalScope.coroutineContext + ioDispatcher) {
            // Currently only filter for Microsoft accounts
            // Future: Handle accounts of all supported provider types
            val supportedAccounts = accounts.filter { it.providerType == "MS" }
            val newAccountIds = supportedAccounts.map { it.id }.toSet()
            val currentAccountIds = observedAccounts.keys.toSet()

            val removedAccountIds = currentAccountIds - newAccountIds
            if (removedAccountIds.isNotEmpty()) {
                Log.d(TAG, "Removing observed accounts: $removedAccountIds")
                removedAccountIds.forEach { accountId ->
                    cancelAndRemoveJob(accountId)
                    observedAccounts.remove(accountId)
                    _folderStates.update { it - accountId }
                }
            }

            val addedAccounts = supportedAccounts.filter { it.id !in currentAccountIds }
            if (addedAccounts.isNotEmpty()) {
                Log.d(TAG, "Adding observed accounts: ${addedAccounts.map { it.id }}")
                addedAccounts.forEach { account ->
                    observedAccounts[account.id] = account
                    val currentState = _folderStates.value[account.id]
                    if (currentState == null || currentState is FolderFetchState.Error) {
                        launchFolderFetchJob(account, isInitialLoad = true)
                    } else {
                        Log.d(
                            TAG,
                            "Skipping initial fetch for ${account.username}, state already exists: $currentState"
                        )
                    }
                }
            }
            Log.d(
                TAG,
                "Finished managing observed accounts. Now tracking: ${observedAccounts.keys}"
            )
        }
    }

    override suspend fun refreshAllFolders(activity: Activity?) {
        val accountsToRefresh = observedAccounts.values.toList()
        Log.d(TAG, "Refreshing folders for ${accountsToRefresh.size} observed accounts...")
        accountsToRefresh.forEach { account ->
            launchFolderFetchJob(
                account,
                isInitialLoad = false,
                activity = activity,
                forceRefresh = true
            )
        }
    }

    private fun launchFolderFetchJob(
        account: Account,
        isInitialLoad: Boolean,
        activity: Activity? = null,
        forceRefresh: Boolean = false
    ) {
        val accountId = account.id

        if (!forceRefresh && folderFetchJobs[accountId]?.isActive == true) {
            Log.d(TAG, "Folder fetch job already active for account $accountId. Skipping.")
            return
        }
        cancelAndRemoveJob(accountId)

        Log.d(
            TAG,
            "Launching folder fetch job for account ${account.username} (ID: $accountId). Force: $forceRefresh, Initial: $isInitialLoad"
        )
        _folderStates.update { currentMap ->
            currentMap + (accountId to FolderFetchState.Loading)
        }

        // Based on account provider type, use different API helpers
        // Currently, only Microsoft is supported
        if (account.providerType != "MS") {
            Log.w(TAG, "Unsupported account provider type: ${account.providerType}")
            _folderStates.update { it + (accountId to FolderFetchState.Error("Unsupported account provider type")) }
            return
        }

        val job = externalScope.launch(ioDispatcher) {
            try {
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive()

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(TAG, "Token acquired for ${account.username}, fetching folders...")
                    val foldersResult = graphApiHelper.getMailFolders(accessToken)
                    ensureActive()

                    val newState = if (foldersResult.isSuccess) {
                        val folders = foldersResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${folders.size} folders for ${account.username}"
                        )
                        FolderFetchState.Success(folders)
                    } else {
                        val errorMsg =
                            errorMapper.mapNetworkOrApiException(foldersResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch folders for ${account.username}: $errorMsg",
                            foldersResult.exceptionOrNull()
                        )
                        FolderFetchState.Error(errorMsg)
                    }
                    if (observedAccounts.containsKey(accountId)) {
                        _folderStates.update { it + (accountId to newState) }
                    } else {
                        Log.w(
                            TAG,
                            "Folder fetch completed but account $accountId no longer observed. Discarding result."
                        )
                    }

                } else { // Token failure
                    val errorMsg =
                        errorMapper.mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for ${account.username}: $errorMsg",
                        tokenResult.exceptionOrNull()
                    )
                    ensureActive()
                    if (observedAccounts.containsKey(accountId)) {
                        _folderStates.update { it + (accountId to FolderFetchState.Error(errorMsg)) }
                    } else {
                        Log.w(
                            TAG,
                            "Token fetch failed but account $accountId no longer observed. Discarding error."
                        )
                    }
                }

            } catch (e: CancellationException) {
                Log.w(TAG, "Folder fetch job for ${account.username} cancelled.", e)
                // Optionally reset state if cancelled during loading
                if (observedAccounts.containsKey(accountId) && _folderStates.value[accountId] is FolderFetchState.Loading) {
                    _folderStates.update { currentMap ->
                        if (currentMap[accountId] is FolderFetchState.Loading) {
                            currentMap - accountId // Remove loading state on cancel
                        } else {
                            currentMap // Keep success/error state if already set
                        }
                    }
                }
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Exception during folder fetch for ${account.username}", e)
                ensureActive()
                val errorMsg = errorMapper.mapAuthExceptionToUserMessage(e)
                if (observedAccounts.containsKey(accountId)) {
                    _folderStates.update { it + (accountId to FolderFetchState.Error(errorMsg)) }
                } else {
                    Log.w(
                        TAG,
                        "Exception caught but account $accountId no longer observed. Discarding error."
                    )
                }
            } finally {
                // Clean up job reference if this is the job completing
                if (folderFetchJobs[accountId] == coroutineContext[Job]) {
                    folderFetchJobs.remove(accountId)
                }
            }
        }
        folderFetchJobs[accountId] = job

        // Optional: Handle unhandled job failures
        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException && externalScope.isActive) {
                Log.e(TAG, "Unhandled error in folder fetch job for $accountId", cause)
                if (observedAccounts.containsKey(accountId) && _folderStates.value[accountId] !is FolderFetchState.Error) {
                    val errorMsg = errorMapper.mapAuthExceptionToUserMessage(cause)
                    _folderStates.update { it + (accountId to FolderFetchState.Error(errorMsg)) }
                }
            }
        }
    }

    private fun cancelAndRemoveJob(accountId: String) {
        folderFetchJobs.remove(accountId)?.apply {
            if (isActive) {
                // Provide a more specific cancellation message
                cancel(CancellationException("Job cancelled for account $accountId due to removal or refresh."))
                Log.d(TAG, "Cancelled active job for account $accountId")
            }
        }
    }
}