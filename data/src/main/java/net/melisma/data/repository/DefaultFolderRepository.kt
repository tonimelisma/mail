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
import net.melisma.core_data.datasource.MailApiService
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

@Singleton
class DefaultFolderRepository @Inject constructor(
    private val tokenProviders: Map<String, @JvmSuppressWildcards TokenProvider>,
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : FolderRepository {

    private val TAG = "DefaultFolderRepo"

    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())
    private val folderFetchJobs = ConcurrentHashMap<String, Job>()
    private val observedAccounts = ConcurrentHashMap<String, Account>()

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(externalScope.coroutineContext + ioDispatcher) {
            val supportedAccounts = accounts.filter {
                tokenProviders.containsKey(it.providerType.uppercase()) && // Use uppercase for map key consistency
                        mailApiServices.containsKey(it.providerType.uppercase()) &&
                        errorMappers.containsKey(it.providerType.uppercase())
            }

            if (supportedAccounts.size < accounts.size) {
                Log.w(
                    TAG, "Some accounts have unsupported provider types or missing services: ${
                    accounts.filterNot { acc -> supportedAccounts.any { it.id == acc.id } }
                        .map { it.providerType }
                }")
            }

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
                Log.d(
                    TAG,
                    "Adding observed accounts: ${addedAccounts.map { it.username }}"
                ) // CORRECTED: account.username
                addedAccounts.forEach { account ->
                    observedAccounts[account.id] = account
                    val currentState = _folderStates.value[account.id]
                    // Fetch if no state, or if there was an error previously.
                    // Also fetch if current state is Success but has no folders (initial successful fetch might be empty)
                    if (currentState == null || currentState is FolderFetchState.Error ||
                        (currentState is FolderFetchState.Success && currentState.folders.isEmpty())
                    ) { // CORRECTED: Logic for initial/refetch
                        launchFolderFetchJob(account, isInitialLoad = true)
                    } else {
                        Log.d(
                            TAG,
                            "Skipping initial fetch for ${account.username}, state already exists: $currentState"
                        ) // CORRECTED: account.username
                    }
                }
            }
            Log.d(
                TAG,
                "Finished managing observed accounts. Now tracking: ${observedAccounts.values.map { it.username }}"
            ) // CORRECTED: account.username
        }
    }

    override suspend fun refreshAllFolders(activity: Activity?) {
        val accountsToRefresh = observedAccounts.values.toList()
        Log.d(TAG, "Refreshing folders for ${accountsToRefresh.size} observed accounts...")
        accountsToRefresh.forEach { account ->
            val providerType =
                account.providerType.uppercase() // Use uppercase for map key consistency
            if (tokenProviders.containsKey(providerType) &&
                mailApiServices.containsKey(providerType) &&
                errorMappers.containsKey(providerType)
            ) {
                launchFolderFetchJob(
                    account,
                    isInitialLoad = false,
                    activity = activity,
                    forceRefresh = true
                )
            } else {
                Log.w(
                    TAG,
                    "Skipping refresh for account ${account.username} due to unsupported provider type or missing services. Available mappers: ${errorMappers.keys}"
                ) // CORRECTED: account.username
                _folderStates.update { currentMap ->
                    currentMap + (account.id to FolderFetchState.Error("Cannot refresh: Unsupported account provider type: ${account.providerType}, or missing services."))
                }
            }
        }
    }

    private fun launchFolderFetchJob(
        account: Account,
        isInitialLoad: Boolean,
        activity: Activity? = null,
        forceRefresh: Boolean = false
    ) {
        val accountId = account.id
        val providerType = account.providerType.uppercase() // Use uppercase for map key consistency

        if (!forceRefresh && folderFetchJobs[accountId]?.isActive == true) {
            Log.d(
                TAG,
                "Folder fetch job already active for account ${account.username}. Skipping."
            ) // CORRECTED: account.username
            return
        }
        cancelAndRemoveJob(accountId)

        Log.d(
            TAG,
            "Launching folder fetch job for account ${account.username} (ID: $accountId). Force: $forceRefresh, Initial: $isInitialLoad"
        ) // CORRECTED: account.username
        _folderStates.update { currentMap ->
            currentMap + (accountId to FolderFetchState.Loading)
        }

        val tokenProvider = tokenProviders[providerType]
        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (tokenProvider == null || mailApiService == null || errorMapper == null) {
            Log.w(
                TAG,
                "No provider, API service, or error mapper available for account type: $providerType. Available mappers: ${errorMappers.keys}"
            )
            _folderStates.update { it + (accountId to FolderFetchState.Error("Setup error for account type: $providerType")) }
            return
        }

        val job = externalScope.launch(ioDispatcher) {
            try {
                val tokenResult =
                    tokenProvider.getAccessToken(account, listOf("Mail.Read"), activity)
                ensureActive()

                if (tokenResult.isSuccess) {
                    val accessToken = tokenResult.getOrThrow()
                    Log.d(
                        TAG,
                        "Token acquired for ${account.username}, fetching folders..."
                    ) // CORRECTED: account.username
                    val foldersResult = mailApiService.getMailFolders(accessToken)
                    ensureActive()

                    val newState = if (foldersResult.isSuccess) {
                        val folders = foldersResult.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched ${folders.size} folders for ${account.username}"
                        ) // CORRECTED: account.username
                        FolderFetchState.Success(folders)
                    } else {
                        val errorMsg =
                            errorMapper.mapNetworkOrApiException(foldersResult.exceptionOrNull())
                        Log.e(
                            TAG,
                            "Failed to fetch folders for ${account.username}: $errorMsg",
                            foldersResult.exceptionOrNull()
                        ) // CORRECTED: account.username
                        FolderFetchState.Error(errorMsg)
                    }
                    if (isActive && observedAccounts.containsKey(accountId)) {
                        _folderStates.update { it + (accountId to newState) }
                    } else {
                        Log.w(
                            TAG,
                            "Folder fetch completed but account ${account.username} no longer observed or coroutine inactive. Discarding result."
                        ) // CORRECTED: account.username
                    }
                } else {
                    val errorMsg =
                        errorMapper.mapAuthExceptionToUserMessage(tokenResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "Failed to acquire token for ${account.username}: $errorMsg",
                        tokenResult.exceptionOrNull()
                    ) // CORRECTED: account.username
                    ensureActive()
                    if (isActive && observedAccounts.containsKey(accountId)) {
                        _folderStates.update { it + (accountId to FolderFetchState.Error(errorMsg)) }
                    } else {
                        Log.w(
                            TAG,
                            "Token fetch failed but account ${account.username} no longer observed or coroutine inactive. Discarding error."
                        ) // CORRECTED: account.username
                    }
                }
            } catch (e: CancellationException) {
                Log.w(
                    TAG,
                    "Folder fetch job for ${account.username} cancelled.",
                    e
                ) // CORRECTED: account.username
                if (isActive && observedAccounts.containsKey(accountId) && _folderStates.value[accountId] is FolderFetchState.Loading) {
                    _folderStates.update { currentMap ->
                        // Remove the loading state, effectively resetting it to "not loaded" or whatever the absence of a state implies
                        currentMap - accountId // CORRECTED: No specific "Initial" state, just remove current loading state
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Exception during folder fetch for ${account.username}",
                    e
                ) // CORRECTED: account.username
                ensureActive()
                val errorMsg = errorMapper.mapNetworkOrApiException(e)
                if (isActive && observedAccounts.containsKey(accountId)) {
                    _folderStates.update { it + (accountId to FolderFetchState.Error(errorMsg)) }
                } else {
                    Log.w(
                        TAG,
                        "Exception caught but account ${account.username} no longer observed or coroutine inactive. Discarding error."
                    ) // CORRECTED: account.username
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
                Log.e(TAG, "Unhandled error in folder fetch job for account ID $accountId", cause)
                if (observedAccounts.containsKey(accountId) && _folderStates.value[accountId] !is FolderFetchState.Error) {
                    val providerErrorMapper =
                        errorMappers[account.providerType.uppercase()] // Use uppercase for consistency
                    val errorMsg = providerErrorMapper?.mapNetworkOrApiException(cause)
                        ?: "Unknown error after job completion for $providerType."
                    _folderStates.update { it + (accountId to FolderFetchState.Error(errorMsg)) }
                }
            }
        }
    }

    private fun cancelAndRemoveJob(accountId: String) {
        folderFetchJobs.remove(accountId)?.apply {
            if (isActive) {
                cancel(CancellationException("Job cancelled for account $accountId due to removal or refresh."))
                Log.d(TAG, "Cancelled active job for account $accountId")
            }
        }
    }
}