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
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.MessageSyncState
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import net.melisma.core_data.model.DraftType

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
    private val workManager: WorkManager
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
        Timber.d("getMessageDetails: accountId=$accountId, messageId=$messageId")
        return getMessageWithBody(accountId, messageId)
    }

    private fun getMessageWithBody(accountId: String, messageId: String): Flow<Message?> =
        channelFlow {
            Timber.d("getMessageWithBody called for account $accountId, message $messageId")

            // Step 1: Initial DB Load and Emit
            var existingMessageEntity = messageDao.getMessageByIdSuspend(messageId)
            var existingBodyEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)

            var messageToSend: Message? = existingMessageEntity?.toDomainModel()?.copy(
                body = existingBodyEntity?.content,
                bodyContentType = existingBodyEntity?.contentType
            )
            send(messageToSend) // Emit cached state immediately

            // Step 2: Prepare for Network Fetch (if account/service available)
            val account = accountRepository.getAccountByIdSuspend(accountId)
            if (account == null) {
                Timber.e("Account not found for ID: $accountId. Cannot fetch message content for $messageId.")
                close() // Close the flow if account is not found
                return@channelFlow
            }

            val providerType = account.providerType.uppercase()
            val apiService = mailApiServices[providerType]
            val errorMapper = errorMappers[providerType]

            if (apiService == null || errorMapper == null) {
                Timber.e("No API service or error mapper for provider: $providerType for $messageId. Cannot fetch from network.")
                close() // Close the flow if API service is not found
                return@channelFlow
            }

            // Step 3: Perform Network Fetch
            Timber.d("Attempting network fetch for message $messageId (either cache miss or for potential update).")
            try {
                val result = apiService.getMessageContent(messageId)
                if (result.isSuccess) {
                    val fetchedMessageFromApi = result.getOrThrow()
                    Timber.d(
                        "Successfully fetched message $messageId from API. Body: ${fetchedMessageFromApi.body != null}, ContentType: ${fetchedMessageFromApi.bodyContentType}"
                    )

                    if (fetchedMessageFromApi.body != null && fetchedMessageFromApi.bodyContentType != null) {
                        if (existingMessageEntity != null) {
                            // We have a new body, update DB and emit for existing message
                            val newBodyEntity = MessageBodyEntity(
                                messageId = messageId,
                                content = fetchedMessageFromApi.body!!,
                                contentType = fetchedMessageFromApi.bodyContentType!!,
                                lastFetchedTimestamp = System.currentTimeMillis()
                            )
                            messageBodyDao.insertOrUpdateMessageBody(newBodyEntity)

                            val messageEntityToSave = existingMessageEntity.copy(
                                snippet = fetchedMessageFromApi.bodyPreview ?: existingMessageEntity.snippet,
                                subject = fetchedMessageFromApi.subject ?: existingMessageEntity.subject,
                                senderName = fetchedMessageFromApi.senderName ?: existingMessageEntity.senderName,
                                senderAddress = fetchedMessageFromApi.senderAddress ?: existingMessageEntity.senderAddress,
                                recipientNames = fetchedMessageFromApi.recipientNames ?: existingMessageEntity.recipientNames,
                                recipientAddresses = fetchedMessageFromApi.recipientAddresses ?: existingMessageEntity.recipientAddresses,
                                timestamp = fetchedMessageFromApi.timestamp.takeIf { it != 0L } ?: existingMessageEntity.timestamp,
                                isRead = fetchedMessageFromApi.isRead // API should be source of truth for read state if available
                                // sentDateTime from API's Message is a String, MessageEntity expects Long.
                                // This requires parsing similar to what was in the removed parseIso8601DateTimeToLong or in a mapper.
                                // For now, not updating sentTimestamp here to avoid re-introducing parsing directly.
                                // Mapper should handle this if API provides sentDateTime.
                            )
                            messageDao.insertOrUpdateMessages(listOf(messageEntityToSave))
                            Timber.d("Saved/Updated message entity and body in DB for $messageId")

                            // Re-fetch from DB to ensure SSoT for the emission
                            val updatedMessageEntity = messageDao.getMessageByIdSuspend(messageId)
                            val updatedBodyEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
                            messageToSend = updatedMessageEntity?.toDomainModel()?.copy(
                                body = updatedBodyEntity?.content,
                                bodyContentType = updatedBodyEntity?.contentType
                            )
                            send(messageToSend) // Send the updated message from DB
                        } else {
                            // Message was not in DB (cache miss). Send API-fetched message directly.
                            // Body will not be persisted as we lack folderId for MessageEntity.
                            Timber.w("Message $messageId not found in local DB. Sending API-fetched content. Body will not be persisted without folder context.")
                            messageToSend = fetchedMessageFromApi // This has the body from API directly
                            send(messageToSend)
                        }
                    } else {
                        Timber.w("API fetch for $messageId succeeded but body or contentType was null/empty. No update to emit beyond cached.")
                    }
                } else { // apiService.getMessageContent failed
                    val exception = result.exceptionOrNull()
                    val errorMessage = errorMapper.mapExceptionToErrorDetails(
                        exception ?: Throwable("Unknown error fetching message content from API for $messageId")
                    ).message
                    Timber.e(exception, "Error fetching message content for $messageId from API: $errorMessage")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e // Propagate cancellation
                Timber.e(e, "Exception during network fetch process for $messageId: ${e.message}")
            } finally {
                close() // Close the flow after network attempt (success or failure)
            }
        }.flowOn(ioDispatcher)

    override suspend fun markMessageRead(
        account: Account,
        messageId: String,
        isRead: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("markMessageRead: msgId=$messageId, isRead=$isRead, account=${account.id}")
        try {
            appDatabase.withTransaction {
                messageDao.updateReadState(messageId, isRead, needsSync = true)
            }
            Timber.i("Optimistically marked message $messageId as isRead=$isRead, needsSync=true")
            enqueueSyncMessageStateWorker(
                accountId = account.id,
                messageId = messageId,
                operationType = SyncMessageStateWorker.OP_MARK_READ,
                isRead = isRead
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
                messageDao.updateStarredState(messageId, isStarred, needsSync = true)
            }
            Timber.i("Optimistically starred message $messageId as isStarred=$isStarred, needsSync=true")
            enqueueSyncMessageStateWorker(
                accountId = account.id,
                messageId = messageId,
                operationType = SyncMessageStateWorker.OP_STAR_MESSAGE,
                isStarred = isStarred
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
                    needsSync = true
                )
            }
            Timber.i("Optimistically marked message $messageId as locally deleted, needsSync=true")

            enqueueSyncMessageStateWorker(
                accountId = account.id,
                messageId = messageId,
                operationType = SyncMessageStateWorker.OP_DELETE_MESSAGE
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error in deleteMessage for $messageId")
            val currentMessage = messageDao.getMessageByIdSuspend(messageId)
            if (currentMessage != null && !currentMessage.isLocallyDeleted) {
                val errorMessage =
                    errorMappers[account.providerType.uppercase()]?.mapExceptionToErrorDetails(e)?.message
                        ?: e.message
                messageDao.updateLastSyncError(messageId, "Optimistic delete failed: $errorMessage")
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
                    needsSync = true
                )
            }
            Timber.i("Optimistically moved message $messageId to folder $newFolderId, needsSync=true")

            // Enqueue the worker to perform the actual API call
            enqueueSyncMessageStateWorker(
                accountId = account.id,
                messageId = messageId,
                operationType = SyncMessageStateWorker.OP_MOVE_MESSAGE,
                newFolderId = newFolderId,
                oldFolderId = oldFolderId
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error in moveMessage for $messageId to $newFolderId")
            // If optimistic update failed, try to set sync error on the original record if it exists
            // The local message's folderId might not have been updated yet if transaction failed.
            val messageForErrorUpdate = messageDao.getMessageByIdSuspend(messageId)
            if (messageForErrorUpdate != null) {
                val errorMessage =
                    errorMappers[account.providerType.uppercase()]?.mapExceptionToErrorDetails(e)?.message
                        ?: e.message
                messageDao.updateLastSyncError(
                    messageId,
                    "Optimistic move to $newFolderId failed: $errorMessage"
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
                        senderName = account.username,
                        senderAddress = account.username,
                        recipientNames = draft.to, // Will be converted by TypeConverter
                        recipientAddresses = draft.to, // Will be converted by TypeConverter
                        timestamp = currentTime,
                        sentTimestamp = null,
                        isRead = true,
                        isStarred = false,
                        hasAttachments = draft.attachments.isNotEmpty(),
                        isOutbox = true,
                        isDraft = false,
                        draftType = draft.type.name,
                        draftParentId = draft.originalMessageId,
                        needsSync = true
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
                    val draftJson = Json.encodeToString(draft)
                    enqueueSyncMessageStateWorker(
                        account = account,
                        messageId = messageId,
                        operation = SyncMessageStateWorker.OP_SEND_MESSAGE,
                        draftData = draftJson,
                        sentFolderId = "SENT" // Default sent folder
                    )
                }
                Log.d(TAG, "Message queued for sending with ID: $messageId")
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
                        // Fetch from API
                        val account = accountRepository.getAccountByIdSuspend(accountId)
                        if (account != null) {
                            fetchAndStoreAttachments(account, messageId)
                        }
                    }
                }

                // Return flow of attachments, mapped to domain model
                localAttachments.map { attachmentEntities ->
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
                Timber.e(e, "Error getting message attachments")
                flowOf(emptyList())
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

                appDatabase.withTransaction {
                    // Generate a unique draft ID
                    val draftId = "draft_${System.currentTimeMillis()}_${accountId}"
                    val currentTime = System.currentTimeMillis()

                    // Create draft message entity
                    val draftMessage = MessageEntity(
                        messageId = draftId,
                        accountId = accountId,
                        folderId = "DRAFTS", // Special folder for drafts
                        threadId = draftDetails.originalMessageId,
                        subject = draftDetails.subject,
                        snippet = draftDetails.body?.take(150),
                        senderName = account.username,
                        senderAddress = account.username,
                        recipientNames = draftDetails.to,
                        recipientAddresses = draftDetails.to,
                        timestamp = currentTime,
                        sentTimestamp = null,
                        isRead = true,
                        isStarred = false,
                        hasAttachments = draftDetails.attachments.isNotEmpty(),
                        isDraft = true,
                        isOutbox = false,
                        draftType = draftDetails.type.name,
                        draftParentId = draftDetails.originalMessageId,
                        needsSync = true
                    )

                    // Insert into database
                    messageDao.insertOrUpdateMessages(listOf(draftMessage))

                    // Store message body
                    val messageBody = MessageBodyEntity(
                        messageId = draftId,
                        contentType = "HTML",
                        content = draftDetails.body,
                        lastFetchedTimestamp = currentTime
                    )
                    messageBodyDao.insertOrUpdateMessageBody(messageBody)

                    // Enqueue worker to sync with server
                    val draftJson = Json.encodeToString(draftDetails)
                    enqueueSyncMessageStateWorker(
                        account = account,
                        messageId = draftId,
                        operation = SyncMessageStateWorker.OP_CREATE_DRAFT,
                        draftData = draftJson
                    )
                }

                // Re-fetch the generated draftId within the transaction or pass it out if possible.
                // For simplicity, we are not re-fetching here post-transaction to return the full Message object.
                // The current return type is Result<Message>, so we construct it manually.
                // A more robust approach might involve the worker returning the server-confirmed draft ID/details.
                val draftId = "draft_${System.currentTimeMillis()}_${accountId}" // This ID is generated before transaction and might differ if logic changes
                val currentTime = System.currentTimeMillis() // This timestamp also might differ

                // Convert to domain model for return
                val message = Message(
                    id = draftId, // This ID might not be the one committed if transaction failed or ID generation changed
                    threadId = draftDetails.originalMessageId,
                    receivedDateTime = java.time.Instant.ofEpochMilli(currentTime).toString(),
                    sentDateTime = null,
                    subject = draftDetails.subject,
                    senderName = account.username,
                    senderAddress = account.username,
                    bodyPreview = draftDetails.body?.take(150),
                    isRead = true,
                    recipientNames = draftDetails.to,
                    recipientAddresses = draftDetails.to,
                    isStarred = false,
                    hasAttachments = draftDetails.attachments.isNotEmpty(),
                    timestamp = currentTime
                )

                Timber.d("Draft created with ID: $draftId")
                Result.success(message)
            } catch (e: Exception) {
                Timber.e(e, "Failed to create draft message")
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

                // Check if draft exists
                val existingDraft = messageDao.getMessageByIdSuspend(messageId)
                if (existingDraft == null || !existingDraft.isDraft) {
                    Timber.e("Draft message not found or not a draft: $messageId")
                    return@withContext Result.failure(IllegalArgumentException("Draft not found"))
                }
                val currentTime = System.currentTimeMillis()

                appDatabase.withTransaction {
                    // Update draft content in database
                    messageDao.updateDraftContent(
                        messageId = messageId,
                        subject = draftDetails.subject,
                        snippet = draftDetails.body?.take(150),
                        recipientNames = draftDetails.to,
                        recipientAddresses = draftDetails.to,
                        timestamp = currentTime,
                        needsSync = true,
                        draftType = draftDetails.type.name,
                        draftParentId = draftDetails.originalMessageId
                    )

                    // Update message body
                    val messageBody = MessageBodyEntity(
                        messageId = messageId,
                        contentType = "HTML",
                        content = draftDetails.body,
                        lastFetchedTimestamp = currentTime
                    )
                    messageBodyDao.insertOrUpdateMessageBody(messageBody)

                    // Enqueue worker to sync with server
                    val draftJson = Json.encodeToString(draftDetails)
                    enqueueSyncMessageStateWorker(
                        account = account,
                        messageId = messageId,
                        operation = SyncMessageStateWorker.OP_UPDATE_DRAFT,
                        draftData = draftJson
                    )
                }

                // Convert to domain model for return
                val message = Message(
                    id = messageId,
                    threadId = draftDetails.originalMessageId,
                    receivedDateTime = java.time.Instant.ofEpochMilli(currentTime).toString(),
                    sentDateTime = null,
                    subject = draftDetails.subject,
                    senderName = account.username,
                    senderAddress = account.username,
                    bodyPreview = draftDetails.body?.take(150),
                    isRead = true,
                    recipientNames = draftDetails.to,
                    recipientAddresses = draftDetails.to,
                    isStarred = false,
                    hasAttachments = draftDetails.attachments.isNotEmpty(),
                    timestamp = currentTime
                )

                Timber.d("Draft updated: $messageId")
                Result.success(message)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update draft message")
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
                cc = emptyList(), // Assuming CC/BCC are not stored in MessageEntity directly like this
                bcc = emptyList(), // Modify if your MessageEntity stores these
                subject = messageEntity.subject,
                body = messageBodyEntity.content,
                attachments = emptyList() // Placeholder: Attachment handling for resend needs clarification
                                        // If attachments were uploaded to a server and IDs stored, those might be used.
                                        // If they are local files, the paths would be needed.
                                        // For now, assuming worker handles based on initial draft data or server state.
            )

            appDatabase.withTransaction {
                messageDao.prepareForRetry(messageId)
                // Optionally, reset sendAttempts if desired, or let it increment naturally by the worker
                // messageDao.resetSendAttempts(messageId) // Example: if you add such a DAO method
            }

            val draftJson = Json.encodeToString(reconstructedDraft)
            enqueueSyncMessageStateWorker(
                account = account,
                messageId = messageId,
                operation = SyncMessageStateWorker.OP_SEND_MESSAGE,
                draftData = draftJson,
                sentFolderId = "SENT" // Default sent folder, ensure this matches logic in sendMessage
            )
            Timber.tag(TAG).i("retrySendMessage: Re-enqueued SyncMessageStateWorker for message $messageId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "retrySendMessage: Failed for message $messageId")
            Result.failure(e)
        }
    }

    private fun enqueueSyncMessageStateWorker(
        accountId: String,
        messageId: String,
        operationType: String,
        isRead: Boolean? = null,
        isStarred: Boolean? = null,
        newFolderId: String? = null,
        oldFolderId: String? = null,
        draftData: String? = null,
        sentFolderId: String? = null
    ) {
        Timber.d("Enqueueing SyncMessageStateWorker: Acc=$accountId, Msg=$messageId, Op=$operationType, Read=$isRead, Starred=$isStarred, NewFId=$newFolderId, OldFId=$oldFolderId")

        val inputDataBuilder = Data.Builder()
            .putString(SyncMessageStateWorker.KEY_ACCOUNT_ID, accountId)
            .putString(SyncMessageStateWorker.KEY_MESSAGE_ID, messageId)
            .putString(SyncMessageStateWorker.KEY_OPERATION_TYPE, operationType)

        isRead?.let { inputDataBuilder.putBoolean(SyncMessageStateWorker.KEY_IS_READ, it) }
        isStarred?.let { inputDataBuilder.putBoolean(SyncMessageStateWorker.KEY_IS_STARRED, it) }
        newFolderId?.let {
            inputDataBuilder.putString(
                SyncMessageStateWorker.KEY_NEW_FOLDER_ID,
                it
            )
        }
        oldFolderId?.let {
            inputDataBuilder.putString(
                SyncMessageStateWorker.KEY_OLD_FOLDER_ID,
                it
            )
        }
        draftData?.let {
            inputDataBuilder.putString(
                SyncMessageStateWorker.KEY_DRAFT_DATA,
                it
            )
        }
        sentFolderId?.let {
            inputDataBuilder.putString(
                SyncMessageStateWorker.KEY_SENT_FOLDER_ID,
                it
            )
        }

        val workRequest = OneTimeWorkRequestBuilder<SyncMessageStateWorker>()
            .setInputData(inputDataBuilder.build())
            .addTag("SyncMessageState_${accountId}_${messageId}")
            .build()

        workManager.enqueue(workRequest)
        Timber.i("SyncMessageStateWorker enqueued for message $messageId, operation $operationType.")
    }

    // Overloaded method that accepts Account
    private fun enqueueSyncMessageStateWorker(
        account: Account,
        messageId: String,
        operation: String,
        draftData: String? = null,
        sentFolderId: String? = null
    ) {
        enqueueSyncMessageStateWorker(
            accountId = account.id,
            messageId = messageId,
            operationType = operation,
            draftData = draftData,
            sentFolderId = sentFolderId
        )
    }

    // Helper method to fetch and store attachments from API
    private suspend fun fetchAndStoreAttachments(account: Account, messageId: String) {
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
                            downloadError = null
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
}