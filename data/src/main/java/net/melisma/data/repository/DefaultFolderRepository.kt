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
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : FolderRepository {

    private val TAG = "DefaultFolderRepo" // Logging TAG

    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())
    private val folderFetchJobs = ConcurrentHashMap<String, Job>()
    private val observedAccounts = ConcurrentHashMap<String, Account>()

    init {
        Log.d(
            TAG, "Initializing DefaultFolderRepository. Injected maps:" +
                    " mailApiServices keys: ${mailApiServices.keys}," +
                    " errorMappers keys: ${errorMappers.keys}"
        )
    }

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(externalScope.coroutineContext + ioDispatcher) { // Using externalScope.coroutineContext as per original
            Log.i(
                TAG,
                "manageObservedAccounts called with ${accounts.size} accounts: ${accounts.joinToString { it.username + " (" + it.providerType + ")" }}"
            )
            Log.d(
                TAG,
                "Current injected map sizes: APIs=${mailApiServices.size}, Mappers=${errorMappers.size}"
            )
            Log.d(TAG, "Current mailApiServices keys: ${mailApiServices.keys}")
            Log.d(TAG, "Current errorMappers keys: ${errorMappers.keys}")

            val supportedAccounts = accounts.filter { account ->
                val providerKey = account.providerType.uppercase()
                val hasMailApiService = mailApiServices.containsKey(providerKey)
                val hasErrorMapper = errorMappers.containsKey(providerKey)

                Log.d(
                    TAG,
                    "manageObservedAccounts: Checking support for account '${account.username}' (Provider: '${account.providerType}', Key: '$providerKey') -> " +
                            "hasMailApiService=$hasMailApiService, " +
                            "hasErrorMapper=$hasErrorMapper. Overall supported: ${hasMailApiService && hasErrorMapper}"
                )
                hasMailApiService && hasErrorMapper
            }

            Log.i(
                TAG,
                "manageObservedAccounts: Found ${supportedAccounts.size} supported accounts out of ${accounts.size}."
            )

            if (supportedAccounts.size < accounts.size) {
                val unsupported =
                    accounts.filterNot { acc -> supportedAccounts.any { it.id == acc.id } }
                Log.w(
                    TAG,
                    "manageObservedAccounts: Some accounts have unsupported provider types or missing services: ${unsupported.joinToString { it.providerType + " (" + it.username + ")" }}"
                )
            }

            val newAccountIds = supportedAccounts.map { it.id }.toSet()
            val currentAccountIds = observedAccounts.keys.toSet()
            Log.d(
                TAG,
                "manageObservedAccounts: New account IDs: $newAccountIds, Current account IDs: $currentAccountIds"
            )


            val removedAccountIds = currentAccountIds - newAccountIds
            if (removedAccountIds.isNotEmpty()) {
                Log.i(
                    TAG,
                    "manageObservedAccounts: Removing ${removedAccountIds.size} observed accounts: $removedAccountIds"
                )
                removedAccountIds.forEach { accountId ->
                    cancelAndRemoveJob(accountId, "Account removed from observed list")
                    observedAccounts.remove(accountId)
                    _folderStates.update { currentMap ->
                        Log.d(
                            TAG,
                            "manageObservedAccounts: Removing folder state for accountId '$accountId'"
                        )
                        currentMap - accountId
                    }
                }
            }

            val addedAccounts = supportedAccounts.filter { it.id !in currentAccountIds }
            if (addedAccounts.isNotEmpty()) {
                Log.i(
                    TAG,
                    "manageObservedAccounts: Adding ${addedAccounts.size} new observed accounts: ${addedAccounts.joinToString { it.username }}"
                )
                addedAccounts.forEach { account ->
                    observedAccounts[account.id] = account
                    val currentState = _folderStates.value[account.id]
                    Log.d(
                        TAG,
                        "manageObservedAccounts: For added account '${account.username}', current folder state is: $currentState"
                    )
                    if (currentState == null || currentState is FolderFetchState.Error ||
                        (currentState is FolderFetchState.Success && currentState.folders.isEmpty())
                    ) {
                        Log.d(
                            TAG,
                            "manageObservedAccounts: Condition met to launch initial folder fetch for '${account.username}'."
                        )
                        launchFolderFetchJob(
                            account,
                            isInitialLoad = true,
                            reasonSuffix = "newly observed or errored"
                        )
                    } else {
                        Log.d(
                            TAG,
                            "manageObservedAccounts: Skipping initial fetch for ${account.username}, state ($currentState) seems okay."
                        )
                    }
                }
            }
            Log.i(
                TAG,
                "manageObservedAccounts: Finished. Now tracking ${observedAccounts.size} accounts: ${observedAccounts.values.joinToString { it.username }}"
            )
        }
    }

    override suspend fun refreshAllFolders(activity: Activity?) {
        val accountsToRefresh = observedAccounts.values.toList()
        Log.i(
            TAG,
            "refreshAllFolders called. Will attempt to refresh folders for ${accountsToRefresh.size} observed accounts."
        )
        accountsToRefresh.forEach { account ->
            val providerType = account.providerType.uppercase()
            Log.d(
                TAG,
                "refreshAllFolders: Checking support for account '${account.username}' (Provider: '$providerType')"
            )
            if (mailApiServices.containsKey(providerType) &&
                errorMappers.containsKey(providerType)
            ) {
                Log.d(
                    TAG,
                    "refreshAllFolders: Provider '$providerType' for '${account.username}' is supported. Launching fetch."
                )
                launchFolderFetchJob(
                    account,
                    isInitialLoad = false,
                    activity = activity,
                    forceRefresh = true,
                    reasonSuffix = "global refreshAllFolders"
                )
            } else {
                Log.w(
                    TAG,
                    "refreshAllFolders: Skipping refresh for account ${account.username} (Provider: '$providerType') due to unsupported provider type or missing services."
                )
                _folderStates.update { currentMap ->
                    Log.w(
                        TAG,
                        "refreshAllFolders: Setting Error state for account '${account.username}' (ID: ${account.id}) due to unsupported provider for refresh."
                    )
                    currentMap + (account.id to FolderFetchState.Error("Cannot refresh: Unsupported account provider type: ${account.providerType}, or missing services."))
                }
            }
        }
    }

    private fun launchFolderFetchJob(
        account: Account,
        isInitialLoad: Boolean,
        activity: Activity? = null,
        forceRefresh: Boolean = false,
        reasonSuffix: String = "unknown reason" // Added for better logging
    ) {
        val accountId = account.id
        val providerType = account.providerType.uppercase()
        Log.d(
            TAG,
            "launchFolderFetchJob: Preparing for account '${account.username}' (ID: $accountId, Provider: $providerType). Force: $forceRefresh, Initial: $isInitialLoad, Reason: $reasonSuffix. Current job active: ${folderFetchJobs[accountId]?.isActive}"
        )

        if (!forceRefresh && folderFetchJobs[accountId]?.isActive == true) {
            Log.i(
                TAG,
                "launchFolderFetchJob: Folder fetch job already active for account '${account.username}'. Skipping."
            )
            return
        }
        cancelAndRemoveJob(accountId, "launchFolderFetchJob new fetch for $reasonSuffix")

        Log.i(
            TAG,
            "launchFolderFetchJob: Setting Loading state for account '${account.username}' (ID: $accountId)"
        )
        _folderStates.update { currentMap ->
            currentMap + (accountId to FolderFetchState.Loading)
        }

        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (mailApiService == null || errorMapper == null) {
            Log.e(
                TAG,
                "launchFolderFetchJob: Cannot proceed for account '${account.username}'. Missing service for providerType '$providerType'. " +
                        "MailApiService null? ${mailApiService == null}. " +
                        "ErrorMapper null? ${errorMapper == null}."
            )
            _folderStates.update { currentMap ->
                currentMap + (accountId to FolderFetchState.Error("Setup error for account type: $providerType"))
            }
            return
        }

        Log.i(
            TAG,
            "launchFolderFetchJob: Launching actual job for account '${account.username}' (ID: $accountId). Provider: $providerType, Initial: $isInitialLoad"
        )
        folderFetchJobs[accountId] = externalScope.launch(ioDispatcher) {
            Log.i(TAG, "launchFolderFetchJob: Coroutine started for account '${account.username}'.")
            try {
                val foldersResult = mailApiService.getMailFolders()
                ensureActive()

                if (foldersResult.isSuccess) {
                    val folders = foldersResult.getOrNull() ?: emptyList()
                    if (!isActive) {
                        Log.i(
                            TAG,
                            "launchFolderFetchJob: Scope became inactive during fetch for '${account.username}'. Job cancelled by parent scope perhaps."
                        )
                        return@launch
                    }
                    Log.i(
                        TAG,
                        "launchFolderFetchJob: Successfully fetched ${folders.size} folders for account '${account.username}'."
                    )
                    _folderStates.update { currentMap ->
                        currentMap + (accountId to FolderFetchState.Success(folders))
                    }
                } else {
                    val exception = foldersResult.exceptionOrNull()
                        ?: IllegalStateException("Unknown error fetching folders")
                    Log.w(
                        TAG,
                        "launchFolderFetchJob: Failed to fetch folders for account '${account.username}'.",
                        exception
                    )
                    val details = errorMapper.mapExceptionToErrorDetails(exception)
                    _folderStates.update {
                        it + (accountId to FolderFetchState.Error(details.message))
                    }
                }
            } catch (e: CancellationException) {
                ensureActive() // Re-throw if the scope itself is cancelled
                Log.i(
                    TAG,
                    "launchFolderFetchJob: Folder fetch cancelled for account '${account.username}'.",
                    e
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "launchFolderFetchJob: Exception fetching folders for account '${account.username}'.",
                    e
                )
                ensureActive()
                val details = errorMapper.mapExceptionToErrorDetails(e)
                _folderStates.update {
                    it + (accountId to FolderFetchState.Error(details.message))
                }
            } finally {
                if (isActive) {
                    Log.d(
                        TAG,
                        "launchFolderFetchJob: Coroutine finished for account '${account.username}'. Removing job from map."
                    )
                }
                folderFetchJobs.remove(accountId)
            }
        }
        Log.d(TAG, "launchFolderFetchJob: Job launched for account '${account.username}'.")
    }

    private fun cancelAndRemoveJob(accountId: String, reason: String) {
        val jobToCancel = folderFetchJobs[accountId]
        if (jobToCancel != null) {
            Log.d(
                TAG,
                "cancelAndRemoveJob for accountId '$accountId'. Reason: '$reason'. Current job active: ${jobToCancel.isActive}. Hash: ${jobToCancel.hashCode()}"
            )
            folderFetchJobs.remove(accountId) // Remove first to prevent re-assignment issues in racing conditions
            if (jobToCancel.isActive) {
                jobToCancel.cancel(CancellationException("Job cancelled for account $accountId: $reason"))
                Log.i(
                    TAG,
                    "Previous folder fetch job (Hash: ${jobToCancel.hashCode()}) for account '$accountId' cancelled due to: $reason"
                )
            }
        } else {
            Log.d(
                TAG,
                "cancelAndRemoveJob for accountId '$accountId'. Reason: '$reason'. No active job to cancel."
            )
        }
    }
}