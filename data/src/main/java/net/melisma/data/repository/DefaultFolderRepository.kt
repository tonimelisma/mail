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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
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
import net.melisma.data.sync.SyncController
import net.melisma.core_data.model.SyncJob
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultFolderRepository @Inject constructor(
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val accountDao: AccountDao,
    private val folderDao: FolderDao,
    private val syncController: SyncController
) : FolderRepository {

    private val _folderStates = MutableStateFlow<Map<String, FolderFetchState>>(emptyMap())

    init {
        Timber.d("Initializing DefaultFolderRepository with DB support.")
        observeDatabaseChanges()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDatabaseChanges() {
        externalScope.launch(ioDispatcher) {
            accountDao.getAllAccounts()
                .flatMapLatest { accountEntities ->
                    if (accountEntities.isEmpty()) {
                        flow { emit(emptyMap<String, FolderFetchState>()) }
                    } else {
                        val accountFolderFlows = accountEntities.map { accountEntity ->
                            val accountId = accountEntity.id
                            folderDao.getFoldersForAccount(accountId)
                                .map<List<net.melisma.core_db.entity.FolderEntity>, FolderFetchState> { folderEntities ->
                                    if (folderEntities.isEmpty() && !accountEntity.isLocalOnly && accountEntity.lastFolderListSyncTimestamp == null) {
                                        Timber.d("No folders in DB for ${accountEntity.emailAddress}, triggering initial folder list sync.")
                                        syncController.submit(SyncJob.SyncFolderList(accountId))
                                    }
                                    FolderFetchState.Success(folderEntities.map { it.toDomainModel() })
                                }
                                .catch { e ->
                                    Timber.e(e, "Error observing folders for account $accountId from DB")
                                    emit(FolderFetchState.Error("DB error: ${e.localizedMessage}"))
                                }
                                .map { state -> accountId to state }
                        }
                        combine(accountFolderFlows) { statesArray ->
                            @Suppress("UNCHECKED_CAST")
                            (statesArray as Array<*>).filterIsInstance<Pair<String, FolderFetchState>>().toMap()
                        }
                    }
                }
                .distinctUntilChanged()
                .onEach { latestDbStates ->
                    _folderStates.value = latestDbStates
                }
                .catch { e -> Timber.e(e, "Error in observeDatabaseChanges") }
                .launchIn(this)
        }
    }

    override fun observeFoldersState(): Flow<Map<String, FolderFetchState>> = _folderStates.asStateFlow()

    override suspend fun manageObservedAccounts(accounts: List<Account>) {
        withContext(ioDispatcher) {
            Timber.i("Managing observed accounts: ${accounts.joinToString { it.emailAddress }}.")
            val currentDbAccountIds = accountDao.getAllAccounts().first().map { it.id }.toSet()
            val newApiAccountIds = accounts.map { it.id }.toSet()

            val removedAccountIds = currentDbAccountIds - newApiAccountIds
            if (removedAccountIds.isNotEmpty()) {
                Timber.i("Removing accounts: $removedAccountIds")
                folderDao.deleteAllFoldersForAccounts(removedAccountIds.toList())
                _folderStates.update { currentMap -> currentMap - removedAccountIds }
            }

            accounts.forEach { account ->
                val accountEntity = accountDao.getAccountByIdSuspend(account.id)
                val needsSync = accountEntity?.lastFolderListSyncTimestamp == null
                if (needsSync && !account.isLocalOnly) {
                    Timber.d("Account ${account.emailAddress} needs initial folder sync.")
                    syncController.submit(SyncJob.SyncFolderList(account.id))
                }
            }
        }
    }

    override suspend fun refreshFoldersForAccount(accountId: String, activity: Activity?) {
        val accountEntity = accountDao.getAccountByIdSuspend(accountId)
        if (accountEntity == null) {
            Timber.w("Account $accountId not found in DB for refresh.")
            _folderStates.update { it + (accountId to FolderFetchState.Error("Account not found")) }
            return
        }
        Timber.i("Refreshing folders for ${accountEntity.emailAddress}, delegating to SyncController.")
        syncController.submit(SyncJob.SyncFolderList(accountId))
    }

    override suspend fun refreshAllFolders(activity: Activity?) {
        Timber.i("Refreshing all folders.")
        val allAccountEntities = accountDao.getAllAccounts().first()
        allAccountEntities.forEach { accountEntity ->
            if (!accountEntity.isLocalOnly) {
                Timber.d("Requesting folder sync for ${accountEntity.emailAddress} via SyncController.")
                syncController.submit(SyncJob.SyncFolderList(accountEntity.id))
            }
        }
    }

    override suspend fun syncFolderContents(accountId: String, folderId: String): Result<Unit> {
        return try {
            syncController.submit(SyncJob.ForceRefreshFolder(folderId, accountId))
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to submit folder content sync job for folderId: $folderId")
            Result.failure(e)
        }
    }

    override fun getThreadsInFolder(accountId: String, folderId: String): Flow<List<MailThread>> {
        TODO("Not yet implemented")
    }

    override fun getMessagesInFolder(accountId: String, folderId: String): Flow<List<Message>> {
        TODO("Not yet implemented")
    }

    override fun getFolderById(folderId: String): Flow<MailFolder?> {
        return flow {
            emit(folderDao.getFolderByIdSuspend(folderId)?.toDomainModel())
        }.catch { e ->
            Timber.e(e, "Error getting folder by ID $folderId")
            emit(null)
        }
    }

    override suspend fun getFolderByIdSuspend(folderId: String): MailFolder? {
        return folderDao.getFolderByIdSuspend(folderId)?.toDomainModel()
    }

    override suspend fun getLocalFolderUuidByRemoteId(
        accountId: String,
        remoteFolderId: String
    ): String? = withContext(ioDispatcher) {
        folderDao.getFolderByAccountIdAndRemoteId(accountId, remoteFolderId)?.id
    }
} 