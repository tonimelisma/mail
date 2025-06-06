package net.melisma.data.repository

import android.app.Activity
import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.model.EmailAddress
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.MessageSyncState
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.AttachmentEntity
import net.melisma.core_db.entity.MessageEntity
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import net.melisma.data.paging.MessageRemoteMediator
import net.melisma.data.sync.SyncEngine
import net.melisma.data.sync.workers.ActionUploadWorker
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DefaultMessageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val accountRepository: AccountRepository,
    private val messageDao: MessageDao,
    private val messageBodyDao: MessageBodyDao,
    private val attachmentDao: AttachmentDao,
    private val folderDao: FolderDao,
    private val appDatabase: AppDatabase,
    private val workManager: WorkManager, // Keep for now, though SyncEngine will use it
    private val syncEngine: SyncEngine, // Inject SyncEngine
    private val networkMonitor: NetworkMonitor
) : MessageRepository {

    private val TAG = "DefaultMessageRepo"
    private val _messageSyncState = MutableStateFlow<MessageSyncState>(MessageSyncState.Idle)
    override val messageSyncState: StateFlow<MessageSyncState> = _messageSyncState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolderId: String? = null
    private var syncJob: Job? = null

    init {
        Timber.d("Initializing DefaultMessageRepository with MessageDao.")
    }

    override fun observeMessagesForFolder(
        accountId: String,
        folderId: String
    ): Flow<List<Message>> {
        Timber.d("observeMessagesForFolder: accountId=$accountId, folderId=$folderId")

        // Launch a one-off check to see if sync is needed for this folder.
        externalScope.launch(ioDispatcher) {
            val messagesCount = messageDao.getMessagesCountForFolder(accountId, folderId)
            val folderEntity =
                folderDao.getFolderByIdSuspend(folderId) // Assumes folderId is the local DB ID

            if (folderEntity != null) {
                // Heuristic: Sync if no messages locally, or if sync timestamp is very old/null.
                // More robust staleness (e.g., < 15 mins) can be added later.
                // Also, folderEntity.remoteId is needed for SyncEngine.
                val needsSync =
                    messagesCount == 0 || folderEntity.lastSuccessfulSyncTimestamp == null
                // || (System.currentTimeMillis() - (folderEntity.lastSuccessfulSyncTimestamp ?: 0) > SYNC_THRESHOLD_MS)


                if (needsSync && folderEntity.remoteId != null) {
                    Timber.d("observeMessagesForFolder: Folder $folderId (remote: ${folderEntity.remoteId}) needs content sync (count=$messagesCount, lastSync=${folderEntity.lastSuccessfulSyncTimestamp}). Triggering SyncEngine.")
                    syncEngine.syncFolderContent(accountId, folderId, folderEntity.remoteId!!)
                } else if (folderEntity.remoteId == null) {
                    Timber.w("observeMessagesForFolder: Cannot sync folder $folderId content because remoteId is null.")
                }
            } else {
                Timber.w("observeMessagesForFolder: FolderEntity for id $folderId not found. Cannot determine if sync is needed.")
            }
        }

        return messageDao.getMessagesForFolder(accountId, folderId)
            .map { entities -> entities.map { it.toDomainModel() } }
    }

    @OptIn(ExperimentalPagingApi::class)
    override fun getMessagesPager(
        accountId: String,
        folderId: String,
        pagingConfig: PagingConfig
    ): Flow<PagingData<Message>> {
        Timber.d(
            "getMessagesPager for accountId=$accountId, folderId=$folderId. PagingConfig: pageSize=${pagingConfig.pageSize}, prefetchDistance=${pagingConfig.prefetchDistance}, initialLoadSize=${pagingConfig.initialLoadSize}"
        )
        val account = runBlocking(ioDispatcher) {
            accountRepository.getAccountById(accountId).firstOrNull()
        }
        val providerType = account?.providerType?.uppercase()
        val apiServiceForMediator = mailApiServices[providerType]
            ?: mailApiServices.values.firstOrNull()
            ?: throw IllegalStateException("No MailApiService available for MessageRemoteMediator. Account: $accountId, Provider: $providerType")
        Timber.d(
            "getMessagesPager: Using API service for provider: $providerType for RemoteMediator."
        )

        return Pager(
            config = pagingConfig,
            remoteMediator = MessageRemoteMediator(
                accountId = accountId,
                folderId = folderId,
                database = appDatabase,
                mailApiService = apiServiceForMediator,
                networkMonitor = networkMonitor,
                ioDispatcher = ioDispatcher,
                onSyncStateChanged = { syncState -> _messageSyncState.value = syncState }
            ),
            pagingSourceFactory = { messageDao.getMessagesPagingSource(accountId, folderId) }
        ).flow
            .map { pagingData ->
                pagingData.map { messageEntity ->
                    messageEntity.toDomainModel()
                }
            }
    }

    override suspend fun setTargetFolder(
        account: Account?,
        folder: MailFolder?
    ) {
        val newAccountId = account?.id
        val newFolderId = folder?.id
        Timber.d(
            "setTargetFolder: Account=${account?.emailAddress}($newAccountId), Folder=${folder?.displayName}($newFolderId). CurrentTarget: ${currentTargetAccount?.id}/$currentTargetFolderId. SyncJob Active: ${syncJob?.isActive}"
        )

        if (newAccountId == currentTargetAccount?.id && newFolderId == currentTargetFolderId) {
            Timber.d(
                "setTargetFolder: Same target account/folder. No change to repository\'s internal target."
            )
            return
        }

        Timber.i("setTargetFolder: Target changed. Previous: ${currentTargetAccount?.id}/${currentTargetFolderId}, New: $newAccountId/$newFolderId. Clearing old sync job if any.")
        cancelAndClearSyncJob("Target folder changed in setTargetFolder. New: $newAccountId/$newFolderId")
        currentTargetAccount = account
        currentTargetFolderId = newFolderId

        if (newAccountId == null || newFolderId == null) {
            _messageSyncState.value = MessageSyncState.Idle
            Timber.d(
                "setTargetFolder: Target cleared (account or folder is null). Sync state set to Idle."
            )
        } else {
            Timber.d(
                "setTargetFolder: Updated currentTargetAccount and currentTargetFolderId. Paging system will handle data loading for $newAccountId/$newFolderId via getMessagesPager."
            )
        }
    }

    override suspend fun refreshMessages(activity: Activity?) {
        val account = currentTargetAccount
        val folderId = currentTargetFolderId
        if (account != null && folderId != null) {
            Timber.tag(TAG)
                .d("refreshMessages: Triggering sync for current target: Account ${account.id}, Folder $folderId")
            syncMessagesForFolderInternal(
                account,
                folderId,
                isRefresh = true,
                explicitRequest = true
            )
        } else {
            Timber.tag(TAG)
                .w("refreshMessages: No current target account or folder set. Cannot refresh.")
        }
    }

    override suspend fun syncMessagesForFolder(
        accountId: String,
        folderId: String,
        activity: Activity?
    ) {
        val account = accountRepository.getAccountById(accountId).firstOrNull()
        if (account == null) {
            Timber.e("syncMessagesForFolder: Account not found for id $accountId")
            _messageSyncState.value =
                MessageSyncState.SyncError(accountId, folderId, "Account not found for sync.")
            return
        }
        syncMessagesForFolderInternal(account, folderId, isRefresh = true, explicitRequest = true)
    }

    private fun syncMessagesForFolderInternal(
        account: Account,
        folderId: String,
        isRefresh: Boolean,
        explicitRequest: Boolean = false
    ) {
        val logPagingBehavior = "Paging Sync (RemoteMediator)"
        Timber.d(
            "[${account.id}/$folderId] syncMessagesForFolderInternal called. isRefresh: $isRefresh, explicitRequest: $explicitRequest, currentSyncJob active: ${syncJob?.isActive}. ViewModel should rely on $logPagingBehavior for list loading."
        )

        if (syncJob?.isActive == true && !isRefresh && !explicitRequest) {
            Timber.d(
                "syncMessagesForFolderInternal: Sync job already active for $folderId and not an explicit forced refresh. Ignoring call."
            )
            return
        }

        val logPrefix = "[${account.id}/$folderId]"
        Timber.d("$logPrefix syncMessagesForFolderInternal for account type: ${account.providerType}")

        cancelAndClearSyncJob("$logPrefix Launching new message sync. Refresh: $isRefresh, Explicit: $explicitRequest")

        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (apiService == null || errorMapper == null) {
            val errorMsg =
                "Unsupported account type: $providerType or missing services for message sync."
            Timber.e(errorMsg)
            _messageSyncState.value = MessageSyncState.SyncError(account.id, folderId, errorMsg)
            return
        }

        _messageSyncState.value = MessageSyncState.Syncing(account.id, folderId)

        syncJob = externalScope.launch(ioDispatcher) {
            Timber.i("$logPrefix Message sync job started. Refresh: $isRefresh")
            try {
                val currentMaxResults = 100
                val messagesResult = apiService.getMessagesForFolder(
                    folderId = folderId,
                    maxResults = currentMaxResults
                )
                ensureActive()

                if (messagesResult.isSuccess) {
                    val apiMessages = messagesResult.getOrThrow()
                    Timber.tag(TAG).i(
                        "$logPrefix Successfully fetched ${apiMessages.messages.size} messages from API (maxResults was $currentMaxResults)."
                    )
                    val messageEntities =
                        apiMessages.messages.map { msg -> msg.toEntity(account.id, folderId) }

                    appDatabase.withTransaction {
                        if (isRefresh) {
                            messageDao.deleteMessagesForFolder(account.id, folderId)
                        }
                        messageDao.insertOrUpdateMessages(messageEntities)
                    }
                    _messageSyncState.value = MessageSyncState.SyncSuccess(account.id, folderId)
                } else {
                    val error = messagesResult.exceptionOrNull()
                    val errorDetails = error?.let { errorMapper.mapExceptionToErrorDetails(it) }
                    val errorMessage = errorDetails?.message ?: error?.message
                    ?: "Unknown API error during message sync"
                    Timber.tag(TAG).w("$logPrefix Message sync API call failed: $errorMessage")
                    _messageSyncState.value =
                        MessageSyncState.SyncError(account.id, folderId, errorMessage)
                }
            } catch (e: CancellationException) {
                Timber.tag(TAG).i("$logPrefix Message sync job was cancelled.")
                _messageSyncState.value = MessageSyncState.Idle
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "$logPrefix Exception during message sync")
                val errorDetails = errorMapper.mapExceptionToErrorDetails(e)
                _messageSyncState.value = MessageSyncState.SyncError(
                    account.id,
                    folderId,
                    errorDetails.message ?: "Exception during message sync"
                )
            } finally {
                if (isActive) {
                    Timber.d("$logPrefix Sync job finished.")
                } else {
                    Timber.d("$logPrefix Sync job finished (was cancelled or completed).")
                }
            }
        }
    }

    private fun cancelAndClearSyncJob(reason: String) {
        if (syncJob?.isActive == true) {
            Timber.d("Cancelling active sync job: $reason")
            syncJob?.cancel(CancellationException(reason))
        }
        syncJob = null
    }

    override suspend fun getMessageDetails(
        messageId: String,
        accountId: String
    ): Flow<Message?> {
        // First, try to get the full message with body from the local DB
        val localMessageFlow = messageDao.getMessageById(messageId)
            .map { messageEntity ->
                if (messageEntity != null) {
                    val messageBody = messageBodyDao.getBodyForMessage(messageId)
                    messageEntity.toDomainModel().copy(
                        body = messageBody?.content,
                        bodyContentType = messageBody?.contentType
                    )
                } else {
                    null
                }
            }

        // Trigger a download from remote if not found locally or if forced
        // For simplicity here, we just return the local version.
        // A full implementation would check if the body is missing and then call
        // syncEngine.downloadMessageBody(accountId, messageId)
        return localMessageFlow
    }

    override suspend fun markMessageRead(
        account: Account,
        messageId: String,
        isRead: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        messageDao.updateReadStatus(messageId, isRead)
        syncEngine.enqueueMessageAction(
            account.id,
            messageId,
            ActionUploadWorker.ACTION_MARK_READ,
            mapOf("isRead" to isRead)
        )
        Result.success(Unit)
    }

    override suspend fun starMessage(
        account: Account,
        messageId: String,
        isStarred: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        messageDao.updateStarredStatus(messageId, isStarred)
        syncEngine.enqueueMessageAction(
            account.id,
            messageId,
            ActionUploadWorker.ACTION_STAR_MESSAGE,
            mapOf("isStarred" to isStarred)
        )
        Result.success(Unit)
    }

    override suspend fun deleteMessage(
        account: Account,
        messageId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        messageDao.deleteMessageById(messageId)
        syncEngine.enqueueMessageAction(
            account.id,
            messageId,
            ActionUploadWorker.ACTION_DELETE_MESSAGE,
            emptyMap()
        )
        Result.success(Unit)
    }

    override suspend fun moveMessage(
        account: Account,
        messageId: String,
        newFolderId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val currentMessage = messageDao.getMessageByIdSuspend(messageId)
            ?: return@withContext Result.failure(Exception("Message not found"))

        messageDao.updateFolderId(messageId, newFolderId)

        syncEngine.enqueueMessageAction(
            account.id,
            messageId,
            ActionUploadWorker.ACTION_MOVE_MESSAGE,
            mapOf(
                "currentFolderId" to currentMessage.folderId,
                "destinationFolderId" to newFolderId
            )
        )
        Result.success(Unit)
    }

    override suspend fun sendMessage(draft: MessageDraft, account: Account): Result<String> {
        val tempId = "local-sent-${UUID.randomUUID()}"
        syncEngine.enqueueMessageAction(
            account.id,
            tempId,
            ActionUploadWorker.ACTION_SEND_MESSAGE,
            mapOf(
                ActionUploadWorker.KEY_DRAFT_DETAILS to Json.encodeToString(draft)
            )
        )
        return Result.success(tempId)
    }

    override fun searchMessages(
        accountId: String,
        query: String,
        folderId: String?
    ): Flow<List<Message>> {
        // This should ideally trigger a remote search and cache results.
        // For now, it searches the local database.
        return messageDao.searchMessages("%$query%", accountId, folderId)
            .map { entities -> entities.map { it.toDomainModel() } }
    }

    override suspend fun getMessageAttachments(
        accountId: String,
        messageId: String
    ): Flow<List<Attachment>> {
        return attachmentDao.getAttachmentsForMessage(messageId)
            .map { entities -> entities.map { attachmentEntity: AttachmentEntity -> attachmentEntity.toDomainModel() } }
    }

    override suspend fun downloadAttachment(
        accountId: String,
        messageId: String,
        attachment: Attachment
    ): Flow<String?> = channelFlow {
        // Use SyncEngine to trigger the download
        syncEngine.downloadAttachment(accountId, messageId, attachment.id, attachment.fileName)

        // Observe the attachment's download status from the database
        attachmentDao.getAttachmentById(attachment.id).collect { attachmentEntity ->
            if (attachmentEntity != null) {
                when (attachmentEntity.syncStatus) {
                    SyncStatus.SYNCED -> send(attachmentEntity.localFilePath)
                    SyncStatus.ERROR -> throw IOException(
                        attachmentEntity.lastSyncError ?: "Attachment download failed"
                    )

                    else -> {
                        // Still in progress, do nothing, wait for next update
                    }
                }
            }
        }
    }

    private suspend fun updateStarredStatusInDb(
        messageId: String,
        isStarred: Boolean
    ) {
        messageDao.updateStarredStatus(
            messageId,
            isStarred
        )
    }

    private suspend fun deleteMessageFromDb(messageId: String) {
        messageDao.deleteMessageById(messageId)
    }

    private suspend fun saveLocalDraft(
        accountId: String,
        draftDetails: MessageDraft,
        isNew: Boolean
    ): Result<Message> {
        val account = accountRepository.getAccountById(accountId).firstOrNull()
            ?: return Result.failure(Exception("Account not found for draft saving"))

        val draftFolderId = folderDao.getFolderIdByType(accountId, "DRAFTS")?.id
            ?: return Result.failure(Exception("Unable to find DRAFTS folder for account $accountId"))

        val messageId = if (isNew) {
            draftDetails.existingMessageId ?: UUID.randomUUID().toString()
        } else {
            draftDetails.existingMessageId ?: return Result.failure(
                IllegalArgumentException("existingMessageId is required for updates")
            )
        }

        val entity = MessageEntity(
            id = messageId,
            messageId = if (isNew) null else draftDetails.existingMessageId,
            accountId = accountId,
            folderId = draftFolderId,
            threadId = draftDetails.inReplyTo,
            subject = draftDetails.subject,
            snippet = draftDetails.body.take(100),
            senderName = account.displayName,
            senderAddress = account.emailAddress,
            recipientNames = draftDetails.to.mapNotNull { emailAddressObj: EmailAddress -> emailAddressObj.displayName },
            recipientAddresses = draftDetails.to.map { emailAddressObj: EmailAddress -> emailAddressObj.emailAddress },
            timestamp = System.currentTimeMillis(),
            sentTimestamp = System.currentTimeMillis(),
            isRead = true,
            isStarred = false,
            hasAttachments = draftDetails.attachments.isNotEmpty(),
            body = draftDetails.body
        )

        val attachments = draftDetails.attachments.map {
            AttachmentEntity(
                attachmentId = it.id,
                messageId = messageId,
                fileName = it.fileName,
                mimeType = it.contentType,
                size = it.size,
                isInline = it.isInline,
                contentId = it.contentId,
                localFilePath = it.localUri,
                isDownloaded = it.localUri != null,
                downloadTimestamp = if (it.localUri != null) System.currentTimeMillis() else null,
                syncStatus = SyncStatus.IDLE,
                lastSyncError = null
            )
        }

        appDatabase.withTransaction {
            messageDao.insertOrUpdateMessages(listOf(entity))
            if (attachments.isNotEmpty()) {
                attachmentDao.insertAttachments(attachments)
            }
        }
        val savedMessage = entity.toDomainModel()
            .copy(attachments = attachments.map { attachmentEntity: AttachmentEntity -> attachmentEntity.toDomainModel() })
        return Result.success(savedMessage)
    }

    override suspend fun createDraftMessage(
        accountId: String,
        draftDetails: MessageDraft
    ): Result<Message> = withContext(ioDispatcher) {
        val account = accountRepository.getAccountById(accountId).firstOrNull()
            ?: return@withContext Result.failure(Exception("Account not found"))

        // Create a temporary local-only message
        val tempId = "local-draft-${UUID.randomUUID()}"
        val draftFolder = folderDao.getFolderIdByType(accountId, "DRAFTS")
            ?: return@withContext Result.failure(Exception("Drafts folder not found"))

        val newMessage = MessageEntity(
            id = tempId,
            messageId = null,
            accountId = accountId,
            folderId = draftFolder.id,
            threadId = null,
            subject = draftDetails.subject,
            snippet = draftDetails.body.take(100),
            body = draftDetails.body,
            senderName = account.emailAddress,
            senderAddress = account.emailAddress,
            recipientNames = draftDetails.to.mapNotNull { emailAddressObj: EmailAddress -> emailAddressObj.displayName },
            recipientAddresses = draftDetails.to.map { emailAddressObj: EmailAddress -> emailAddressObj.emailAddress },
            timestamp = System.currentTimeMillis(),
            sentTimestamp = null,
            isRead = true,
            isStarred = false,
            hasAttachments = false
        )

        messageDao.insertOrUpdateMessages(listOf(newMessage))

        // Enqueue the actual creation
        syncEngine.enqueueMessageAction(
            accountId,
            tempId,
            ActionUploadWorker.ACTION_CREATE_DRAFT,
            mapOf(
                ActionUploadWorker.KEY_DRAFT_DETAILS to Json.encodeToString(draftDetails)
            )
        )

        Result.success(newMessage.toDomainModel())
    }

    override suspend fun updateDraftMessage(
        accountId: String,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message> = withContext(ioDispatcher) {
        accountRepository.getAccountById(accountId).firstOrNull()
            ?: return@withContext Result.failure(Exception("Account not found"))

        // Update the local message optimistically
        val existingMessage = messageDao.getMessageByIdSuspend(messageId)
            ?: return@withContext Result.failure(Exception("Message not found"))

        val updatedMessage = existingMessage.copy(
            subject = draftDetails.subject,
            body = draftDetails.body,
            // ... other fields
        )
        messageDao.insertOrUpdateMessages(listOf(updatedMessage))

        syncEngine.enqueueMessageAction(
            accountId,
            messageId,
            ActionUploadWorker.ACTION_UPDATE_DRAFT,
            mapOf(
                ActionUploadWorker.KEY_DRAFT_DETAILS to Json.encodeToString(draftDetails)
            )
        )

        Result.success(updatedMessage.toDomainModel())
    }
}