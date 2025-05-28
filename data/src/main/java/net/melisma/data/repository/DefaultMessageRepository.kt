package net.melisma.data.repository

import android.app.Activity
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import net.melisma.data.paging.MessageRemoteMediator
import net.melisma.data.worker.SyncMessageStateWorker
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DefaultMessageRepository @Inject constructor(
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val accountRepository: AccountRepository,
    private val messageDao: MessageDao,
    private val messageBodyDao: MessageBodyDao,
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
        Log.d(TAG, "Initializing DefaultMessageRepository with MessageDao.")
    }

    override fun observeMessagesForFolder(
        accountId: String,
        folderId: String
    ): Flow<List<Message>> {
        Log.d(TAG, "observeMessagesForFolder: accountId=$accountId, folderId=$folderId")
        return messageDao.getMessagesForFolder(accountId, folderId)
            .map { entities -> entities.map { it.toDomainModel() } }
    }

    @OptIn(ExperimentalPagingApi::class)
    override fun getMessagesPager(
        accountId: String,
        folderId: String,
        pagingConfig: PagingConfig
    ): Flow<PagingData<Message>> {
        Log.d(
            TAG,
            "getMessagesPager for accountId=$accountId, folderId=$folderId. PagingConfig: pageSize=${pagingConfig.pageSize}, prefetchDistance=${pagingConfig.prefetchDistance}, initialLoadSize=${pagingConfig.initialLoadSize}"
        )
        val account = runBlocking(ioDispatcher) {
            accountRepository.getAccountById(accountId).firstOrNull()
        }
        val providerType = account?.providerType?.uppercase()
        val apiServiceForMediator = mailApiServices[providerType]
            ?: mailApiServices.values.firstOrNull()
            ?: throw IllegalStateException("No MailApiService available for MessageRemoteMediator. Account: $accountId, Provider: $providerType")
        Log.d(
            TAG,
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
            .map { pagingDataEntity: PagingData<net.melisma.core_db.entity.MessageEntity> ->
                pagingDataEntity.map { messageEntity: net.melisma.core_db.entity.MessageEntity ->
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
        Log.d(
            TAG,
            "setTargetFolder: Account=${account?.username}($newAccountId), Folder=${folder?.displayName}($newFolderId). CurrentTarget: ${currentTargetAccount?.id}/$currentTargetFolderId. SyncJob Active: ${syncJob?.isActive}"
        )

        if (newAccountId == currentTargetAccount?.id && newFolderId == currentTargetFolderId) {
            Log.d(
                TAG,
                "setTargetFolder: Same target account/folder. No change to repository\'s internal target."
            )
            return
        }

        Timber.tag(TAG)
            .i("setTargetFolder: Target changed. Previous: ${currentTargetAccount?.id}/${currentTargetFolderId}, New: $newAccountId/$newFolderId. Clearing old sync job if any.")
        cancelAndClearSyncJob("Target folder changed in setTargetFolder. New: $newAccountId/$newFolderId")
        currentTargetAccount = account
        currentTargetFolderId = newFolderId

        if (newAccountId == null || newFolderId == null) {
            _messageSyncState.value = MessageSyncState.Idle
            Log.d(
                TAG,
                "setTargetFolder: Target cleared (account or folder is null). Sync state set to Idle."
            )
        } else {
            Log.d(
                TAG,
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
            Log.e(TAG, "syncMessagesForFolder: Account not found for id $accountId")
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
        Log.d(
            TAG,
            "[${account.id}/$folderId] syncMessagesForFolderInternal called. isRefresh: $isRefresh, explicitRequest: $explicitRequest, currentSyncJob active: ${syncJob?.isActive}. ViewModel should rely on $logPagingBehavior for list loading."
        )

        if (syncJob?.isActive == true && !isRefresh && !explicitRequest) {
            Log.d(
                TAG,
                "syncMessagesForFolderInternal: Sync job already active for $folderId and not an explicit forced refresh. Ignoring call."
            )
            return
        }

        val logPrefix = "[${account.id}/$folderId]"
        Timber.tag(TAG)
            .d("$logPrefix syncMessagesForFolderInternal for account type: ${account.providerType}")

        cancelAndClearSyncJob("$logPrefix Launching new message sync. Refresh: $isRefresh, Explicit: $explicitRequest")

        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (apiService == null || errorMapper == null) {
            val errorMsg =
                "Unsupported account type: $providerType or missing services for message sync."
            Log.e(TAG, errorMsg)
            _messageSyncState.value = MessageSyncState.SyncError(account.id, folderId, errorMsg)
            return
        }

        _messageSyncState.value = MessageSyncState.Syncing(account.id, folderId)

        syncJob = externalScope.launch(ioDispatcher) {
            Log.i(TAG, "$logPrefix Message sync job started. Refresh: $isRefresh")
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
                    Log.d(TAG, "$logPrefix Sync job finished.")
                } else {
                    Log.d(TAG, "$logPrefix Sync job finished (was cancelled or completed).")
                }
            }
        }
    }

    private fun cancelAndClearSyncJob(reason: String) {
        if (syncJob?.isActive == true) {
            Timber.tag(TAG).d("Cancelling active sync job: $reason")
            syncJob?.cancel(CancellationException(reason))
        }
        syncJob = null
    }

    override suspend fun getMessageDetails(
        messageId: String,
        accountId: String
    ): Flow<Message?> {
        Timber.tag(TAG).d("getMessageDetails: accountId=$accountId, messageId=$messageId")
        return getMessageWithBody(accountId, messageId)
    }

    private fun getMessageWithBody(accountId: String, messageId: String): Flow<Message?> =
        channelFlow {
            Log.d(TAG, "getMessageWithBody called for account $accountId, message $messageId")

            var messageEntity = messageDao.getMessageByIdNonFlow(messageId)
            var bodyEntity = messageBodyDao.getMessageBodyNonFlow(messageId)

            val initialDomainMessage = messageEntity?.toDomainModel()?.copy(
                body = bodyEntity?.content,
                bodyContentType = bodyEntity?.contentType
            )
            send(initialDomainMessage)

            if (messageEntity == null) {
                Log.w(
                    TAG,
                    "MessageEntity not found in DB for messageId: $messageId. Cannot proceed to fetch body."
                )
                return@channelFlow
            }

            if (initialDomainMessage?.body.isNullOrEmpty()) {
                Log.d(
                    TAG,
                    "Message body for $messageId is null/empty or initial message was null. Fetching from network."
                )
                val account = accountRepository.getAccountByIdNonFlow(accountId)
                if (account == null) {
                    Log.e(TAG, "Account not found for ID: $accountId. Cannot fetch message body.")
                    return@channelFlow
                }

                val providerType = account.providerType.uppercase()
                val apiService = mailApiServices[providerType]
                val errorMapper = errorMappers[providerType]

                if (apiService == null || errorMapper == null) {
                    Log.e(TAG, "No API service or error mapper for provider: $providerType")
                    return@channelFlow
                }

                try {
                    val result = apiService.getMessageContent(messageId)
                    if (result.isSuccess) {
                        val fetchedMessage = result.getOrThrow()
                        Log.d(
                            TAG,
                            "Successfully fetched message content for $messageId. Body: ${fetchedMessage.body != null}, ContentType: ${fetchedMessage.bodyContentType}"
                        )

                        if (fetchedMessage.body != null && fetchedMessage.bodyContentType != null) {
                            val newBodyEntity = MessageBodyEntity(
                                messageId = messageId,
                                content = fetchedMessage.body!!,
                                contentType = fetchedMessage.bodyContentType!!,
                                lastFetchedTimestamp = System.currentTimeMillis()
                            )
                            messageBodyDao.insertOrUpdateMessageBody(newBodyEntity)
                            Log.d(TAG, "Saved new message body to DB for $messageId")

                            val freshMessageEntity = messageDao.getMessageByIdNonFlow(messageId)
                            if (freshMessageEntity != null) {
                                val updatedMessageEntity = freshMessageEntity.copy(
                                    snippet = fetchedMessage.bodyPreview
                                        ?: freshMessageEntity.snippet
                                )
                                messageDao.insertOrUpdateMessages(listOf(updatedMessageEntity))
                                Log.d(
                                    TAG,
                                    "Updated MessageEntity for $messageId with new snippet if available."
                                )

                                val updatedDomainMessage =
                                    updatedMessageEntity.toDomainModel().copy(
                                        body = newBodyEntity.content,
                                        bodyContentType = newBodyEntity.contentType
                                    )
                                send(updatedDomainMessage)
                            } else {
                                Log.w(
                                    TAG,
                                    "Original MessageEntity for $messageId not found after body fetch. Sending API data directly."
                                )
                                send(fetchedMessage)
                            }
                        } else {
                            Log.w(
                                TAG,
                                "Fetched message content for $messageId, but body or contentType is null. Will use existing."
                            )
                            send(initialDomainMessage)
                        }
                    } else {
                        val exception = result.exceptionOrNull()
                        val errorMessage = errorMapper.mapExceptionToErrorDetails(
                            exception ?: Throwable("Unknown error fetching message content")
                        ).message
                        Log.e(
                            TAG,
                            "Error fetching message content for $messageId: $errorMessage",
                            exception
                        )
                        send(initialDomainMessage)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(
                        TAG,
                        "Exception during message content fetch for $messageId: ${e.message}",
                        e
                    )
                    errorMapper.mapExceptionToErrorDetails(e).message
                    send(initialDomainMessage)
                }
            } else {
                Log.d(
                    TAG,
                    "Message body already present in DB for $messageId or initial message was null. No network fetch needed."
                )
            }
    }

    override suspend fun markMessageRead(
        account: Account,
        messageId: String,
        isRead: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.tag(TAG)
            .d("markMessageRead: msgId=$messageId, isRead=$isRead, account=${account.id}")
        try {
            appDatabase.withTransaction {
                messageDao.updateReadState(messageId, isRead, needsSync = true)
            }
            Timber.tag(TAG)
                .i("Optimistically marked message $messageId as isRead=$isRead, needsSync=true")
            enqueueSyncMessageStateWorker(
                accountId = account.id,
                messageId = messageId,
                operationType = SyncMessageStateWorker.OP_MARK_READ,
                isRead = isRead
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in markMessageRead for $messageId")
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
        Timber.tag(TAG)
            .d("starMessage: msgId=$messageId, isStarred=$isStarred, account=${account.id}")
        try {
            appDatabase.withTransaction {
                messageDao.updateStarredState(messageId, isStarred, needsSync = true)
            }
            Timber.tag(TAG)
                .i("Optimistically starred message $messageId as isStarred=$isStarred, needsSync=true")
            enqueueSyncMessageStateWorker(
                accountId = account.id,
                messageId = messageId,
                operationType = SyncMessageStateWorker.OP_STAR_MESSAGE,
                isStarred = isStarred
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in starMessage for $messageId")
            val errorDetails =
                errorMappers[account.providerType.uppercase()]?.mapExceptionToErrorDetails(e)
            Result.failure(Exception(errorDetails?.message ?: e.message, e))
        }
    }

    override suspend fun deleteMessage(
        account: Account,
        messageId: String
    ): Result<Unit> {
        Timber.tag(TAG)
            .w("deleteMessage called for msg $messageId in account ${account.id}. NOT IMPLEMENTED.")
        return Result.failure(NotImplementedError("deleteMessage not implemented"))
    }

    override suspend fun moveMessage(
        account: Account,
        messageId: String,
        newFolderId: String
    ): Result<Unit> {
        Timber.tag(TAG)
            .w("moveMessage called for msg $messageId to folder $newFolderId in account ${account.id}. NOT IMPLEMENTED.")
        return Result.failure(NotImplementedError("moveMessage not implemented"))
    }

    override suspend fun sendMessage(draft: MessageDraft, account: Account): Result<String> {
        return Result.failure(NotImplementedError("sendMessage not part of this repository's direct caching scope"))
    }

    override suspend fun getMessageAttachments(
        accountId: String,
        messageId: String
    ): Flow<List<Attachment>> {
        Log.d(TAG, "getMessageAttachments called for accountId: $accountId, messageId: $messageId")
        Log.w(TAG, "getMessageAttachments: Not fully implemented. Returning empty.")
        return flowOf(emptyList())
    }

    override suspend fun downloadAttachment(
        accountId: String,
        messageId: String,
        attachmentId: String
    ): Result<ByteArray> {
        TODO("Not yet implemented. Fetch attachment binary data.")
    }

    override suspend fun createDraftMessage(
        accountId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        Log.d(
            TAG,
            "createDraftMessage called for accountId: $accountId. Not implemented with DB sync."
        )
        return Result.failure(NotImplementedError("createDraftMessage not yet implemented with DB sync"))
    }

    override suspend fun updateDraftMessage(
        accountId: String,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        Log.d(
            TAG,
            "updateDraftMessage called for accountId: $accountId, messageId: $messageId. Not implemented with DB sync."
        )
        return Result.failure(NotImplementedError("updateDraftMessage not yet implemented with DB sync"))
    }

    override fun searchMessages(
        accountId: String,
        query: String,
        folderId: String?
    ): Flow<List<Message>> {
        Log.d(
            TAG,
            "searchMessages called for account: $accountId, query: '$query', folder: $folderId. Not implemented for full DB search."
        )
        return flowOf(emptyList())
    }

    private fun enqueueSyncMessageStateWorker(
        accountId: String,
        messageId: String,
        operationType: String,
        isRead: Boolean? = null,
        isStarred: Boolean? = null
    ) {
        Timber.tag(TAG)
            .d("Enqueueing SyncMessageStateWorker: Acc=$accountId, Msg=$messageId, Op=$operationType, Read=$isRead, Starred=$isStarred")

        val inputDataBuilder = Data.Builder()
            .putString(SyncMessageStateWorker.KEY_ACCOUNT_ID, accountId)
            .putString(SyncMessageStateWorker.KEY_MESSAGE_ID, messageId)
            .putString(SyncMessageStateWorker.KEY_OPERATION_TYPE, operationType)

        isRead?.let { inputDataBuilder.putBoolean(SyncMessageStateWorker.KEY_IS_READ, it) }
        isStarred?.let { inputDataBuilder.putBoolean(SyncMessageStateWorker.KEY_IS_STARRED, it) }

        val workRequest = OneTimeWorkRequestBuilder<SyncMessageStateWorker>()
            .setInputData(inputDataBuilder.build())
            .addTag("SyncMessageState_${accountId}_${messageId}")
            .build()

        workManager.enqueue(workRequest)
        Timber.tag(TAG)
            .i("SyncMessageStateWorker enqueued for message $messageId, operation $operationType.")
    }
}