package net.melisma.data.repository

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.Message
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.data.mapper.toDomainAccount
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DefaultFolderRepository @Inject constructor(
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope, // Still useful for sync jobs not tied to ViewModel
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val accountDao: AccountDao,
    private val folderDao: FolderDao
) : FolderRepository {

    private val TAG = "DefaultFolderRepo"

    // This will hold the combined state from DB and ongoing sync operations.
    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())

    // To keep track of ongoing sync jobs per account
    private val syncJobs = ConcurrentHashMap<String, Job>()

    init {
        Log.d(TAG, "Initializing DefaultFolderRepository with DB support.")
        observeDatabaseChanges()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDatabaseChanges() {
        externalScope.launch(ioDispatcher) { // Use externalScope for long-lived observation
            accountDao.getAllAccounts()
                .flatMapLatest { accountEntities ->
                    if (accountEntities.isEmpty()) {
                        flowOf(emptyMap<String, FolderFetchState>())
                    } else {
                        val accountFolderFlows: List<Flow<Pair<String, FolderFetchState>>> =
                            accountEntities.map { accountEntity ->
                                val accountId = accountEntity.id
                                folderDao.getFoldersForAccount(accountId)
                                    .map<List<net.melisma.core_db.entity.FolderEntity>, FolderFetchState> { folderEntities ->
                                        FolderFetchState.Success(folderEntities.map { it.toDomainModel() })
                                    }
                                    .catch { e ->
                                        Log.e(
                                            TAG,
                                            "Error observing folders for account $accountId from DB",
                                            e
                                        )
                                        // Emit previous state or an error state if critical
                                        // For now, if DB fails, it's a significant issue, reflect error.
                                        emit(FolderFetchState.Error("DB error: ${e.localizedMessage}"))
                                    }
                                    .map { state -> accountId to state }
                            }

                        combine(accountFolderFlows) { statesArray ->
                            statesArray.toMap()
                        }
                    }
                }
                .distinctUntilChanged()
                .onEach { newDbStates ->
                    _folderStates.update { currentApiStates ->
                        // Merge DB state with API-driven states (like Loading/Error from ongoing sync)
                        // DB is source of truth for Success. API sync modifies Loading/Error states.
                        val mergedStates = newDbStates.toMutableMap()
                        currentApiStates.forEach { (accountId, state) ->
                            if (state is FolderFetchState.Loading || state is FolderFetchState.Error) {
                                // If DB has success for this account, and API state is Loading/Error
                                // from a *previous* operation, let DB success take precedence.
                                // However, if an active sync is happening, its Loading/Error state is more current.
                                if (syncJobs[accountId]?.isActive != true && newDbStates[accountId] is FolderFetchState.Success) {
                                    // Keep the DB success state
                                } else {
                                    mergedStates[accountId] = state // Keep API-driven Loading/Error
                                }
                            } else if (!mergedStates.containsKey(accountId)) {
                                // This case should ideally not happen if getAllAccounts is comprehensive
                                mergedStates[accountId] = state
                            }
                        }
                        mergedStates
                    }
                }
                .catch { e ->
                    Log.e(
                        TAG,
                        "Error in observeDatabaseChanges flatMapLatest/combine",
                        e
                    )
                }
                .launchIn(this) // Launch in the externalScope's coroutine context
        }
    }

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(ioDispatcher) {
            Log.i(TAG, "manageObservedAccounts called with ${accounts.size} accounts.")
            val currentDbAccountEntities = accountDao.getAllAccounts().first() // Get current state
            val currentDbAccountIds = currentDbAccountEntities.map { it.id }.toSet()
            val newApiAccountIds = accounts.map { it.id }.toSet()

            // Accounts to remove from DB
            val removedAccountIds = currentDbAccountIds - newApiAccountIds
            removedAccountIds.forEach { accountId ->
                Log.i(TAG, "Removing account $accountId from DB and cancelling its syncs.")
                syncJobs[accountId]?.cancel(CancellationException("Account removed"))
                syncJobs.remove(accountId)
                folderDao.deleteAllFoldersForAccount(accountId) // Clears folders
                accountDao.deleteAccount(accountId) // Clears account
                _folderStates.update { it - accountId } // Also update local state immediately
            }

            val accountEntitiesToUpsert = accounts.map { it.toEntity() }
            accountDao.insertOrUpdateAccounts(accountEntitiesToUpsert)
            Log.i(TAG, "Upserted ${accountEntitiesToUpsert.size} accounts into DB.")

            // For new or existing accounts, trigger an initial sync if no data or upon request
            // This logic might need refinement based on "initial sync" requirements vs. regular refresh
            accounts.forEach { account ->
                val currentState = _folderStates.value[account.id]
                // If no folders yet from DB ( Success(emptyList) ) or an error state, try to sync.
                val needsSync = currentState == null ||
                        currentState is FolderFetchState.Error ||
                        (currentState is FolderFetchState.Success && currentState.folders.isEmpty())

                if (needsSync) {
                    Log.d(
                        TAG,
                        "manageObservedAccounts: Account ${account.username} needs initial folder sync. Current state: $currentState"
                    )
                    // Use externalScope for sync jobs that shouldn't be tied to a ViewModel lifecycle
                    // but should persist as long as the repository (Singleton) exists.
                    refreshFoldersForAccountInternal(
                        account,
                        null,
                        reasonSuffix = "manageObservedAccounts"
                    )
                }
            }
        }
    }

    private fun refreshFoldersForAccountInternal(
        account: Account,
        activity: Activity?, // For potential re-auth, though less common for folder lists
        forceRefresh: Boolean = true, // Default to true for explicit calls
        reasonSuffix: String
    ) {
        val accountId = account.id
        val providerType = account.providerType.uppercase()

        if (!forceRefresh && syncJobs[accountId]?.isActive == true) {
            Log.i(
                TAG,
                "Sync job already active for account '${account.username}'. Skipping: $reasonSuffix."
            )
            return
        }
        syncJobs[accountId]?.cancel(CancellationException("New sync request: $reasonSuffix"))

        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (mailApiService == null || errorMapper == null) {
            Log.e(
                TAG,
                "Cannot sync folders for ${account.username}. Missing service for provider $providerType."
            )
            _folderStates.update { currentMap ->
                currentMap + (accountId to FolderFetchState.Error("Setup error for $providerType"))
            }
            return
        }

        syncJobs[accountId] =
            externalScope.launch(ioDispatcher + SupervisorJob()) { // Use SupervisorJob for this specific sync
                Log.i(
                    TAG,
                    "Starting folder sync for ${account.username} (ID: $accountId). Reason: $reasonSuffix"
                )
                _folderStates.update { currentMap ->
                    currentMap + (accountId to FolderFetchState.Loading)
                }

            try {
                val foldersResult = mailApiService.getMailFolders() // API call
                ensureActive()

                if (foldersResult.isSuccess) {
                    val apiFolders = foldersResult.getOrThrow()
                    Log.i(
                        TAG,
                        "Successfully fetched ${apiFolders.size} folders for ${account.username} from API."
                    )
                    val folderEntities = apiFolders.map { it.toEntity(accountId) }
                    folderDao.insertOrUpdateFolders(folderEntities) // Save to DB
                    // DB observation via observeDatabaseChanges will update _folderStates with Success
                    Log.i(
                        TAG,
                        "Saved ${folderEntities.size} folder entities to DB for ${account.username}."
                    )
                    // Explicitly update to success if DB observation is too slow or for immediate feedback
                    // This might cause a quick Loading -> Success -> Success(from DB) flicker.
                    // Prefer relying on the DB flow to naturally update.
                    // _folderStates.update { currentMap ->
                    //     currentMap + (accountId to FolderFetchState.Success(apiFolders)) // apiFolders are List<MailFolder>
                    // }

                } else {
                    val exception = foldersResult.exceptionOrNull()
                    val errorDetails = errorMapper.mapExceptionToErrorDetails(exception)
                    Log.e(
                        TAG,
                        "Error syncing folders for ${account.username}: ${errorDetails.message}",
                        exception
                    )
                    _folderStates.update { currentMap ->
                        currentMap + (accountId to FolderFetchState.Error(errorDetails.message))
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "Folder sync cancelled for ${account.username}: $reasonSuffix", e)
                // If state was loading, it might be appropriate to revert or set to error.
                // If cancellation was due to account removal, state is already cleared.
                if (isActive && _folderStates.value[accountId] is FolderFetchState.Loading) {
                    _folderStates.update { currentMap ->
                        currentMap + (accountId to FolderFetchState.Error("Sync cancelled"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during folder sync for ${account.username}: $reasonSuffix", e)
                if (isActive) {
                    val errorDetails = errorMapper.mapExceptionToErrorDetails(e)
                    _folderStates.update { currentMap ->
                        currentMap + (accountId to FolderFetchState.Error(errorDetails.message))
                    }
                }
            } finally {
                // remove job only if this is the job that completed, not a new one that started for same account
                if (syncJobs[accountId] == coroutineContext[Job]) {
                    syncJobs.remove(accountId)
                }
                Log.d(TAG, "Folder sync job ended for ${account.username}. Reason: $reasonSuffix.")
            }
            }
    }


    override suspend fun refreshFoldersForAccount(accountId: String, activity: Activity?) {
        val accountEntity = accountDao.getAccountById(accountId).first() // Get from DB
        if (accountEntity == null) {
            Log.w(TAG, "Account $accountId not found in DB for refresh.")
            _folderStates.update { it + (accountId to FolderFetchState.Error("Account not found")) }
            return
        }
        val account = accountEntity.toDomainAccount()
        Log.i(TAG, "refreshFoldersForAccount called for ${account.username}")
        refreshFoldersForAccountInternal(
            account,
            activity,
            forceRefresh = true,
            reasonSuffix = "explicit refresh"
        )
    }

    // refreshAllFolders needs to be adapted if it's still used.
    // It would iterate over accounts from accountDao and call refreshFoldersForAccountInternal.
    override suspend fun refreshAllFolders(activity: Activity?) {
        Log.i(TAG, "refreshAllFolders called.")
        val allAccountEntities = accountDao.getAllAccounts().first()
        if (allAccountEntities.isEmpty()) {
            Log.i(TAG, "No accounts in DB to refresh.")
            return
        }
        allAccountEntities.forEach { accountEntity ->
            val account = accountEntity.toDomainAccount()
            // Check if service for this providerType is available before launching
            val providerType = account.providerType.uppercase()
            if (mailApiServices.containsKey(providerType) && errorMappers.containsKey(providerType)) {
                refreshFoldersForAccountInternal(
                    account,
                    activity,
                    forceRefresh = true,
                    reasonSuffix = "global refreshAllFolders"
                )
            } else {
                Log.w(
                    TAG,
                    "refreshAllFolders: Skipping ${account.username} as provider $providerType is not supported."
                )
                _folderStates.update { currentMap ->
                    currentMap + (account.id to FolderFetchState.Error("Unsupported provider for refresh"))
                }
            }
        }
    }


    // --- Stub implementations for other FolderRepository methods ---
    override suspend fun syncFolderContents(accountId: String, folderId: String): Result<Unit> {
        Log.d(
            TAG,
            "syncFolderContents called for accountId: $accountId, folderId: $folderId (NOT IMPLEMENTED WITH DB)"
        )
        return Result.failure(NotImplementedError("syncFolderContents not implemented with DB"))
    }

    override fun getThreadsInFolder(accountId: String, folderId: String): Flow<List<MailThread>> {
        Log.d(
            TAG,
            "getThreadsInFolder called for accountId: $accountId, folderId: $folderId (NOT IMPLEMENTED WITH DB)"
        )
        return flowOf(emptyList())
    }

    override fun getMessagesInFolder(accountId: String, folderId: String): Flow<List<Message>> {
        Log.d(
            TAG,
            "getMessagesInFolder called for accountId: $accountId, folderId: $folderId (NOT IMPLEMENTED WITH DB)"
        )
        return flowOf(emptyList())
    }
}