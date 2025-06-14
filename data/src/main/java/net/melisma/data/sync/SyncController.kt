package net.melisma.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.model.SyncJob
import net.melisma.core_data.model.SyncControllerStatus
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.data.sync.work.SyncWorkManager
import timber.log.Timber
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import androidx.room.withTransaction
import net.melisma.data.mapper.toEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

@Singleton
class SyncController @Inject constructor(
    @ApplicationScope private val externalScope: CoroutineScope,
    private val queue: PriorityBlockingQueue<SyncJob>,
    private val networkMonitor: NetworkMonitor,
    private val syncWorkManager: SyncWorkManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mailApiServiceSelector: net.melisma.core_data.datasource.MailApiServiceSelector,
    private val appDatabase: net.melisma.core_db.AppDatabase,
    @net.melisma.core_data.di.Dispatcher(net.melisma.core_data.di.MailDispatchers.IO)
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
) {

    private val _status = MutableStateFlow(SyncControllerStatus())
    val status = _status.asStateFlow()

    private var pollingJob: kotlinx.coroutines.Job? = null

    private val ACTIVE_POLL_INTERVAL_MS = 5_000L

    init {
        externalScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                _status.value = _status.value.copy(networkAvailable = isOnline)
            }
        }

        // Observe user preference for initial sync duration
        externalScope.launch {
            userPreferencesRepository.userPreferencesFlow.collect { prefs ->
                _status.value = _status.value.copy(initialSyncDurationDays = prefs.initialSyncDurationDays)
                initialSyncDurationDays = prefs.initialSyncDurationDays
            }
        }
        externalScope.launch { run() }
        Timber.d("SyncController initialized and running.")
    }

    fun submit(job: SyncJob) {
        if (queue.contains(job)) {
            Timber.d("Ignoring duplicate job: $job")
            return
        }
        Timber.d("Submitting new job: $job")
        queue.put(job)
    }

    private suspend fun run() {
        while (true) {
            val job = queue.take()
            _status.value = _status.value.copy(isSyncing = true, currentJob = job.toString())
            Timber.i("Processing job: $job")

            try {
                when (job) {
                    is SyncJob.SyncFolderList -> syncWorkManager.enqueueFolderSync(job.accountId)
                    is SyncJob.ForceRefreshFolder -> syncWorkManager.enqueueFolderContentSync(job.accountId, job.folderId, true)
                    is SyncJob.DownloadMessageBody -> syncWorkManager.enqueueMessageBodyDownload(job.accountId, job.messageId)
                    is SyncJob.RefreshFolderContents -> syncWorkManager.enqueueFolderContentSync(job.accountId, job.folderId, true)
                    is SyncJob.UploadAction -> syncWorkManager.enqueueActionUpload(
                        accountId = job.accountId,
                        actionType = job.actionType,
                        entityId = job.entityId,
                        payload = job.payload
                    )
                    is SyncJob.DownloadAttachment -> syncWorkManager.enqueueAttachmentDownload(
                        accountId = job.accountId,
                        messageId = job.messageId,
                        attachmentId = job.attachmentId
                    )
                    is SyncJob.FetchFullMessageBody -> syncWorkManager.enqueueMessageBodyDownload(job.accountId, job.messageId)
                    is SyncJob.FetchMessageHeaders -> handleFetchMessageHeaders(job)
                    is SyncJob.FetchNextMessageListPage -> handleFetchNextPage(job)
                    is SyncJob.SearchOnline -> Timber.w("No-op handler for SearchOnline")
                    is SyncJob.EvictFromCache -> Timber.w("No-op handler for EvictFromCache")
                }
                _status.value = _status.value.copy(error = null)
            } catch (e: Exception) {
                Timber.e(e, "Error processing job: $job")
                _status.value = _status.value.copy(error = e.message)
            } finally {
                _status.value = _status.value.copy(isSyncing = false, currentJob = null)
            }
        }
    }

    /**
     * Stores latest preference so workers or controller logic can consult.
     * Currently unused but prepared for Phase-1 integration task.
     */
    @Volatile
    var initialSyncDurationDays: Long = 90L

    private suspend fun handleFetchMessageHeaders(job: SyncJob.FetchMessageHeaders) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            processFetchHeaders(job.folderId, job.pageToken, job.accountId)
        }
    }

    private suspend fun handleFetchNextPage(job: SyncJob.FetchNextMessageListPage) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            val stateDao = appDatabase.folderSyncStateDao()
            val nextToken = stateDao.observeState(job.folderId).firstOrNull()?.nextPageToken
            if (nextToken != null) {
                processFetchHeaders(job.folderId, nextToken, job.accountId)
            } else {
                Timber.d("No nextPageToken for folder ${job.folderId}. Nothing to fetch.")
            }
        }
    }

    private suspend fun processFetchHeaders(folderId: String, pageToken: String?, accountId: String) {
        val service = mailApiServiceSelector.getServiceByAccountId(accountId) ?: run {
            Timber.w("MailApiService not found for account $accountId")
            return
        }

        // Resolve remote folder id
        val folderDao = appDatabase.folderDao()
        val localFolder = folderDao.getFolderByIdSuspend(folderId) ?: run {
            Timber.w("Local folder $folderId not found")
            return
        }
        val apiFolderId = localFolder.remoteId ?: folderId

        val response = service.getMessagesForFolder(
            folderId = apiFolderId,
            maxResults = 50,
            pageToken = pageToken
        )

        if (response.isFailure) {
            Timber.e(response.exceptionOrNull(), "Failed to fetch headers for $folderId")
            return
        }

        val paged = response.getOrThrow()
        val messageEntities = paged.messages.map { it.toEntity(accountId) }

        appDatabase.withTransaction {
            appDatabase.messageDao().insertOrUpdateMessages(messageEntities)
            val junctionDao = appDatabase.messageFolderJunctionDao()
            val junctions = messageEntities.map { me ->
                net.melisma.core_db.entity.MessageFolderJunction(me.id, folderId)
            }
            junctionDao.insertAll(junctions)

            // Persist state
            appDatabase.folderSyncStateDao().upsert(
                net.melisma.core_db.entity.FolderSyncStateEntity(
                    folderId,
                    paged.nextPageToken,
                    System.currentTimeMillis()
                )
            )
        }

        if (paged.nextPageToken != null) {
            submit(SyncJob.FetchMessageHeaders(folderId, paged.nextPageToken, accountId))
        }
    }

    /**
     * Starts aggressive 5-second foreground polling that queues low-priority freshness jobs
     * for critical folders (currently Inbox for every account).
     * If already active, subsequent calls are ignored.
     */
    fun startActivePolling() {
        if (pollingJob?.isActive == true) {
            return
        }
        Timber.d("SyncController: Starting active polling interval of $ACTIVE_POLL_INTERVAL_MS ms")
        pollingJob = externalScope.launch(ioDispatcher) {
            while (isActive) {
                try {
                    // Skip if device is offline
                    if (!networkMonitor.isOnline.first()) {
                        kotlinx.coroutines.delay(ACTIVE_POLL_INTERVAL_MS)
                        continue
                    }

                    val accounts = appDatabase.accountDao().getAllAccounts().firstOrNull() ?: emptyList()
                    accounts.forEach { accountEntity ->
                        val inboxFolder = appDatabase.folderDao()
                            .getFolderByWellKnownTypeSuspend(
                                accountEntity.id,
                                net.melisma.core_data.model.WellKnownFolderType.INBOX
                            )
                        if (inboxFolder != null) {
                            submit(
                                net.melisma.core_data.model.SyncJob.FetchMessageHeaders(
                                    folderId = inboxFolder.id,
                                    pageToken = null,
                                    accountId = accountEntity.id
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during active polling tick")
                }
                kotlinx.coroutines.delay(ACTIVE_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the foreground polling ticker, if running. */
    fun stopActivePolling() {
        if (pollingJob?.isActive == true) {
            Timber.d("SyncController: Stopping active polling")
            pollingJob?.cancel()
        }
        pollingJob = null
    }
} 