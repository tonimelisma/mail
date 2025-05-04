package net.melisma.backend_microsoft.repository

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
import net.melisma.backend_microsoft.errors.ErrorMapper
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.repository.FolderRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Microsoft implementation of the [FolderRepository] interface.
 * Fetches mail folders for Microsoft accounts using the Microsoft Graph API.
 * Manages fetching state per account and handles token acquisition.
 */
@Singleton
class MicrosoftFolderRepository @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val graphApiHelper: GraphApiHelper,
    // Use the qualifier imported from core-data
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    // Use the qualifier imported from core-data
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMapper: ErrorMapper
) : FolderRepository {

    private val TAG = "MsFolderRepository"

    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())
    private val folderFetchJobs = ConcurrentHashMap<String, Job>()
    private val observedAccounts = ConcurrentHashMap<String, Account>()

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(externalScope.coroutineContext + ioDispatcher) {
            val newMicrosoftAccounts = accounts.filter { it.providerType == "MS" }
            val newAccountIds = newMicrosoftAccounts.map { it.id }.toSet()
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

            val addedAccounts = newMicrosoftAccounts.filter { it.id !in currentAccountIds }
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
                            errorMapper.mapGraphExceptionToUserMessage(foldersResult.exceptionOrNull())
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
                if (observedAccounts.containsKey(accountId) && _folderStates.value[accountId] is FolderFetchState.Loading) {
                    _folderStates.update { it - accountId }
                }
                throw e
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
                if (folderFetchJobs[accountId] == coroutineContext[Job]) {
                    folderFetchJobs.remove(accountId)
                }
            }
        }
        folderFetchJobs[accountId] = job

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
                cancel(CancellationException("Job cancelled for account $accountId"))
                Log.d(TAG, "Cancelled active job for account $accountId")
            }
        }
    }
}