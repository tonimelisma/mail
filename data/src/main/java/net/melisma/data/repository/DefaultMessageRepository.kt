package net.melisma.data.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.model.DraftType
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.MessageSyncState
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.AttachmentEntity
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_db.entity.MessageEntity
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import net.melisma.data.paging.MessageRemoteMediator
import net.melisma.data.worker.SyncMessageStateWorker
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    private val appDatabase: AppDatabase,
    private val workManager: WorkManager // Keep for now, though SyncEngine will use it
    // TODO: P2_WRITE - Inject SyncEngine
    // private val syncEngine: net.melisma.data.sync.SyncEngine
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
        // TODO: P1_SYNC - Request sync from SyncEngine if message list is stale or empty for this folder.
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
                ioDispatcher = ioDispatcher,
                onSyncStateChanged = { syncState -> _messageSyncState.value = syncState }
            ),
            pagingSourceFactory = { messageDao.getMessagesPagingSource(accountId, folderId) }
        ).flow
            .map { pagingDataEntity: PagingData<MessageEntity> ->
                pagingDataEntity.map { messageEntity: MessageEntity ->
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
            "setTargetFolder: Account=${account?.username}($newAccountId), Folder=${folder?.displayName}($newFolderId). CurrentTarget: ${currentTargetAccount?.id}/$currentTargetFolderId. SyncJob Active: ${syncJob?.isActive}"
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
                        "$logPrefix Successfully fetched ${apiMessages.size} messages from API (maxResults was $currentMaxResults)."
                    )
                    val messageEntities =
                        apiMessages.map { msg -> msg.toEntity(account.id, folderId) }

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
        Timber.d("RepoDBG: getMessageDetails called. AccountId: $accountId, MessageId: $messageId")
        return getMessageWithBody(accountId, messageId)
    }

    private fun getMessageWithBody(accountId: String, messageId: String): Flow<Message?> =
        channelFlow {
            Timber.d("RepoDBG: getMessageWithBody started. AccountId: $accountId, MessageId: $messageId")

            // Step 1: Initial DB Load and Emit
            var existingMessageEntity = messageDao.getMessageByIdSuspend(messageId)
            var existingBodyEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            Timber.d("RepoDBG: Initial DB load - MessageEntity found: ${existingMessageEntity != null}, BodyEntity found: ${existingBodyEntity != null}, BodyEntity content blank: ${existingBodyEntity?.content.isNullOrBlank()}")

            var messageToSend: Message? = existingMessageEntity?.toDomainModel()?.copy(
                body = existingBodyEntity?.content,
                bodyContentType = existingBodyEntity?.contentType
            )
            Timber.d("RepoDBG: Emitting from cache - Message ID: ${messageToSend?.id}, Subject: '${messageToSend?.subject}', Body is blank: ${messageToSend?.body.isNullOrBlank()}, BodyContentType: ${messageToSend?.bodyContentType}")
            send(messageToSend) // Emit cached state immediately

            // TODO: P1_SYNC - Request message body fetch from SyncEngine if existingBodyEntity is null or content is empty.
            // The SyncEngine will fetch the body, save it to messageBodyDao,
            // which should then trigger this Flow to re-emit if it's observing messageBodyDao.
            // For now, this flow only emits what's currently in the DB.

            // Step 2 & 3 (Network Fetch and subsequent DB save/emit) are removed from this read Flow.
            // The flow will emit the initial DB state and then complete.
            // If the data changes in DB due to external sync, a new collection of this Flow will see it.
            // If this needs to be a long-lived flow that updates when body is fetched,
            // it should be messageDao.getMessageById(messageId).combine(messageBodyDao.getMessageBodyById(messageId))
            // For now, per instructions, it's a one-shot read from DB then complete.

            Timber.d("RepoDBG: getMessageWithBody for $messageId finished. Emitted cached content. Closing flow.")
            close() // Close the flow after emitting cached content.
        }.flowOn(ioDispatcher)

    override suspend fun markMessageRead(
        account: Account,
        messageId: String,
        isRead: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("markMessageRead: msgId=$messageId, isRead=$isRead, account=${account.id}")
        try {
            appDatabase.withTransaction {
                messageDao.updateReadState(messageId, isRead, syncStatus = SyncStatus.PENDING_UPLOAD)
            }
            Timber.i("Optimistically marked message $messageId as isRead=$isRead, syncStatus=PENDING_UPLOAD")
            // TODO: P2_WRITE - Replace with SyncEngine call
            // syncEngine.enqueueActionUploadWorker(
            //     accountId = account.id,
            //     entityId = messageId,
            //     actionType = ActionUploadWorker.ACTION_MARK_MESSAGE_READ,
            //     payload = workDataOf("IS_READ" to isRead)
            // )
            enqueueActionUploadWorker(
                accountId = account.id,
                entityId = messageId,
                actionType = ActionUploadWorker.ACTION_MARK_MESSAGE_READ,
                additionalPayload = mapOf("IS_READ" to isRead)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error in markMessageRead for $messageId")
            val errorDetails =
                errorMappers[account.providerType.uppercase()]?.mapExceptionToErrorDetails(e)
            Result.failure(Exception(errorDetails?.message ?: e.message, e))
        }
    }

    override suspend fun starMessage(
        account: Account,
        messageId: String,
        isStarred: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("starMessage: msgId=$messageId, isStarred=$isStarred, account=${account.id}")
        try {
            appDatabase.withTransaction {
                messageDao.updateStarredState(messageId, isStarred, syncStatus = SyncStatus.PENDING_UPLOAD)
            }
            Timber.i("Optimistically starred message $messageId as isStarred=$isStarred, syncStatus=PENDING_UPLOAD")
            // TODO: P2_WRITE - Replace with SyncEngine call
            // syncEngine.enqueueActionUploadWorker(
            //     accountId = account.id,
            //     entityId = messageId,
            //     actionType = ActionUploadWorker.ACTION_STAR_MESSAGE,
            //     payload = workDataOf("IS_STARRED" to isStarred)
            // )
            enqueueActionUploadWorker(
                accountId = account.id,
                entityId = messageId,
                actionType = ActionUploadWorker.ACTION_STAR_MESSAGE,
                additionalPayload = mapOf("IS_STARRED" to isStarred)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error in starMessage for $messageId")
            val errorDetails =
                errorMappers[account.providerType.uppercase()]?.mapExceptionToErrorDetails(e)
            Result.failure(Exception(errorDetails?.message ?: e.message, e))
        }
    }

    override suspend fun deleteMessage(
        account: Account,
        messageId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("deleteMessage: msgId=$messageId, account=${account.id}")
        try {
            appDatabase.withTransaction {
                messageDao.markAsLocallyDeleted(
                    messageId = messageId,
                    isLocallyDeleted = true,
                    syncStatus = SyncStatus.PENDING_UPLOAD
                )
            }
            Timber.i("Optimistically marked message $messageId as locally deleted, syncStatus=PENDING_UPLOAD")
            // TODO: P2_WRITE - Replace with SyncEngine call
            // syncEngine.enqueueActionUploadWorker(
            //     accountId = account.id,
            //     entityId = messageId,
            //     actionType = ActionUploadWorker.ACTION_DELETE_MESSAGE
            // )
            enqueueActionUploadWorker(
                accountId = account.id,
                entityId = messageId,
                actionType = ActionUploadWorker.ACTION_DELETE_MESSAGE
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error in deleteMessage for $messageId")
            // Attempt to update sync error on the existing message if the optimistic delete failed before worker enqueue
            val currentMessage = messageDao.getMessageByIdSuspend(messageId)
            if (currentMessage != null && !currentMessage.isLocallyDeleted) { // Check if it wasn't marked as deleted
                val errorMessage =
                    errorMappers[account.providerType.uppercase()]?.mapExceptionToErrorDetails(e)?.message
                        ?: e.message
                messageDao.updateLastSyncError(messageId, "Optimistic delete failed: $errorMessage") // This sets syncStatus to ERROR
            }
            Result.failure(Exception("Failed to mark message for deletion: ${e.message}", e))
        }
    }

    override suspend fun moveMessage(
        account: Account,
        messageId: String,
        newFolderId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("moveMessage: msgId=$messageId to newFolderId=$newFolderId for account=${account.id}")
        var oldFolderId: String? = null
        try {
            // First, get the current folderId to pass to the worker for Gmail API requirements
            val currentMessage = messageDao.getMessageByIdSuspend(messageId)
            if (currentMessage == null) {
                Timber.e("Message $messageId not found. Cannot move.")
                return@withContext Result.failure(NoSuchElementException("Message $messageId not found"))
            }
            oldFolderId = currentMessage.folderId

            if (oldFolderId == newFolderId) {
                Timber.i("Message $messageId is already in folder $newFolderId. No action taken.")
                return@withContext Result.success(Unit) // Or a specific result indicating no operation
            }

            // Optimistically update the local database
            appDatabase.withTransaction {
                messageDao.updateMessageFolderAndSyncState(
                    messageId = messageId,
                    newFolderId = newFolderId,
                    syncStatus = SyncStatus.PENDING_UPLOAD
                )
            }
            Timber.i("Optimistically moved message $messageId to folder $newFolderId, syncStatus=PENDING_UPLOAD")

            // TODO: P2_WRITE - Replace with SyncEngine call
            // syncEngine.enqueueActionUploadWorker(
            //     accountId = account.id,
            //     entityId = messageId,
            //     actionType = ActionUploadWorker.ACTION_MOVE_MESSAGE,
            //     payload = workDataOf("NEW_FOLDER_ID" to newFolderId, "OLD_FOLDER_ID" to oldFolderId)
            // )
            enqueueActionUploadWorker(
                accountId = account.id,
                entityId = messageId,
                actionType = ActionUploadWorker.ACTION_MOVE_MESSAGE,
                additionalPayload = mapOf("NEW_FOLDER_ID" to newFolderId, "OLD_FOLDER_ID" to oldFolderId)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error in moveMessage for $messageId to $newFolderId")
            // If optimistic update failed or worker enqueue failed, try to set sync error.
            // The local message's folderId might not have been updated if transaction failed.
            // It's also possible oldFolderId wasn't fetched if getMessageByIdSuspend failed.
            val messageForErrorUpdate = messageDao.getMessageByIdSuspend(messageId)
            if (messageForErrorUpdate != null) {
                val errorMessage =
                    errorMappers[account.providerType.uppercase()]?.mapExceptionToErrorDetails(e)?.message
                        ?: e.message
                // If the folder was updated optimistically, revert or set error on new folderId.
                // If not, set error on oldFolderId.
                val folderIdForError = if (messageForErrorUpdate.folderId == newFolderId) newFolderId else oldFolderId ?: messageForErrorUpdate.folderId
                messageDao.updateFolderIdSyncErrorAndStatus(
                    messageId = messageId,
                    folderId = folderIdForError,
                    errorMessage = "Optimistic move to $newFolderId failed: $errorMessage".take(500),
                    syncStatus = SyncStatus.ERROR
                )
            }
            Result.failure(
                Exception(
                    "Failed to move message $messageId to $newFolderId: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun sendMessage(draft: MessageDraft, account: Account): Result<String> {
        return withContext(ioDispatcher) {
            try {
                // Generate a unique message ID for the outbox entry
                val messageId = "outbox_${System.currentTimeMillis()}_${account.id}"
                val currentTime = System.currentTimeMillis()

                appDatabase.withTransaction {
                    // Create outbox message entity
                    val outboxMessage = MessageEntity(
                        messageId = messageId,
                        accountId = account.id,
                        folderId = "OUTBOX", // Special folder for outbox
                        threadId = draft.originalMessageId, // For reply threading
                        subject = draft.subject,
                        snippet = draft.body?.take(150),
                        senderName = account.displayName ?: account.emailAddress,
                        senderAddress = account.emailAddress,
                        recipientNames = draft.to, // Will be converted by TypeConverter
                        recipientAddresses = draft.to, // Will be converted by TypeConverter
                        timestamp = currentTime,
                        sentTimestamp = null,
                        isRead = true,
                        isStarred = false,
                        hasAttachments = draft.attachments.isNotEmpty(),
                        isLocallyDeleted = false,
                        lastSyncError = null,
                        isDraft = false, // No longer a draft
                        isOutbox = true, // Now in outbox
                        draftType = draft.type.name,
                        draftParentId = draft.originalMessageId,
                        sendAttempts = 0,
                        lastSendError = null,
                        scheduledSendTime = null,
                        syncStatus = SyncStatus.PENDING_UPLOAD,
                        lastSyncAttemptTimestamp = null,
                        lastSuccessfulSyncTimestamp = null,
                        isLocalOnly = true, // True for outbox messages until successfully sent
                        needsFullSync = false
                    )

                    // Insert into database
                    messageDao.insertOrUpdateMessages(listOf(outboxMessage))

                    // Store message body
                    val messageBody = MessageBodyEntity(
                        messageId = messageId,
                        contentType = "HTML",
                        content = draft.body,
                        lastFetchedTimestamp = currentTime
                    )
                    messageBodyDao.insertOrUpdateMessageBody(messageBody)

                    // Enqueue worker to send the message
                    val draftJson = Json.encodeToString(draft) // This is the payload
                    // TODO: P2_WRITE - Replace with SyncEngine call
                    // syncEngine.enqueueActionUploadWorker(
                    //     accountId = account.id,
                    //     entityId = messageId,
                    //     actionType = ActionUploadWorker.ACTION_SEND_MESSAGE,
                    //     payload = workDataOf("DRAFT_JSON" to draftJson, "SENT_FOLDER_ID" to "SENT")
                    // )
                    enqueueActionUploadWorker(
                        accountId = account.id,
                        entityId = messageId,
                        actionType = ActionUploadWorker.ACTION_SEND_MESSAGE,
                        additionalPayload = mapOf("DRAFT_JSON" to draftJson, "SENT_FOLDER_ID" to "SENT")
                    )
                }
                Timber.d("$TAG: Message $messageId enqueued for sending via ActionUploadWorker.")
                Result.success(messageId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to queue message for sending")
                Result.failure(e)
            }
        }
    }

    override suspend fun getMessageAttachments(
        accountId: String,
        messageId: String
    ): Flow<List<Attachment>> {
        return withContext(ioDispatcher) {
            try {
                Timber.d(
                    "getMessageAttachments called for accountId: $accountId, messageId: $messageId"
                )

                // First try to get attachments from local database
                val localAttachments = attachmentDao.getAttachmentsForMessage(messageId)

                // Check if we need to fetch from API
                val message = messageDao.getMessageByIdSuspend(messageId)
                if (message?.hasAttachments == true) {
                    // Check if we have attachments in local DB
                    val existingAttachments =
                        attachmentDao.getAttachmentsForMessageSuspend(messageId)

                    if (existingAttachments.isEmpty()) {
                        // TODO: P1_SYNC - Request attachment list sync from SyncEngine for message $messageId because local attachments are empty but message.hasAttachments is true.
                        // The SyncEngine would call a method like internalFetchAndStoreAttachments(account, messageId).
                        // For now, do not call fetchAndStoreAttachments(account, messageId) directly here.
                        Timber.d("getMessageAttachments: Attachments potentially missing for $messageId. SyncEngine should handle fetching.")
                    }
                }

                // Return flow of attachments, mapped to domain model
                localAttachments.map { attachmentEntities -> // This flow is from attachmentDao.getAttachmentsForMessage(messageId)
                    attachmentEntities.map { entity ->
                        Attachment(
                            id = entity.attachmentId,
                            fileName = entity.fileName,
                            size = entity.size,
                            contentType = entity.contentType,
                            contentId = entity.contentId,
                            isInline = entity.isInline
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error getting message attachments for $messageId")
                flowOf(emptyList()) // Return empty list on error
            }
        }
    }

    override suspend fun downloadAttachment(
        accountId: String,
        messageId: String,
        attachment: Attachment
    ): Flow<String?> = channelFlow {
        Log.d(
            TAG,
            "downloadAttachment started for account $accountId, message $messageId, attachment ${attachment.id} (${attachment.fileName})"
        )
        send(null) // Emit initial state (e.g., for progress, though not fully implemented here)

        try {
            val account = accountRepository.getAccountById(accountId).firstOrNull()
            if (account == null) {
                Log.e(TAG, "downloadAttachment: Account not found for ID: $accountId")
                attachmentDao.updateDownloadStatus(
                    attachmentId = attachment.id,
                    isDownloaded = false,
                    localFilePath = null,
                    timestamp = null,
                    error = "Account not found"
                )
                send(null) // Error
                close()
                return@channelFlow
            }

            val providerType = account.providerType.uppercase()
            val mailApiService = mailApiServices[providerType]

            if (mailApiService == null) {
                Log.e(TAG, "downloadAttachment: MailApiService not found for provider: $providerType")
                attachmentDao.updateDownloadStatus(
                    attachmentId = attachment.id,
                    isDownloaded = false,
                    localFilePath = null,
                    timestamp = null,
                    error = "API service not found"
                )
                send(null) // Error
                close()
                return@channelFlow
            }

            val result = mailApiService.downloadAttachment(messageId, attachment.id)

            if (result.isSuccess) {
                val attachmentData = result.getOrThrow()
                // Save to internal storage
                val attachmentsDir = File(context.filesDir, "attachments/$messageId")
                if (!attachmentsDir.exists()) {
                    attachmentsDir.mkdirs()
                }
                val localFile = File(attachmentsDir, attachment.fileName)

                FileOutputStream(localFile).use { outputStream ->
                    outputStream.write(attachmentData)
                }
                Log.d(TAG, "Attachment ${attachment.fileName} saved to ${localFile.absolutePath}")

                attachmentDao.updateDownloadStatus(
                    attachmentId = attachment.id,
                    isDownloaded = true,
                    localFilePath = localFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    error = null
                )
                send(localFile.absolutePath) // Success, emit file path
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown download error"
                Log.e(TAG, "downloadAttachment: Failed for ${attachment.fileName} - $error")
                attachmentDao.updateDownloadStatus(
                    attachmentId = attachment.id,
                    isDownloaded = false,
                    localFilePath = null,
                    timestamp = null,
                    error = error
                )
                send(null) // Error
            }
        } catch (e: IOException) {
            Log.e(TAG, "downloadAttachment: IOException for ${attachment.fileName} - ${e.message}", e)
            attachmentDao.updateDownloadStatus(
                attachmentId = attachment.id,
                isDownloaded = false,
                localFilePath = null,
                timestamp = null,
                error = "IOException: ${e.message}"
            )
            send(null) // Error
        } catch (e: Exception) {
            Log.e(TAG, "downloadAttachment: Exception for ${attachment.fileName} - ${e.message}", e)
            attachmentDao.updateDownloadStatus(
                attachmentId = attachment.id,
                isDownloaded = false,
                localFilePath = null,
                timestamp = null,
                error = "Exception: ${e.message}"
            )
            send(null) // Error
        } finally {
            close() // Ensure the channel is closed
        }
    }.flowOn(ioDispatcher).catch { e ->
        // This catch is for upstream errors in the flow construction itself or from ioDispatcher
        Log.e(TAG, "downloadAttachment: Flow error for attachment ${attachment.id} - ${e.message}", e)
        // Attempt to update DB even if flow fails, though attachmentId might not be available if attachment itself is the problem
        // This specific update might be better inside the channelFlow's try/catch
        // For now, we assume 'attachment.id' is valid if we reach here.
        attachmentDao.updateDownloadStatus(
            attachmentId = attachment.id,
            isDownloaded = false,
            localFilePath = null,
            timestamp = null,
            error = "Flow failed: ${e.message}"
        )
        emit(null) // Emit error state
    }

    override suspend fun createDraftMessage(
        accountId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        return withContext(ioDispatcher) {
            try {
                val account = accountRepository.getAccountByIdSuspend(accountId)
                if (account == null) {
                    Timber.e("Account not found for ID: $accountId")
                    return@withContext Result.failure(IllegalArgumentException("Account not found"))
                }

                val draftId = "draft_${System.currentTimeMillis()}_${accountId}"
                val currentTime = System.currentTimeMillis()

                appDatabase.withTransaction {
                    val draftMessageEntity = MessageEntity(
                        messageId = draftId,
                        accountId = accountId,
                        folderId = "DRAFTS", // Consider a constant or configurable default draft folder ID
                        threadId = draftDetails.originalMessageId,
                        subject = draftDetails.subject,
                        snippet = draftDetails.body?.take(150),
                        senderName = account.displayName ?: account.emailAddress,
                        senderAddress = account.emailAddress,
                        recipientNames = draftDetails.to, // Assuming to, cc, bcc are List<String> of addresses
                        recipientAddresses = draftDetails.to,
                        timestamp = currentTime,
                        sentTimestamp = null,
                        isRead = true, // Drafts are typically read by the sender
                        isStarred = false,
                        hasAttachments = draftDetails.attachments.isNotEmpty(),
                        isLocallyDeleted = false,
                        lastSyncError = null,
                        isDraft = true,
                        isOutbox = false,
                        draftType = draftDetails.type.name,
                        draftParentId = draftDetails.originalMessageId,
                        sendAttempts = 0,
                        lastSendError = null,
                        scheduledSendTime = null,
                        syncStatus = SyncStatus.PENDING_UPLOAD,
                        lastSyncAttemptTimestamp = null,
                        lastSuccessfulSyncTimestamp = null,
                        isLocalOnly = false, // Assuming drafts are meant to be synced eventually
                        needsFullSync = false
                    )
                    messageDao.insertOrUpdateMessages(listOf(draftMessageEntity))

                    if (draftDetails.body != null) {
                        val messageBody = MessageBodyEntity(
                            messageId = draftId,
                            contentType = "HTML", // Assuming HTML, make configurable if needed
                            content = draftDetails.body,
                            lastFetchedTimestamp = currentTime,
                            syncStatus = SyncStatus.PENDING_UPLOAD // Body also needs upload
                        )
                        messageBodyDao.insertOrUpdateMessageBody(messageBody) // Corrected call
                    }

                    val draftJson = Json.encodeToString(draftDetails)
                    // TODO: P2_WRITE - Replace with SyncEngine call
                    // syncEngine.enqueueActionUploadWorker(
                    //    accountId = account.id,
                    //    entityId = draftId,
                    //    actionType = ActionUploadWorker.ACTION_CREATE_DRAFT,
                    //    payload = workDataOf("DRAFT_JSON" to draftJson)
                    // )
                    enqueueActionUploadWorker(
                        accountId = account.id,
                        entityId = draftId,
                        actionType = ActionUploadWorker.ACTION_CREATE_DRAFT,
                        additionalPayload = mapOf("DRAFT_JSON" to draftJson)
                    )
                }

                // Construct and return the domain model Message
                // Fetching from DB after transaction would be more robust for SSoT
                val createdMessage = messageDao.getMessageByIdSuspend(draftId)?.toDomainModel()?.copy(
                    body = draftDetails.body, // Add body from input as it might not be in MessageEntity.toDomainModel()
                    hasAttachments = draftDetails.attachments.isNotEmpty() // Explicitly set hasAttachments
                )
                if (createdMessage != null) {
                    Timber.d("Draft created with ID: $draftId")
                    Result.success(createdMessage)
                } else {
                    Timber.e("Failed to retrieve created draft $draftId from DB after transaction.")
                    Result.failure(IllegalStateException("Failed to create draft locally"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to create draft message for account $accountId")
                Result.failure(e)
            }
        }
    }

    override suspend fun updateDraftMessage(
        accountId: String,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        return withContext(ioDispatcher) {
            try {
                val account = accountRepository.getAccountByIdSuspend(accountId)
                if (account == null) {
                    Timber.e("Account not found for ID: $accountId")
                    return@withContext Result.failure(IllegalArgumentException("Account not found"))
                }

                val existingDraft = messageDao.getMessageByIdSuspend(messageId)
                if (existingDraft == null || !existingDraft.isDraft) {
                    Timber.e("Draft message not found or not a draft: $messageId")
                    return@withContext Result.failure(IllegalArgumentException("Draft not found or not a draft"))
                }
                val currentTime = System.currentTimeMillis()

                appDatabase.withTransaction {
                    messageDao.updateDraftContent(
                        messageId = messageId,
                        subject = draftDetails.subject,
                        snippet = draftDetails.body?.take(150),
                        recipientNames = draftDetails.to,
                        recipientAddresses = draftDetails.to,
                        timestamp = currentTime,
                        syncStatus = SyncStatus.PENDING_UPLOAD,
                        draftType = draftDetails.type.name,
                        draftParentId = draftDetails.originalMessageId
                    )

                    if (draftDetails.body != null) {
                        val messageBody = MessageBodyEntity(
                            messageId = messageId,
                            contentType = "HTML",
                            content = draftDetails.body,
                            lastFetchedTimestamp = currentTime,
                            syncStatus = SyncStatus.PENDING_UPLOAD // Body also needs upload
                        )
                        messageBodyDao.insertOrUpdateMessageBody(messageBody) // Corrected call
                    }

                    val draftJson = Json.encodeToString(draftDetails)
                     // TODO: P2_WRITE - Replace with SyncEngine call
                    // syncEngine.enqueueActionUploadWorker(
                    //    accountId = account.id,
                    //    entityId = messageId,
                    //    actionType = ActionUploadWorker.ACTION_UPDATE_DRAFT,
                    //    payload = workDataOf("DRAFT_JSON" to draftJson)
                    // )
                    enqueueActionUploadWorker(
                        accountId = account.id,
                        entityId = messageId,
                        actionType = ActionUploadWorker.ACTION_UPDATE_DRAFT,
                        additionalPayload = mapOf("DRAFT_JSON" to draftJson)
                    )
                }
                val updatedMessage = messageDao.getMessageByIdSuspend(messageId)?.toDomainModel()?.copy(
                     body = draftDetails.body, // Add body from input
                     hasAttachments = draftDetails.attachments.isNotEmpty() // Explicitly set hasAttachments
                )
                if (updatedMessage != null) {
                    Timber.d("Draft updated: $messageId")
                    Result.success(updatedMessage)
                } else {
                     Timber.e("Failed to retrieve updated draft $messageId from DB after transaction.")
                     Result.failure(IllegalStateException("Failed to update draft locally"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to update draft message $messageId")
                Result.failure(e)
            }
        }
    }

    override fun searchMessages(
        accountId: String,
        query: String,
        folderId: String?
    ): Flow<List<Message>> {
        Timber.d(
            "searchMessages called for account: $accountId, query: '$query', folder: $folderId. Not implemented for full DB search."
        )
        // This implementation directly calls the API service for searching.
        // It does not currently incorporate local FTS or cache search results extensively beyond the flow.
        return channelFlow<List<Message>> {
            send(emptyList()) // Emit empty list initially or a loading state if preferred

            val account = accountRepository.getAccountByIdSuspend(accountId)
            if (account == null) {
                Log.e(TAG, "searchMessages: Account not found for ID: $accountId")
                send(emptyList()) // Keep emitting empty or send error state
                close()
                return@channelFlow
            }

            val providerType = account.providerType.uppercase()
            val apiService = mailApiServices[providerType]
            val errorMapper = errorMappers[providerType]

            if (apiService == null || errorMapper == null) {
                Log.e(TAG, "searchMessages: MailApiService or ErrorMapper not found for provider: $providerType")
                send(emptyList())
                close()
                return@channelFlow
            }

            try {
                // Max results for a search, can be configurable
                val searchMaxResults = 50
                val result = apiService.searchMessages(query, folderId, searchMaxResults)
                if (result.isSuccess) {
                    val messages = result.getOrThrow()
                    Log.d(TAG, "searchMessages: API call successful, found ${messages.size} messages.")
                    send(messages)
                } else {
                    val exception = result.exceptionOrNull()
                    val errorMessage = errorMapper.mapExceptionToErrorDetails(
                        exception ?: Throwable("Unknown error during API search for query: $query")
                    ).message
                    Log.e(TAG, "searchMessages: API call failed: $errorMessage", exception)
                    send(emptyList()) // Emit empty list on error
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMessage = errorMapper.mapExceptionToErrorDetails(e).message
                Log.e(TAG, "searchMessages: Exception during search: $errorMessage", e)
                send(emptyList()) // Emit empty list on exception
            }
            finally {
                close()
            }
        }.flowOn(ioDispatcher)
    }

    suspend fun retrySendMessage(accountId: String, messageId: String): Result<Unit> = withContext(ioDispatcher) {
        Timber.tag(TAG).d("retrySendMessage: Attempting to retry sending message $messageId for account $accountId")
        try {
            val account = accountRepository.getAccountByIdSuspend(accountId)
            if (account == null) {
                Timber.tag(TAG).e("retrySendMessage: Account $accountId not found.")
                return@withContext Result.failure(NoSuchElementException("Account $accountId not found"))
            }

            val messageEntity = messageDao.getMessageByIdSuspend(messageId)
            if (messageEntity == null || !messageEntity.isOutbox) {
                Timber.tag(TAG).e("retrySendMessage: Message $messageId not found or not in Outbox.")
                return@withContext Result.failure(IllegalStateException("Message $messageId not in Outbox or does not exist."))
            }

            val messageBodyEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            if (messageBodyEntity?.content == null) {
                Timber.tag(TAG).e("retrySendMessage: Message body for $messageId not found.")
                return@withContext Result.failure(IllegalStateException("Message body for $messageId not found."))
            }

            // Reconstruct MessageDraft. Note: Attachments are not re-added here as they are not part of MessageDraft directly.
            // The original send operation would have handled attachments if they were part of the draft process.
            // If attachments need to be re-processed, this logic would need to be more complex.
            val draftType = try {
                messageEntity.draftType?.let { DraftType.valueOf(it) } ?: DraftType.NEW
            } catch (e: IllegalArgumentException) {
                DraftType.NEW // Default if parsing fails
            }

            val reconstructedDraft = MessageDraft(
                originalMessageId = messageEntity.draftParentId,
                type = draftType,
                to = messageEntity.recipientAddresses ?: emptyList(),
                cc = emptyList(), 
                bcc = emptyList(), 
                subject = messageEntity.subject,
                body = messageBodyEntity.content,
                attachments = emptyList() 
            )

            appDatabase.withTransaction {
                messageDao.prepareForRetry(messageId)
            }

            val draftJson = Json.encodeToString(reconstructedDraft)
            // TODO: P2_WRITE - Replace with SyncEngine call for ActionUploadWorker
            // syncEngine.enqueueActionUploadWorker(
            //    accountId = account.id,
            //    entityId = messageId,
            //    actionType = ActionUploadWorker.ACTION_SEND_MESSAGE, // Or a specific RETRY_SEND action type
            //    payload = workDataOf("DRAFT_JSON" to draftJson, "SENT_FOLDER_ID" to "SENT")
            // )
             enqueueActionUploadWorker(
                accountId = account.id,
                entityId = messageId,
                actionType = ActionUploadWorker.ACTION_SEND_MESSAGE, // Could be a RETRY_SEND_MESSAGE
                additionalPayload = mapOf("DRAFT_JSON" to draftJson, "SENT_FOLDER_ID" to "SENT")
            )
            Timber.tag(TAG).i("retrySendMessage: Re-enqueued ActionUploadWorker for message $messageId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "retrySendMessage: Failed for message $messageId")
            Result.failure(e)
        }
    }

    private fun enqueueActionUploadWorker(
        accountId: String,
        entityId: String,
        actionType: String,
        additionalPayload: Map<String, Any?> = emptyMap()
    ) {
        Timber.d("$TAG: Request to enqueue ActionUploadWorker: Acc=$accountId, Entity=$entityId, Action=$actionType, PayloadKeys=${additionalPayload.keys}")
        // This method will conceptually call syncEngine.enqueueAction(...)
        // For now, it will just log as SyncEngine is not injected.
        // Example of how it would look with SyncEngine:
        // syncEngine.enqueueMessageAction(accountId, entityId, actionType, additionalPayload)
        Timber.i("$TAG: Conceptual: SyncEngine would be called here to enqueue ActionUploadWorker for $actionType on $entityId.")

        // Placeholder for direct WorkManager call IF SyncEngine is not used for this.
        // However, SyncEngine should be the entry point.
        // This direct call is temporary for testing worker creation if SyncEngine path is blocked.
        // val workData = workDataOf(
        //     ActionUploadWorker.KEY_ACCOUNT_ID to accountId,
        //     ActionUploadWorker.KEY_ENTITY_ID to entityId,
        //     ActionUploadWorker.KEY_ACTION_TYPE to actionType,
        //     *additionalPayload.toList().toTypedArray() // Spread operator for map to varargs pairs
        // )
        // val workRequest = OneTimeWorkRequestBuilder<ActionUploadWorker>()
        //     .setInputData(workData)
        //     .addTag("${actionType}_${accountId}_${entityId}")
        //     .build()
        // workManager.enqueue(workRequest)
        // Timber.i("$TAG: (Placeholder Direct Enqueue) ActionUploadWorker enqueued for $actionType on $entityId.")
    }


    // Helper method to fetch and store attachments from API
    private suspend fun fetchAndStoreAttachments(account: Account, messageId: String) {
        // This method is called by getMessageAttachments if local attachments are missing.
        // In Phase 2, this direct fetch should be replaced by enqueuing a worker via SyncEngine.
        // For now, it remains as a direct network call placeholder if getMessageAttachments logic still calls it.
        // However, getMessageAttachments was already refactored in P1 to only use DAO and add a TODO for SyncEngine.
        // So, this fetchAndStoreAttachments method might become dead code or be moved to a worker.
        // For safety, if it's still called, it will perform its original function.
        Timber.d("$TAG: fetchAndStoreAttachments for message $messageId. This should ideally be handled by a Sync Worker.")
        try {
            val providerType = account.providerType.uppercase()
            val mailApiService = mailApiServices[providerType]

            if (mailApiService != null) {
                val result = mailApiService.getMessageAttachments(messageId)
                result.onSuccess { attachments ->
                    val attachmentEntities = attachments.map { attachment ->
                        AttachmentEntity(
                            attachmentId = attachment.id,
                            messageId = messageId,
                            fileName = attachment.fileName,
                            size = attachment.size,
                            contentType = attachment.contentType,
                            contentId = attachment.contentId,
                            isInline = attachment.isInline,
                            isDownloaded = false,
                            localFilePath = null,
                            downloadTimestamp = null,
                            lastSyncError = null, 
                            syncStatus = SyncStatus.IDLE, 
                            lastSyncAttemptTimestamp = null,
                            lastSuccessfulSyncTimestamp = System.currentTimeMillis(),
                            isLocalOnly = false, 
                            needsFullSync = false
                        )
                    }
                    attachmentDao.insertAttachments(attachmentEntities)
                    Timber.d(
                        "Stored ${attachmentEntities.size} attachments for message $messageId"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch attachments for message $messageId")
        }
    }

    // Method not in MessageRepository interface, commenting out for now.
    /*
    suspend fun updateMessageBody(
        messageId: String,
        body: String,
        contentType: String
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val existingBody = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            val bodyEntity = MessageBodyEntity(
                messageId = messageId,
                content = body,
                contentType = contentType,
                lastFetchedTimestamp = System.currentTimeMillis(),
                // Assuming saving a body means it needs upload if it's part of a draft or being sent
                // If it's just caching a fetched body, status would be SYNCED.
                // This method context suggests it might be user-generated content.
                syncStatus = if (existingBody?.content == body) SyncStatus.SYNCED else SyncStatus.PENDING_UPLOAD,
                lastSyncError = null // Clear previous errors on new update
            )
            messageBodyDao.insertOrUpdateMessageBody(bodyEntity)
            Timber.d("Message body for $messageId updated locally.")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error updating message body for $messageId locally")
            Result.failure(Exception("Failed to update message body for $messageId: ${e.message}", e))
        }
    }
    */

    // Method not in MessageRepository interface, commenting out for now.
    /*
    suspend fun updateDraftState(messageId: String, isDraft: Boolean): Result<Unit> {
        return try {
            // If it's becoming a draft, it's PENDING_UPLOAD (or IDLE if just saving locally without immediate sync intention)
            // If it's no longer a draft (isDraft = false), it implies it was sent, deleted, or moved to outbox.
            // Those actions should set their own appropriate SyncStatus.
            // For a generic updateDraftState, if isDraft becomes false, IDLE seems safest, assuming other flows handle sync for send/delete.
            // If isDraft becomes true, PENDING_UPLOAD is a reasonable default for a save action.
            val status = if (isDraft) SyncStatus.PENDING_UPLOAD else SyncStatus.IDLE
            appDatabase.withTransaction {
                messageDao.updateDraftState(messageId, isDraft, syncStatus = status)
            }
            Timber.d("Draft state for message $messageId updated to isDraft=$isDraft locally with status $status.")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error updating draft state for message $messageId locally.")
            Result.failure(e)
        }
    }
    */

    // Method not in MessageRepository interface, commenting out for now.
    /*
    suspend fun moveToOutbox(messageId: String): Result<Unit> {
        return try {
            appDatabase.withTransaction {
                // The DAO moveToOutbox method defaults syncStatus to PENDING_UPLOAD, but we can be explicit.
                messageDao.moveToOutbox(messageId = messageId, syncStatus = SyncStatus.PENDING_UPLOAD)
            }
            Timber.d("Message $messageId moved to outbox locally, status PENDING_UPLOAD.")
            // Enqueue background work for sending from outbox
            enqueueSyncMessageStateWork(messageId, SyncMessageStateWorker.OP_SEND_MESSAGE)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error moving message $messageId to outbox locally.")
            Result.failure(e)
        }
    }
    */

    // Method not in MessageRepository interface, commenting out for now.
    /*
    suspend fun sendQueuedMessages(accountId: String) {
        // This method was mentioned in build errors as "overrides nothing".
        // Its implementation needs review if it's to be kept.
        // For now, commenting out to align with the MessageRepository interface.
        Timber.d("sendQueuedMessages for account $accountId - Placeholder, needs proper implementation if kept and added to interface")
        // Example: Fetch messages from outbox for the account
        // val outboxMessages = messageDao.getOutboxForAccount(accountId).firstOrNull()
        // outboxMessages?.forEach { messageEntity ->
            // val account = accountRepository.getAccountByIdSuspend(accountId)
            // if (account != null) {
                // val draftDetails = MessageDraft( ... ) // Reconstruct draft from messageEntity
                // sendMessage(draftDetails, account) // This would call the interface method
            // }
        // }
    }
    */
}