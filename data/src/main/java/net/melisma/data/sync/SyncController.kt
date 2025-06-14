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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_db.entity.MessageFolderJunction
import net.melisma.core_db.entity.FolderSyncStateEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_db.model.PendingActionStatus
import net.melisma.data.sync.workers.ActionUploadWorker

@Singleton
class SyncController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val queue: PriorityBlockingQueue<SyncJob>,
    private val networkMonitor: NetworkMonitor,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mailApiServiceSelector: net.melisma.core_data.datasource.MailApiServiceSelector,
    private val appDatabase: net.melisma.core_db.AppDatabase,
    @net.melisma.core_data.di.Dispatcher(net.melisma.core_data.di.MailDispatchers.IO)
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
) {

    private val _status = MutableStateFlow(SyncControllerStatus())
    val status = _status.asStateFlow()

    private var pollingJob: kotlinx.coroutines.Job? = null

    private var passivePollingJob: kotlinx.coroutines.Job? = null

    private val ACTIVE_POLL_INTERVAL_MS = 5_000L

    private val activeAccounts = ConcurrentHashMap.newKeySet<String>()

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

            // Per-account concurrency guard: Skip if another job for the same account is already running.
            if (activeAccounts.contains(job.accountId)) {
                // Requeue and yield to avoid busy loop
                queue.put(job)
                kotlinx.coroutines.delay(100)
                continue
            }
            activeAccounts.add(job.accountId)
            _status.value = _status.value.copy(isSyncing = true, currentJob = job.toString())
            Timber.i("Processing job: $job")

            try {
                when (job) {
                    is SyncJob.SyncFolderList -> handleSyncFolderList(job)
                    is SyncJob.ForceRefreshFolder -> handleForceRefreshFolder(job)
                    is SyncJob.DownloadMessageBody -> handleDownloadMessageBody(job)
                    is SyncJob.RefreshFolderContents -> handleForceRefreshFolder(
                        SyncJob.ForceRefreshFolder(
                            accountId = job.accountId,
                            folderId = job.folderId
                        )
                    )
                    is SyncJob.UploadAction -> handleUploadAction(job)
                    is SyncJob.DownloadAttachment -> handleDownloadAttachment(job)
                    is SyncJob.FetchFullMessageBody -> handleDownloadMessageBody(
                        SyncJob.DownloadMessageBody(job.messageId, job.accountId)
                    )
                    is SyncJob.FetchMessageHeaders -> handleFetchMessageHeaders(job)
                    is SyncJob.FetchNextMessageListPage -> handleFetchNextPage(job)
                    is SyncJob.SearchOnline -> Timber.w("No-op handler for SearchOnline")
                    is SyncJob.EvictFromCache -> runCacheEviction(job.accountId)
                }
                _status.value = _status.value.copy(error = null)
            } catch (e: Exception) {
                Timber.e(e, "Error processing job: $job")
                _status.value = _status.value.copy(error = e.message)
            } finally {
                activeAccounts.remove(job.accountId)
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
                FolderSyncStateEntity(
                    folderId = folderId,
                    nextPageToken = paged.nextPageToken,
                    lastSyncedTimestamp = System.currentTimeMillis()
                )
            )
        }

        if (paged.nextPageToken != null) {
            submit(SyncJob.FetchMessageHeaders(folderId, paged.nextPageToken, accountId))
        }
    }

    private suspend fun handleForceRefreshFolder(job: SyncJob.ForceRefreshFolder) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            // Abort early if offline
            if (!networkMonitor.isOnline.first()) {
                Timber.i("Offline – skipping ForceRefreshFolder for ${job.folderId}")
                return@withContext
            }

            val folderDao = appDatabase.folderDao()
            val messageDao = appDatabase.messageDao()
            val junctionDao = appDatabase.messageFolderJunctionDao()

            val localFolder = folderDao.getFolderByIdSuspend(job.folderId) ?: run {
                Timber.w("Folder ${job.folderId} not found; dropping ForceRefreshFolder job")
                return@withContext
            }

            val apiFolderId = localFolder.remoteId ?: job.folderId

            folderDao.updateSyncStatus(job.folderId, EntitySyncStatus.PENDING_DOWNLOAD)

            val service = mailApiServiceSelector.getServiceByAccountId(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            val response = service.getMessagesForFolder(
                folderId = apiFolderId,
                maxResults = 50,
                pageToken = null
            )

            if (response.isFailure) {
                val ex = response.exceptionOrNull()
                Timber.e(ex, "ForceRefreshFolder failed for ${job.folderId}")
                folderDao.updateSyncStatusAndError(job.folderId, EntitySyncStatus.ERROR, ex?.message)
                return@withContext
            }

            val paged = response.getOrThrow()
            val msgEntities = paged.messages.map { it.toEntity(job.accountId) }

            appDatabase.withTransaction {
                messageDao.deleteMessagesByFolder(job.folderId)
                junctionDao.deleteByFolder(job.folderId)

                messageDao.insertOrUpdateMessages(msgEntities)
                val junctions = msgEntities.map { me -> MessageFolderJunction(me.id, job.folderId) }
                junctionDao.insertAll(junctions)

                folderDao.updateLastSuccessfulSync(job.folderId, System.currentTimeMillis())

                appDatabase.folderSyncStateDao().upsert(
                    FolderSyncStateEntity(
                        folderId = job.folderId,
                        nextPageToken = paged.nextPageToken,
                        lastSyncedTimestamp = System.currentTimeMillis()
                    )
                )
            }

            // Queue next page if exists
            if (paged.nextPageToken != null) {
                submit(SyncJob.FetchMessageHeaders(job.folderId, paged.nextPageToken, job.accountId))
            }
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

    private suspend fun handleSyncFolderList(job: SyncJob.SyncFolderList) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.i("Offline – skipping folder list sync for ${job.accountId}")
                return@withContext
            }

            val accountDao = appDatabase.accountDao()
            val folderDao = appDatabase.folderDao()

            val service = mailApiServiceSelector.getServiceByAccountId(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            val syncResult = service.syncFolders(job.accountId, null)

            if (syncResult.isFailure) {
                val ex = syncResult.exceptionOrNull()
                Timber.e(ex, "Folder list sync failed for ${job.accountId}")
                accountDao.updateFolderListSyncError(job.accountId, ex?.message ?: "Unknown error")
                if (ex is net.melisma.core_data.errors.ApiServiceException && ex.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(job.accountId, true)
                }
                return@withContext
            }

            val delta = syncResult.getOrThrow()
            val serverFolders = delta.newOrUpdatedItems

            appDatabase.withTransaction {
                val localFolders = folderDao.getFoldersForAccount(job.accountId).first()
                val localFolderMap = localFolders.associateBy { it.remoteId }
                val serverFolderMap = serverFolders.associateBy { it.id }

                // Delete local folders no longer on server
                val toDelete = localFolders.filter { it.remoteId !in serverFolderMap.keys }
                if (toDelete.isNotEmpty()) {
                    folderDao.deleteFolders(toDelete)
                }

                // Upsert server folders
                val toUpsert = serverFolders.map { serverFolder ->
                    val existing = localFolderMap[serverFolder.id]
                    val localId = existing?.id ?: java.util.UUID.randomUUID().toString()
                    serverFolder.toEntity(job.accountId, localId)
                }
                if (toUpsert.isNotEmpty()) {
                    folderDao.insertOrUpdateFolders(toUpsert)
                }
            }

            accountDao.updateFolderListSyncSuccess(job.accountId, System.currentTimeMillis())
            accountDao.updateFolderListSyncToken(job.accountId, delta.nextSyncToken)
            Timber.d("Folder list sync completed for ${job.accountId}")
        }
    }

    private suspend fun handleDownloadMessageBody(job: SyncJob.DownloadMessageBody) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.i("Offline – skip body download for ${job.messageId}")
                return@withContext
            }

            val messageDao = appDatabase.messageDao()
            val bodyDao = appDatabase.messageBodyDao()
            val accountDao = appDatabase.accountDao()

            messageDao.setSyncStatus(job.messageId, EntitySyncStatus.PENDING_DOWNLOAD)

            val service = mailApiServiceSelector.getServiceByAccountId(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            val result = service.getMessageContent(job.messageId)

            if (result.isFailure) {
                val ex = result.exceptionOrNull()
                messageDao.setSyncError(job.messageId, ex?.message ?: "Unknown error")
                if (ex is net.melisma.core_data.errors.ApiServiceException && ex.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(job.accountId, true)
                }
                return@withContext
            }

            val msg = result.getOrThrow()
            val bodyContent = msg.body
            val contentType = msg.bodyContentType ?: "TEXT"
            val sizeBytes = bodyContent?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

            bodyDao.insertOrUpdateMessageBody(
                net.melisma.core_db.entity.MessageBodyEntity(
                    messageId = job.messageId,
                    content = bodyContent,
                    contentType = contentType,
                    sizeInBytes = sizeBytes,
                    lastFetchedTimestamp = System.currentTimeMillis()
                )
            )
            if (bodyContent != null) {
                messageDao.updateMessageBody(job.messageId, bodyContent)
            }
            messageDao.setSyncStatus(job.messageId, EntitySyncStatus.SYNCED)
            Timber.d("Downloaded body for ${job.messageId}")
        }
    }

    private suspend fun handleDownloadAttachment(job: SyncJob.DownloadAttachment) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.i("Offline – skip attachment download ${job.attachmentId}")
                return@withContext
            }

            val attachmentDao = appDatabase.attachmentDao()
            val accountDao = appDatabase.accountDao()

            attachmentDao.updateSyncStatus(job.attachmentId, EntitySyncStatus.PENDING_DOWNLOAD, null)

            val attachment = attachmentDao.getAttachmentByIdSuspend(job.attachmentId) ?: run {
                Timber.w("Attachment ${job.attachmentId} not found in DB")
                return@withContext
            }

            val service = mailApiServiceSelector.getServiceByAccountId(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            val result = service.downloadAttachment(job.messageId, job.attachmentId)

            if (result.isFailure) {
                val ex = result.exceptionOrNull()
                attachmentDao.updateSyncStatus(job.attachmentId, EntitySyncStatus.ERROR, ex?.message)
                if (ex is net.melisma.core_data.errors.ApiServiceException && ex.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(job.accountId, true)
                }
                return@withContext
            }

            val data = result.getOrThrow()
            val dir = java.io.File(appContext.filesDir, "attachments/${job.messageId}")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, attachment.fileName)
            file.outputStream().use { it.write(data) }

            attachmentDao.updateDownloadSuccess(job.attachmentId, file.absolutePath, System.currentTimeMillis())
            Timber.d("Downloaded attachment ${job.attachmentId} to ${file.absolutePath}")
        }
    }

    private suspend fun handleUploadAction(job: SyncJob.UploadAction) {
        withContext(ioDispatcher) {
            val pendingActionDao = appDatabase.pendingActionDao()
            val messageDao = appDatabase.messageDao()
            val attachmentDao = appDatabase.attachmentDao()
            val action = pendingActionDao.getNextActionForAccount(job.accountId)
            if (action == null) {
                Timber.d("No pending actions for account ${job.accountId}")
                return@withContext
            }

            val service = mailApiServiceSelector.getServiceByAccountId(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            // Helper to update retry / failed state
            suspend fun markFailure(reason: String?) {
                val updated = action.copy(
                    status = if (action.attemptCount + 1 >= action.maxAttempts) PendingActionStatus.FAILED else PendingActionStatus.RETRY,
                    lastAttemptAt = System.currentTimeMillis(),
                    lastError = reason,
                    attemptCount = action.attemptCount + 1
                )
                pendingActionDao.updateAction(updated)
            }

            try {
                val resultSuccessful: Boolean = when (action.actionType) {
                    ActionUploadWorker.ACTION_MARK_AS_READ -> {
                        service.markMessageRead(action.entityId, true).isSuccess
                    }

                    ActionUploadWorker.ACTION_MARK_AS_UNREAD -> {
                        service.markMessageRead(action.entityId, false).isSuccess
                    }

                    ActionUploadWorker.ACTION_STAR_MESSAGE -> {
                        val isStarred = action.payload[ActionUploadWorker.KEY_IS_STARRED]?.toBoolean() ?: true
                        service.starMessage(action.entityId, isStarred).isSuccess
                    }

                    ActionUploadWorker.ACTION_DELETE_MESSAGE -> {
                        service.deleteMessage(action.entityId).isSuccess.also { ok ->
                            if (ok) messageDao.deleteMessageById(action.entityId)
                        }
                    }

                    ActionUploadWorker.ACTION_MOVE_MESSAGE -> {
                        val oldFolder = action.payload[ActionUploadWorker.KEY_OLD_FOLDER_ID]
                        val newFolder = action.payload[ActionUploadWorker.KEY_NEW_FOLDER_ID]
                        if (oldFolder == null || newFolder == null) false else service.moveMessage(action.entityId, oldFolder, newFolder).isSuccess
                    }

                    ActionUploadWorker.ACTION_MARK_THREAD_AS_READ -> {
                        service.markThreadRead(action.entityId, true).isSuccess
                    }
                    ActionUploadWorker.ACTION_MARK_THREAD_AS_UNREAD -> {
                        service.markThreadRead(action.entityId, false).isSuccess
                    }
                    ActionUploadWorker.ACTION_DELETE_THREAD -> {
                        service.deleteThread(action.entityId).isSuccess
                    }
                    ActionUploadWorker.ACTION_MOVE_THREAD -> {
                        val oldFolder = action.payload[ActionUploadWorker.KEY_OLD_FOLDER_ID]
                        val newFolder = action.payload[ActionUploadWorker.KEY_NEW_FOLDER_ID]
                        if (oldFolder == null || newFolder == null) false else service.moveThread(action.entityId, oldFolder, newFolder).isSuccess
                    }

                    ActionUploadWorker.ACTION_SEND_MESSAGE -> {
                        val draftJson = action.payload[ActionUploadWorker.KEY_DRAFT_DETAILS]
                        val draft = draftJson?.let { Json.decodeFromString<MessageDraft>(it) }
                        if (draft == null) {
                            false
                        } else {
                            val res = service.sendMessage(draft)
                            if (res.isSuccess) {
                                // On success remove local outbox flag, update messageId remoteId if provided
                                val remoteId = res.getOrNull()
                                if (remoteId != null) {
                                    val localMsg = messageDao.getMessageByIdSuspend(action.entityId)
                                    if (localMsg != null) {
                                        localMsg.messageId = remoteId
                                        localMsg.syncStatus = EntitySyncStatus.SYNCED
                                        messageDao.insertOrUpdateMessages(listOf(localMsg))
                                    }
                                }
                            }
                            res.isSuccess
                        }
                    }

                    ActionUploadWorker.ACTION_CREATE_DRAFT -> {
                        val draftJson = action.payload[ActionUploadWorker.KEY_DRAFT_DETAILS]
                        val draft = draftJson?.let { Json.decodeFromString<MessageDraft>(it) }
                        if (draft == null) false else service.createDraftMessage(draft).isSuccess
                    }

                    ActionUploadWorker.ACTION_UPDATE_DRAFT -> {
                        val draftJson = action.payload[ActionUploadWorker.KEY_DRAFT_DETAILS]
                        val draft = draftJson?.let { Json.decodeFromString<MessageDraft>(it) }
                        if (draft == null) false else service.updateDraftMessage(action.entityId, draft).isSuccess
                    }

                    else -> {
                        Timber.w("Unknown action type ${action.actionType}. Deleting action.")
                        true // Treat as success to avoid infinite loop
                    }
                }

                if (resultSuccessful) {
                    pendingActionDao.deleteActionById(action.id)
                    Timber.i("Successfully processed pending action ${action.id} (${action.actionType})")
                } else {
                    Timber.w("Processing of pending action ${action.id} failed – will retry later")
                    markFailure("API error or invalid parameters")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error uploading action ${action.id} (${action.actionType})")
                markFailure(e.message)
            }

            // If more actions remain, enqueue another UploadAction job for this account
            val another = pendingActionDao.getNextActionForAccount(job.accountId)
            if (another != null) {
                submit(SyncJob.UploadAction(job.accountId, another.actionType, another.entityId, another.payload))
            }
        }
    }

    private suspend fun runCacheEviction(accountId: String) {
        withContext(ioDispatcher) {
            Timber.d("[Stub] Cache eviction requested for account $accountId — full algorithm TBD.")
            // We simply log for now.
        }
    }

    // Passive background polling coroutine
    fun startPassivePolling() {
        if (passivePollingJob?.isActive == true) return
        passivePollingJob = externalScope.launch {
            while (isActive) {
                delay(java.util.concurrent.TimeUnit.MINUTES.toMillis(15))
                // For each account's Inbox submit FetchMessageHeaders
                val accountDao = appDatabase.accountDao()
                val folderDao = appDatabase.folderDao()
                val accounts = accountDao.getAllAccounts().first()
                accounts.forEach { acc ->
                    val inboxFolder = folderDao.getFolderByWellKnownTypeSuspend(acc.id, net.melisma.core_data.model.WellKnownFolderType.INBOX)
                    inboxFolder?.let { folder ->
                        submit(
                            SyncJob.FetchMessageHeaders(
                                folderId = folder.id,
                                pageToken = null,
                                accountId = acc.id
                            )
                        )
                    }
                }
            }
        }
    }

    fun stopPassivePolling() {
        passivePollingJob?.cancel()
        passivePollingJob = null
    }
} 