package net.melisma.data.repository

import android.app.Activity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    private val folderDao: FolderDao
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
            Timber.i("manageObservedAccounts called with ${accounts.size} accounts. Usernames: ${accounts.joinToString { it.username }}.")
            val currentDbAccountEntities = accountDao.getAllAccounts().first() // Get current state
            Timber.d("manageObservedAccounts: API accounts: ${accounts.joinToString { it.id + ":" + it.username }}")
            Timber.d("manageObservedAccounts: Current DB accounts before any upsert: ${currentDbAccountEntities.joinToString { it.id + ":" + it.username }}")

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
            // Timber.i("Upserted ${accountEntitiesToUpsert.size} accounts into DB.")
            // Timber.i(TAG, "Upserted ${accountEntitiesToUpsert.size} accounts into DB. Account IDs: ${accountEntitiesToUpsert.joinToString { it.id }}. This will trigger AccountDao observers.")
            Timber.d("manageObservedAccounts: Account upsert/delete operations are handled by DefaultAccountRepository. This repository will now proceed to check if folder syncs are needed for the observed accounts.")

            // For new or existing accounts, trigger an initial sync if no data or upon request
            // This logic might need refinement based on "initial sync" requirements vs. regular refresh
            accounts.forEach { account ->
                val currentState = _folderStates.value[account.id]
                // If no folders yet from DB ( Success(emptyList) ) or an error state, try to sync.
                val needsSync = currentState == null ||
                        currentState is FolderFetchState.Error ||
                        (currentState is FolderFetchState.Success && currentState.folders.isEmpty())

                if (needsSync) {
                    Timber.d(
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

        // Launch as a new job within the externalScope to manage its lifecycle via syncJobs
        // and allow cancellation if the account is removed or another sync is explicitly started.
        syncJobs[accountId]?.cancel(CancellationException("New sync requested for $accountId by $reasonSuffix"))
        syncJobs[accountId] = externalScope.launch(ioDispatcher) {
            // Check needsReauthentication flag from DB first
            val accountEntity = accountDao.getAccountById(accountId).first()
            if (accountEntity?.needsReauthentication == true) {
                Timber.w(
                    "Account $accountId marked for re-authentication. Skipping folder sync for $reasonSuffix."
                )
                _folderStates.update { currentStates ->
                    currentStates + (accountId to FolderFetchState.Error(
                        "Account requires re-authentication.",
                        needsReauth = true
                    ))
                }
                return@launch // Do not proceed with sync
            }

            if (!forceRefresh && _folderStates.value[accountId] is FolderFetchState.Success && (_folderStates.value[accountId] as FolderFetchState.Success).folders.isNotEmpty()) {
                Timber.i(
                    "Folders for account $accountId already loaded and forceRefresh is false. Skipping sync for $reasonSuffix."
                )
                // Ensure the job is removed if we're not actually running it to completion
                // syncJobs.remove(accountId) // Or let it complete naturally if no more code below
                return@launch
            }


            Timber.i(
                "Starting folder sync for $accountId ($providerType) - $reasonSuffix. Force refresh: $forceRefresh. Current coroutine active: $isActive"
            )
            _folderStates.update { currentStates ->
                currentStates + (accountId to FolderFetchState.Loading)
            }

            try {
                ensureActive() // Check if the coroutine was cancelled before network request

                val service = mailApiServices[providerType]
                val errorMapper = errorMappers[providerType]

                if (service == null || errorMapper == null) {
                    Timber.e("No MailApiService or ErrorMapper for provider: $providerType")
                    _folderStates.update { currentStates ->
                        currentStates + (accountId to FolderFetchState.Error("Unsupported account type: $providerType"))
                    }
                    return@launch
                }

                val remoteFoldersResult = service.getMailFolders(activity, accountId)
                ensureActive() // Check cancellation after network request

                remoteFoldersResult.fold(
                    onSuccess = { folderList ->
                        Timber.i(
                            "Successfully fetched ${folderList.size} folders for $accountId from API."
                        )
                        val folderEntities = folderList.map { it.toEntity(accountId) }
                        // Replace folders by deleting all existing for the account then inserting new ones
                        folderDao.deleteAllFoldersForAccount(accountId)
                        folderDao.insertOrUpdateFolders(folderEntities)
                        // The DB observation should update _folderStates to Success
                        // However, explicitly setting it here might provide faster UI feedback
                        // if DB observation is slow, but can lead to race conditions.
                        // For now, rely on DB observation triggered by replaceFoldersForAccount.
                        // If an explicit update is needed:
                        // _folderStates.update { currentStates ->
                        //    currentStates + (accountId to FolderFetchState.Success(folderList))
                        // }
                        Timber.d(
                            "Folders for $accountId saved to DB. State will update via DB observation."
                        )
                    },
                    onFailure = { exception ->
                        val errorDetails = errorMapper.mapExceptionToErrorDetails(exception)
                        Timber.e(
                            exception,
                            "Error syncing folders for $accountId: ${errorDetails.message}"
                        )

                        // Check if the exception is NeedsReauthenticationException specifically
                        // The TokenProvider should have already marked the account for re-auth.
                        val needsReauth =
                            exception is net.melisma.core_data.auth.NeedsReauthenticationException ||
                                    (exception.cause is net.melisma.core_data.auth.NeedsReauthenticationException)

                        _folderStates.update { currentStates ->
                            currentStates + (accountId to FolderFetchState.Error(
                                errorDetails.message,
                                needsReauth = needsReauth
                            ))
                        }
                    }
                )
            } catch (e: CancellationException) {
                Timber.i("Folder sync for $accountId cancelled ($reasonSuffix): ${e.message}")
                // Don't update state to error if it's a legitimate cancellation
                // (e.g. account removed, new sync started)
                // The state will be handled by the cancelling action or new sync.
            } catch (e: Exception) { // Catch-all for other unexpected errors
                Timber.e(
                    e,
                    "Unexpected exception during folder sync for $accountId ($reasonSuffix)"
                )
                val errorDetails = errorMappers[providerType]?.mapExceptionToErrorDetails(e)
                    ?: net.melisma.core_data.model.ErrorDetails("Unknown error during folder sync.")
                _folderStates.update { currentStates ->
                    currentStates + (accountId to FolderFetchState.Error(errorDetails.message))
                }
            } finally {
                // Only remove the job if this instance of the coroutine is still the one in the map.
                // This prevents a newer job from being incorrectly removed if this one was slow
                // and got superseded.
                if (syncJobs[accountId] == coroutineContext[Job]) {
                    syncJobs.remove(accountId)
                }
                Timber.d(
                    "Folder sync job ended for $accountId. Reason: $reasonSuffix. Remaining jobs: ${syncJobs.size}"
                )
            }
        }
    }


    override suspend fun refreshFoldersForAccount(accountId: String, activity: Activity?) {
        val accountEntity = accountDao.getAccountById(accountId).first() // Get from DB
        if (accountEntity == null) {
            Timber.w("Account $accountId not found in DB for refresh.")
            _folderStates.update { it + (accountId to FolderFetchState.Error("Account not found")) }
            return
        }
        val account = accountEntity.toDomainAccount()
        Timber.i("refreshFoldersForAccount called for ${account.username}")
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
        Timber.i("refreshAllFolders called.")
        val allAccountEntities = accountDao.getAllAccounts().first()
        if (allAccountEntities.isEmpty()) {
            Timber.i("No accounts in DB to refresh.")
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
                Timber.w(
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
        Timber.d(
            "syncFolderContents called for accountId: $accountId, folderId: $folderId (NOT IMPLEMENTED WITH DB)"
        )
        return Result.failure(NotImplementedError("syncFolderContents not implemented with DB"))
    }

    override fun getThreadsInFolder(accountId: String, folderId: String): Flow<List<MailThread>> {
        Timber.d(
            "getThreadsInFolder called for accountId: $accountId, folderId: $folderId (NOT IMPLEMENTED WITH DB)"
        )
        return flowOf(emptyList())
    }

    override fun getMessagesInFolder(accountId: String, folderId: String): Flow<List<Message>> {
        Timber.d(
            "getMessagesInFolder called for accountId: $accountId, folderId: $folderId (NOT IMPLEMENTED WITH DB)"
        )
        return flowOf(emptyList())
    }
}