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
import net.melisma.data.sync.SyncConstants
import androidx.core.content.ContextCompat
import android.content.Intent
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.datasource.MailApiServiceFactory
import kotlinx.coroutines.flow.update
import net.melisma.core_data.model.WellKnownFolderType

@Singleton
class SyncController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val queue: PriorityBlockingQueue<SyncJob>,
    private val networkMonitor: NetworkMonitor,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mailApiServiceFactory: MailApiServiceFactory,
    private val appDatabase: net.melisma.core_db.AppDatabase,
    @net.melisma.core_data.di.Dispatcher(net.melisma.core_data.di.MailDispatchers.IO)
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val accountRepository: AccountRepository
) {

    private val _status = MutableStateFlow(SyncControllerStatus())
    val status = _status.asStateFlow()

    private var pollingJob: kotlinx.coroutines.Job? = null

    private var passivePollingJob: kotlinx.coroutines.Job? = null

    private val ACTIVE_POLL_INTERVAL_MS = 5_000L

    private val PASSIVE_POLL_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

    private val activeAccounts = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val BASE_RETRY_DELAY_MS = 10_000L // 10 seconds
        private const val MAX_RETRY_JITTER_MS = 2_000L // 2 seconds
    }

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
                    is SyncJob.SearchOnline -> handleSearchOnline(job)
                    is SyncJob.EvictFromCache -> runCacheEviction(job.accountId)
                    is SyncJob.CheckForNewMail -> handleCheckForNewMail(job)
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
        val service = mailApiServiceFactory.getService(accountId) ?: run {
            Timber.w("MailApiService not found for account $accountId")
            return
        }

        // Determine if this is the very first sync for the folder (no state row yet & first page)
        val stateDao = appDatabase.folderSyncStateDao()
        val hasSyncedBefore = stateDao.observeState(folderId).firstOrNull() != null
        val earliestTimestamp: Long? = if (!hasSyncedBefore && pageToken == null) {
            computeEarliestTimestamp()
        } else {
            null
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
            pageToken = pageToken,
            earliestTimestampEpochMillis = earliestTimestamp
        )

        if (response.isFailure) {
            Timber.e(response.exceptionOrNull(), "Failed to fetch headers for $folderId")
            return
        }

        val paged = response.getOrThrow()
        val messageEntities = paged.messages.map { it.toEntity(accountId) }

        appDatabase.withTransaction {
            // Ensure local folders exist for all remote labels before we process messages.
            val allRemoteLabelIds = paged.messages.flatMap { it.remoteLabelIds ?: emptyList() }.distinct()
            ensureLocalFoldersForRemoteIds(accountId, allRemoteLabelIds)

            appDatabase.messageDao().insertOrUpdateMessages(messageEntities)
            val junctionDao = appDatabase.messageFolderJunctionDao()
            val folderMapByRemote = folderDao.getFoldersForAccount(accountId)
                .first()
                .associateBy { it.remoteId }

            // Reconcile label links for every message so that the junction table mirrors server state.
            paged.messages.forEach { msg ->
                val remoteLabels = msg.remoteLabelIds ?: listOf(apiFolderId) // Use apiFolderId as fallback
                val localFolderIds = remoteLabels.mapNotNull { remoteId ->
                    folderMapByRemote[remoteId]?.id
                }.distinct()

                // If we somehow cannot resolve any folder locally, still ensure message remains linked to context folder.
                val effectiveFolderIds = if (localFolderIds.isEmpty()) listOf(folderId) else localFolderIds
                junctionDao.replaceFoldersForMessage(msg.id, effectiveFolderIds)

                // Persist attachment metadata
                if (msg.hasAttachments && msg.attachments.isNotEmpty()) {
                    val attachmentEntities = msg.attachments.map { it.toEntity() }
                    appDatabase.attachmentDao().insertOrUpdateAttachments(attachmentEntities)
                }
            }

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

    /**
     * Calculates the epoch millis cutoff for the initial sync duration preference.
     * Returns null when the preference represents "All time" or an invalid value.
     */
    private fun computeEarliestTimestamp(): Long? {
        val days = initialSyncDurationDays
        if (days == Long.MAX_VALUE || days <= 0L) return null
        val millisPerDay = 24L * 60L * 60L * 1000L
        return System.currentTimeMillis() - (days * millisPerDay)
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

            val service = mailApiServiceFactory.getService(job.accountId) ?: run {
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
        stopActivePolling() // Ensure no multiple pollers
        pollingJob = externalScope.launch {
            while (isActive) {
                Timber.d("Active polling tick")
                try {
                    val accounts = accountRepository.getAccounts().first()
                    accounts.forEach { account ->
                        // More efficient: just trigger a lightweight check for changes.
                        submit(SyncJob.CheckForNewMail(account.id))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during active polling")
                }
                delay(ACTIVE_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the foreground polling ticker, if running. */
    fun stopActivePolling() {
        pollingJob?.cancel()
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

            // Start foreground Initial Sync service if this appears to be the very first sync for the account.
            val accountEntity = accountDao.getAccountByIdSuspend(job.accountId)
            if (accountEntity?.lastFolderListSyncTimestamp == null) {
                maybeStartInitialSyncService()
            }

            val service = mailApiServiceFactory.getService(job.accountId) ?: run {
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
                // Ensure placeholders exist before we try to diff.
                val remoteFolderDtos = serverFolders.map { it.id to it.displayName }
                // Create a Map<remoteId, name?> for placeholder helper
                val nameMap = remoteFolderDtos.associate { (id, name) -> id to name }
                ensureLocalFoldersForRemoteIds(job.accountId, remoteFolderDtos.map { it.first }, nameMap)

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
                    // We can safely assume an existing placeholder, so existing should not be null.
                    val localId = existing?.id ?: java.util.UUID.randomUUID().toString()
                    serverFolder.toEntity(job.accountId, localId).copy(isPlaceholder = false) // Un-mark as placeholder
                }
                if (toUpsert.isNotEmpty()) {
                    folderDao.insertOrUpdateFolders(toUpsert)
                }
            }

            accountDao.updateFolderListSyncSuccess(job.accountId, System.currentTimeMillis())
            // If the service is running and sync is now complete, attempt to stop it.
            stopInitialSyncServiceIfIdle()
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

            val service = mailApiServiceFactory.getService(job.accountId) ?: run {
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

            val service = mailApiServiceFactory.getService(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            val result = service.downloadAttachment(job.messageId, job.attachmentId.toString())

            if (result.isFailure) {
                val ex = result.exceptionOrNull()
                attachmentDao.updateSyncStatus(job.attachmentId, EntitySyncStatus.ERROR, ex?.message)
                if (ex is net.melisma.core_data.errors.ApiServiceException && ex.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(job.accountId, true)
                }
                return@withContext
            }

            val data = result.getOrThrow()
            // Store attachments inside the no_backup directory so they are excluded from cloud backups
            val dir = java.io.File(appContext.noBackupFilesDir, "attachments/${job.messageId}")
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
                // If this job was triggered by a failure, clear any related error state
                if (_status.value.error?.startsWith("Failed action") == true) {
                    _status.update { it.copy(error = null) }
                }
                return@withContext
            }

            val service = mailApiServiceFactory.getService(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            // Helper to update retry / failed state
            suspend fun markFailure(reason: String?) {
                val isPermanentFailure = action.attemptCount + 1 >= action.maxAttempts
                val newStatus = if (isPermanentFailure) PendingActionStatus.FAILED else PendingActionStatus.RETRY
                val updated = action.copy(
                    status = newStatus,
                    lastAttemptAt = System.currentTimeMillis(),
                    lastError = reason,
                    attemptCount = action.attemptCount + 1
                )
                pendingActionDao.updateAction(updated)
                // Surface the error to the UI
                val errorMessage = "Failed action: ${action.actionType} for ${action.entityId}. Reason: $reason"
                _status.update { it.copy(error = errorMessage, isSyncing = !isPermanentFailure) }
            }

            try {
                val resultSuccessful: Boolean = when (action.actionType) {
                    SyncConstants.ACTION_MARK_AS_READ -> {
                        service.markMessageRead(action.entityId, true).isSuccess
                    }

                    SyncConstants.ACTION_MARK_AS_UNREAD -> {
                        service.markMessageRead(action.entityId, false).isSuccess
                    }

                    SyncConstants.ACTION_STAR_MESSAGE -> {
                        val isStarred = action.payload[SyncConstants.KEY_IS_STARRED]?.toBoolean() ?: true
                        service.starMessage(action.entityId, isStarred).isSuccess
                    }

                    SyncConstants.ACTION_DELETE_MESSAGE -> {
                        service.deleteMessage(action.entityId).isSuccess.also { ok ->
                            if (ok) messageDao.deleteMessageById(action.entityId)
                        }
                    }

                    SyncConstants.ACTION_MOVE_MESSAGE -> {
                        val oldFolder = action.payload[SyncConstants.KEY_OLD_FOLDER_ID]
                        val newFolder = action.payload[SyncConstants.KEY_NEW_FOLDER_ID]
                        if (oldFolder == null || newFolder == null) false else service.moveMessage(action.entityId, oldFolder, newFolder).isSuccess
                    }

                    SyncConstants.ACTION_MARK_THREAD_AS_READ -> {
                        service.markThreadRead(action.entityId, true).isSuccess
                    }
                    SyncConstants.ACTION_MARK_THREAD_AS_UNREAD -> {
                        service.markThreadRead(action.entityId, false).isSuccess
                    }
                    SyncConstants.ACTION_DELETE_THREAD -> {
                        service.deleteThread(action.entityId).isSuccess
                    }
                    SyncConstants.ACTION_MOVE_THREAD -> {
                        val oldFolder = action.payload[SyncConstants.KEY_OLD_FOLDER_ID]
                        val newFolder = action.payload[SyncConstants.KEY_NEW_FOLDER_ID]
                        if (oldFolder == null || newFolder == null) false else service.moveThread(action.entityId, oldFolder, newFolder).isSuccess
                    }

                    SyncConstants.ACTION_SEND_MESSAGE -> {
                        val draftJson = action.payload[SyncConstants.KEY_DRAFT_DETAILS]
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

                    SyncConstants.ACTION_CREATE_DRAFT -> {
                        val draftJson = action.payload[SyncConstants.KEY_DRAFT_DETAILS]
                        val draft = draftJson?.let { Json.decodeFromString<MessageDraft>(it) }
                        if (draft == null) false else service.createDraftMessage(draft).isSuccess
                    }

                    SyncConstants.ACTION_UPDATE_DRAFT -> {
                        val draftJson = action.payload[SyncConstants.KEY_DRAFT_DETAILS]
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
                    // Clear any previous error message for this action type
                    if (_status.value.error?.contains(action.actionType) == true) {
                         _status.update { it.copy(error = null) }
                    }
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
                // If the action we just processed failed and is now in RETRY, apply backoff delay
                if (another.id == action.id && another.status == PendingActionStatus.RETRY) {
                    val delayMs = BASE_RETRY_DELAY_MS * (1L shl (another.attemptCount - 1).coerceAtMost(5)) +
                            (Math.random() * MAX_RETRY_JITTER_MS).toLong()
                    Timber.i("Action ${action.id} failed, backing off for ${delayMs}ms before next attempt.")
                    delay(delayMs)
                }
                submit(SyncJob.UploadAction(job.accountId, another.actionType, another.entityId, another.payload))
            }
        }
    }

    private suspend fun runCacheEviction(accountId: String) {
        withContext(ioDispatcher) {
            val prefs = userPreferencesRepository.userPreferencesFlow.first()
            val cacheLimitBytes = prefs.cacheSizeLimitBytes
            val targetBytesAfterEvict = (cacheLimitBytes * 0.8).toLong()

            val attachmentDao = appDatabase.attachmentDao()
            val messageDao = appDatabase.messageDao()
            val messageBodyDao = appDatabase.messageBodyDao()

            // Gather current cache usage
            val downloadedAttachments = attachmentDao.getAllDownloadedAttachments()
                .filter { it.accountId == accountId }
            var attachmentsBytes = downloadedAttachments.sumOf { it.size }

            val messageBodies = messageBodyDao.getAllMessageBodies()
                .filter { body ->
                    val parent = messageDao.getMessageByIdSuspend(body.messageId)
                    parent?.accountId == accountId
                }
            var bodiesBytes = messageBodies.sumOf { it.sizeInBytes }

            var totalUsageBytes = attachmentsBytes + bodiesBytes
            Timber.d("Cache usage for account $accountId: ${totalUsageBytes / 1024 / 1024} MB (limit ${cacheLimitBytes / 1024 / 1024} MB)")
            if (totalUsageBytes <= cacheLimitBytes) return@withContext // under hard limit, no action
            val bytesToFree = totalUsageBytes - targetBytesAfterEvict
            if (bytesToFree <= 0) return@withContext // at or below soft target, nothing

            val evictionCutoffTs = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(90)
            var bytesFreed = 0L

            appDatabase.withTransaction {
                // 1. Evict attachments (least recently accessed messages first)
                val candidateAttachments = downloadedAttachments
                    .filter { attachment ->
                        val msg = messageDao.getMessageByIdSuspend(attachment.messageId) ?: return@filter false
                        // Exclude recent or protected messages
                        val lastAccess = msg.lastAccessedTimestamp ?: 0L
                        val protect = lastAccess >= evictionCutoffTs || msg.isDraft || msg.isOutbox
                        !protect
                    }
                    .sortedBy { it.downloadTimestamp ?: 0L }

                for (att in candidateAttachments) {
                    if (bytesFreed >= bytesToFree) break
                    // Delete file from disk
                    att.localFilePath?.let { path ->
                        try { java.io.File(path).delete() } catch (e: Exception) { Timber.w(e, "Failed to delete attachment file $path") }
                    }
                    // Reset DB flags so it can be redownloaded on demand
                    attachmentDao.resetDownloadStatus(att.id, EntitySyncStatus.PENDING_DOWNLOAD)
                    bytesFreed += att.size
                }

                // Refresh counters after attachment purge
                attachmentsBytes -= bytesFreed.coerceAtMost(attachmentsBytes)
                totalUsageBytes = attachmentsBytes + bodiesBytes
                if (bytesFreed >= bytesToFree) return@withTransaction

                // 2. Evict message bodies (oldest fetched first)
                val excludedStates = listOf(
                    EntitySyncStatus.PENDING_UPLOAD.name,
                    EntitySyncStatus.ERROR.name,
                    EntitySyncStatus.PENDING_DELETE.name,
                    EntitySyncStatus.PENDING_DOWNLOAD.name
                )
                val candidateBodies = messageBodies
                    .filter { body ->
                        val parent = messageDao.getMessageByIdSuspend(body.messageId) ?: return@filter false
                        val lastAccess = parent.lastAccessedTimestamp ?: 0L
                        val protect = lastAccess >= evictionCutoffTs || parent.isDraft || parent.isOutbox
                        !protect
                    }
                    .sortedBy { it.lastFetchedTimestamp }

                for (body in candidateBodies) {
                    if (bytesFreed >= bytesToFree) break
                    val size = body.sizeInBytes
                    messageBodyDao.deleteMessageBody(body.messageId)
                    bytesFreed += size
                    bodiesBytes -= size
                }

                totalUsageBytes = attachmentsBytes + bodiesBytes
                if (bytesFreed >= bytesToFree) return@withTransaction

                // 3. Evict entire message headers (least recently accessed)
                val candidateMessages = messageDao.getCacheEvictionCandidates(evictionCutoffTs, excludedStates)
                    .filter { it.accountId == accountId }
                    .sortedBy { it.lastAccessedTimestamp ?: 0L }

                for (msg in candidateMessages) {
                    if (bytesFreed >= bytesToFree) break
                    // Delete associated attachments files (if any)
                    val atts = attachmentDao.getDownloadedAttachmentsForMessage(msg.id)
                    atts.forEach { a ->
                        a.localFilePath?.let { path ->
                            try { java.io.File(path).delete() } catch (e: Exception) { Timber.w(e, "Failed to delete attachment file $path") }
                        }
                    }
                    // Cascading delete will remove bodies via FK
                    messageDao.deleteMessageById(msg.id)
                    // Body + header size approximated as 0.2KB to avoid costly recalculation; continue until limit
                    bytesFreed += 200L
                }
            }

            Timber.i("Cache eviction freed ${bytesFreed / 1024 / 1024} MB, new usage ${(totalUsageBytes - bytesFreed) / 1024 / 1024} MB")
        }
    }

    // Passive background polling coroutine
    fun startPassivePolling() {
        stopPassivePolling()
        passivePollingJob = externalScope.launch {
            while (isActive) {
                Timber.d("Passive polling tick")
                try {
                    val accounts = accountRepository.getAccounts().first()
                    accounts.forEach { account ->
                         // More efficient: just trigger a lightweight check for changes.
                         submit(SyncJob.CheckForNewMail(account.id))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during passive polling")
                }
                delay(PASSIVE_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPassivePolling() {
        passivePollingJob?.cancel()
        passivePollingJob = null
    }

    private suspend fun handleSearchOnline(job: SyncJob.SearchOnline) {
        withContext(ioDispatcher) {
            // Skip if offline
            if (!networkMonitor.isOnline.first()) {
                Timber.i("Offline – skipping online search for query '${job.query}'")
                return@withContext
            }

            val service = mailApiServiceFactory.getService(job.accountId) ?: run {
                Timber.w("MailApiService not found for account ${job.accountId}")
                return@withContext
            }

            val result = service.searchMessages(
                query = job.query,
                folderId = job.folderId,
                maxResults = 50
            )

            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Online search failed for '${job.query}'")
                return@withContext
            }

            val messages = result.getOrThrow()

            if (messages.isEmpty()) return@withContext

            val messageDao = appDatabase.messageDao()
            val folderDao = appDatabase.folderDao()
            val junctionDao = appDatabase.messageFolderJunctionDao()

            appDatabase.withTransaction {
                // Ensure local folders exist for all remote labels before we process messages.
                val allRemoteLabelIds = messages.flatMap { it.remoteLabelIds ?: emptyList() }.distinct()
                ensureLocalFoldersForRemoteIds(job.accountId, allRemoteLabelIds)

                // Upsert messages
                val entities = messages.map { it.toEntity(job.accountId) }
                messageDao.insertOrUpdateMessages(entities)

                val folderMapByRemote = folderDao.getFoldersForAccount(job.accountId)
                    .first()
                    .associateBy { it.remoteId }

                // Link messages to folders based on the labels returned by the search
                messages.forEach { msg ->
                    val remoteLabels = msg.remoteLabelIds ?: emptyList()
                    val localFolderIds = remoteLabels.mapNotNull { remoteId ->
                        folderMapByRemote[remoteId]?.id
                    }.distinct()
                    if (localFolderIds.isNotEmpty()) {
                        junctionDao.replaceFoldersForMessage(msg.id, localFolderIds)
                    }
                }
            }
            Timber.d("Online search stored ${messages.size} results for query '${job.query}' (account ${job.accountId})")
        }
    }

    /** Starts the [net.melisma.mail.sync.InitialSyncForegroundService] if not already running. */
    private fun maybeStartInitialSyncService() {
        val intent = Intent().apply {
            setClassName(appContext, "net.melisma.mail.sync.InitialSyncForegroundService")
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    /** Sends stop signal for the InitialSyncForegroundService if it is running. */
    private fun stopInitialSyncServiceIfIdle() {
        val intent = Intent().apply {
            setClassName(appContext, "net.melisma.mail.sync.InitialSyncForegroundService")
        }
        appContext.stopService(intent)
    }

    private suspend fun handleCheckForNewMail(job: SyncJob.CheckForNewMail) {
        if (!networkMonitor.isOnline.first()) {
            Timber.i("Offline – skipping CheckForNewMail for ${job.accountId}")
            return
        }

        val service = mailApiServiceFactory.getService(job.accountId) ?: run {
            Timber.w("MailApiService not found for account ${job.accountId}")
            return
        }
        val accountDao = appDatabase.accountDao()
        val currentToken = accountDao.getAccountByIdSuspend(job.accountId)?.latestDeltaToken

        val result = service.hasChangesSince(job.accountId, currentToken)

        if (result.isSuccess) {
            val (hasChanges, nextToken) = result.getOrThrow()
            accountDao.updateLatestDeltaToken(job.accountId, nextToken)
            if (hasChanges) {
                Timber.i("Changes detected for account ${job.accountId}, queueing full sync.")
                submit(SyncJob.SyncFolderList(job.accountId))
            } else {
                Timber.d("No changes detected for account ${job.accountId}.")
            }
        } else {
            Timber.e(result.exceptionOrNull(), "CheckForNewMail failed for account ${job.accountId}")
        }
    }

    /**
     * Given a list of remote folder/label IDs, this ensures a corresponding local FolderEntity
     * exists for each one. If a local folder for a given remote ID doesn't exist, a placeholder
     * record is created.
     *
     * This must be called within a database transaction.
     *
     * @param accountId The account to which these folders belong.
     * @param remoteIds The list of remote folder/label IDs from the server.
     * @param nameHintMap An optional map of [remoteId] to [displayName] to create more useful
     * placeholder names when a folder is first discovered via a message label.
     */
    private suspend fun ensureLocalFoldersForRemoteIds(
        accountId: String,
        remoteIds: List<String>,
        nameHintMap: Map<String, String?> = emptyMap()
    ) {
        if (remoteIds.isEmpty()) return
        val folderDao = appDatabase.folderDao()
        val existingFolders = folderDao.getFoldersForAccount(accountId).first()
        val existingRemoteIds = existingFolders.mapNotNull { it.remoteId }.toSet()

        val missingRemoteIds = remoteIds.filter { it !in existingRemoteIds }

        if (missingRemoteIds.isNotEmpty()) {
            Timber.d("Found ${missingRemoteIds.size} new remote folder IDs. Creating placeholders...")
            missingRemoteIds.forEach { remoteId ->
                val placeholderName = nameHintMap[remoteId] ?: remoteId
                folderDao.insertPlaceholderIfAbsent(accountId, remoteId, placeholderName)
            }
        }
    }
}