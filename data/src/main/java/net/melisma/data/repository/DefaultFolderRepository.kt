package net.melisma.data.repository

import android.app.Activity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.Message
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.data.mapper.toDomainAccount
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import net.melisma.data.sync.SyncEngine
import timber.log.Timber
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
    private val folderDao: FolderDao,
    private val syncEngine: SyncEngine // Inject SyncEngine
) : FolderRepository {

    // This will hold the combined state from DB and ongoing sync operations.
    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())

    // To keep track of ongoing sync jobs per account
    private val syncJobs = ConcurrentHashMap<String, Job>()

    init {
        Timber.d("Initializing DefaultFolderRepository with DB support.")
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
                                        // Check if folder list sync is needed: no folders locally, account not local-only, and folder list never synced according to AccountEntity.
                                        if (folderEntities.isEmpty() && !accountEntity.isLocalOnly && accountEntity.lastFolderListSyncTimestamp == null) {
                                            Timber.d("No folders in DB for ${accountEntity.emailAddress} (lastFolderListSyncTimestamp is null), triggering initial folder list sync via SyncEngine.")
                                            syncEngine.syncFolders(accountId)
                                            // UI can show loading based on SyncEngine's overall status or specific worker status.
                                            // Here, we reflect the current DB state (empty) and let sync update it.
                                        }
                                        FolderFetchState.Success(folderEntities.map { it.toDomainModel() })
                                    }
                                    .catch { e ->
                                        Timber.e(
                                            e,
                                            "Error observing folders for account $accountId from DB"
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
                    Timber.e(
                        e,
                        "Error in observeDatabaseChanges flatMapLatest/combine"
                    )
                }
                .launchIn(this) // Launch in the externalScope's coroutine context
        }
    }

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> =
        _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(ioDispatcher) {
            Timber.i("manageObservedAccounts called with ${accounts.size} accounts. EmailAddresses: ${accounts.joinToString { it.emailAddress }}.")
            val currentDbAccountEntities = accountDao.getAllAccounts().first() // Get current state
            Timber.d("manageObservedAccounts: API accounts: ${accounts.joinToString { it.id + ":" + it.emailAddress }}")
            Timber.d("manageObservedAccounts: Current DB accounts before any upsert: ${currentDbAccountEntities.joinToString { it.id + ":" + it.emailAddress }}")

            val currentDbAccountIds = currentDbAccountEntities.map { it.id }.toSet()
            val newApiAccountIds = accounts.map { it.id }.toSet()

            // Accounts to remove from DB
            val removedAccountIds = currentDbAccountIds - newApiAccountIds
            removedAccountIds.forEach { accountId ->
                Timber.i("Removing account $accountId from DB and cancelling its syncs.")
                syncJobs[accountId]?.cancel(CancellationException("Account removed"))
                syncJobs.remove(accountId)
                folderDao.deleteAllFoldersForAccount(accountId) // Clears folders
                // accountDao.deleteAccount(accountId) // DefaultAccountRepository handles account deletion
                _folderStates.update { it - accountId } // Also update local state immediately
            }

            accounts.map { it.toEntity() }
            // accountDao.insertOrUpdateAccounts(accountEntitiesToUpsert) // DefaultAccountRepository handles account upserts
            // Timber.i(TAG, "Upserted ${accountEntitiesToUpsert.size} accounts into DB.")
            // Timber.i(TAG, "Upserted ${accountEntitiesToUpsert.size} accounts into DB. Account IDs: ${accountEntitiesToUpsert.joinToString { it.id }}. This will trigger AccountDao observers.")
            Timber.d("manageObservedAccounts: Account upsert/delete operations are handled by DefaultAccountRepository. This repository will now proceed to check if folder syncs are needed for the observed accounts.")

            // For new or existing accounts, trigger an initial sync if no data or upon request
            // This logic might need refinement based on "initial sync" requirements vs. regular refresh
            accounts.forEach { account ->
                val currentState = _folderStates.value[account.id]
                // Also check against AccountEntity's lastFolderListSyncTimestamp
                val accountEntity = accountDao.getAccountByIdSuspend(account.id)
                val needsSyncBasedOnDbState = currentState == null ||
                        currentState is FolderFetchState.Error ||
                        (currentState is FolderFetchState.Success && currentState.folders.isEmpty())

                val needsSyncBasedOnTimestamp = accountEntity?.lastFolderListSyncTimestamp == null

                if ((needsSyncBasedOnDbState || needsSyncBasedOnTimestamp) && !account.isLocalOnly) { // Don't sync for local-only accounts unless forced
                    Timber.d(
                        "manageObservedAccounts: Account ${account.emailAddress} needs initial folder sync. Current state: $currentState, lastFolderListSyncTimestamp: ${accountEntity?.lastFolderListSyncTimestamp}"
                    )
                    syncEngine.syncFolders(account.id) // Use SyncEngine
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

        // Remove direct API call logic
        // The original logic for checking needsReauthentication, forceRefresh, etc.,
        // can be handled by the SyncEngine/Worker or by not enqueuing if not needed.
        // For now, simply enqueue the request.
        Timber.i("Requesting folder sync for $accountId via SyncEngine - $reasonSuffix. Force refresh: $forceRefresh")
        syncEngine.syncFolders(accountId)
        // The _folderStates update to Loading should ideally happen based on observing the WorkManager job status
        // or by the SyncEngine managing this state globally.
        // For optimistic UI, one might set it here: _folderStates.update { it + (accountId to FolderFetchState.Loading) }
        // but that makes SyncEngine less of the state manager for sync progress.
    }


    override suspend fun refreshFoldersForAccount(accountId: String, activity: Activity?) {
        val accountEntity = accountDao.getAccountByIdSuspend(accountId) // Get from DB
        if (accountEntity == null) {
            Timber.w("Account $accountId not found in DB for refresh.")
            _folderStates.update { it + (accountId to FolderFetchState.Error("Account not found")) }
            return
        }
        val account = accountEntity.toDomainAccount()
        Timber.i("refreshFoldersForAccount called for ${account.emailAddress}, delegating to SyncEngine.")
        syncEngine.syncFolders(accountId) // Use SyncEngine
    }

    // refreshAllFolders needs to be adapted if it's still used.
    // It would iterate over accounts from accountDao and call refreshFoldersForAccountInternal.
    override suspend fun refreshAllFolders(activity: Activity?) {
        Timber.i("refreshAllFolders called.")
        val allAccountEntities = accountDao.getAllAccounts().first()
        if (allAccountEntities.isEmpty()) {
            Timber.i("No accounts in DB to refresh.")
            return
        }
        allAccountEntities.forEach { accountEntity ->
            val account = accountEntity.toDomainAccount()
            if (!account.isLocalOnly) { // Don't sync local-only accounts
                Timber.d("refreshAllFolders: Requesting folder sync for ${account.emailAddress} via SyncEngine.")
                syncEngine.syncFolders(account.id) // Use SyncEngine
            } else {
                Timber.i("refreshAllFolders: Skipping local-only account ${account.emailAddress}.")
            }
        }
    }

    override fun getThreadsInFolder(
        accountId: String,
        folderId: String
    ): Flow<List<MailThread>> {
        TODO("Not yet implemented")
    }

    override fun getMessagesInFolder(
        accountId: String,
        folderId: String
    ): Flow<List<Message>> {
        TODO("Not yet implemented")
    }

    override suspend fun syncFolderContents(accountId: String, folderId: String): Result<Unit> {
        Timber.d("DefaultFolderRepository: syncFolderContents for account $accountId, folder $folderId. Delegating to SyncEngine.")
        // In a full implementation, you might need the remoteFolderId.
        // For now, assume folderId is sufficient or can be looked up by SyncEngine/worker.
        val folder = folderDao.getFolderByIdSuspend(folderId)
        if (folder?.remoteId == null) {
            val errorMessage =
                "Cannot sync folder contents: remoteId is null for local folderId $folderId"
            Timber.e(errorMessage)
            return Result.failure(IllegalArgumentException(errorMessage))
        }
        syncEngine.syncFolderContent(accountId, folder.id, folder.remoteId!!)
        return Result.success(Unit)
    }

    override fun getFolderById(folderId: String): Flow<MailFolder?> {
        return folderDao.getFolderByIdFlow(folderId).map { it?.toDomainModel() }
    }

    override suspend fun getFolderByIdSuspend(folderId: String): MailFolder? {
        return folderDao.getFolderByIdSuspend(folderId)?.toDomainModel()
    }
}