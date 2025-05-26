package net.melisma.data.repository

import android.app.Activity
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import net.melisma.core_db.dao.MessageDao
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import net.melisma.data.paging.MessageRemoteMediator
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
    private val appDatabase: AppDatabase
) : MessageRepository {

    private val TAG = "DefaultMessageRepo"
    private val _messageSyncState = MutableStateFlow<MessageSyncState>(MessageSyncState.Idle)
    override val messageSyncState: StateFlow<MessageSyncState> = _messageSyncState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolderId: String? = null
    private var syncJob: Job? = null

    init {
        Log.d(
            TAG, "Initializing DefaultMessageRepository with MessageDao."
        )
    }

    override fun observeMessagesForFolder(
        accountId: String,
        folderId: String
    ): Flow<List<Message>> {
        Log.d(TAG, "observeMessagesForFolder: accountId=$accountId, folderId=$folderId")
        return messageDao.getMessagesForFolder(accountId, folderId)
            .map { entities -> entities.map { it.toDomainModel() } }
    }

    override fun getMessagesPager(
        accountId: String,
        folderId: String,
        pagingConfig: PagingConfig
    ): Flow<PagingData<Message>> {
        Log.d(TAG, "getMessagesPager for accountId=$accountId, folderId=$folderId")
        val apiServiceForMediator = mailApiServices.values.firstOrNull()
            ?: throw IllegalStateException("No MailApiService available for MessageRemoteMediator")

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
        ).flow.map { pagingDataEntity ->
            pagingDataEntity.map { messageEntity ->
                messageEntity.toDomainModel()
            }
        }
    }

    override suspend fun setTargetFolder(
        account: Account?,
        folder: MailFolder?
    ) {
        Log.d(
            TAG,
            "setTargetFolder: Account=${account?.username}, Folder=${folder?.displayName}, SyncJob Active: ${syncJob?.isActive}"
        )

        val newAccountId = account?.id
        val newFolderId = folder?.id

        if (newAccountId == currentTargetAccount?.id && newFolderId == currentTargetFolderId && _messageSyncState.value !is MessageSyncState.Idle && _messageSyncState.value !is MessageSyncState.SyncError) {
            Log.d(
                TAG,
                "setTargetFolder: Same target and not in idle/error state. Current sync state: ${_messageSyncState.value}"
            )
            return
        }

        cancelAndClearSyncJob("New target folder set: Account=${newAccountId}, Folder=${newFolderId}")
        currentTargetAccount = account
        currentTargetFolderId = newFolderId

        if (account == null || newFolderId == null) {
            _messageSyncState.value = MessageSyncState.Idle
            Log.d(TAG, "setTargetFolder: Cleared target, sync state set to Idle.")
            return
        }

        syncMessagesForFolderInternal(account, newFolderId, isRefresh = false)
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
        if (syncJob?.isActive == true && !isRefresh) {
            Log.d(
                TAG,
                "syncMessagesForFolderInternal: Sync job already active for $folderId. Ignoring."
            )
            return
        }
        cancelAndClearSyncJob("Launching new message sync for ${account.id} / $folderId. Refresh: $isRefresh")

        Log.d(
            TAG,
            "[${account.id}/$folderId] syncMessagesForFolderInternal for account type: ${account.providerType}"
        )

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
            Log.i(TAG, "[${account.id}/$folderId] Message sync job started. Refresh: $isRefresh")
            try {
                val messagesResult = apiService.getMessagesForFolder(
                    folderId = folderId,
                    maxResults = 100
                )
                ensureActive()

                if (messagesResult.isSuccess) {
                    val apiMessages = messagesResult.getOrThrow()
                    Log.i(
                        TAG,
                        "[${account.id}/$folderId] Successfully fetched ${apiMessages.size} messages from API."
                    )

                    val messageEntities = apiMessages.map { it.toEntity(account.id, folderId) }
                    messageDao.clearAndInsertMessagesForFolder(
                        account.id,
                        folderId,
                        messageEntities
                    )
                    Log.i(
                        TAG,
                        "[${account.id}/$folderId] Saved ${messageEntities.size} messages to DB."
                    )

                    _messageSyncState.value = MessageSyncState.SyncSuccess(account.id, folderId)
                } else {
                    val exception = messagesResult.exceptionOrNull()
                    val errorDetails = errorMapper.mapExceptionToErrorDetails(exception)
                    Log.e(
                        TAG,
                        "[${account.id}/$folderId] Error syncing messages: ${errorDetails.message}",
                        exception
                    )
                    _messageSyncState.value =
                        MessageSyncState.SyncError(account.id, folderId, errorDetails.message)
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "[${account.id}/$folderId] Message sync job cancelled.", e)
                if (isActive && currentTargetAccount?.id == account.id && currentTargetFolderId == folderId && _messageSyncState.value is MessageSyncState.Syncing) {
                    _messageSyncState.value = MessageSyncState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "[${account.id}/$folderId] Exception during message sync process", e)
                if (isActive && currentTargetAccount?.id == account.id && currentTargetFolderId == folderId) {
                    val details = errorMapper.mapExceptionToErrorDetails(e)
                    _messageSyncState.value =
                        MessageSyncState.SyncError(account.id, folderId, details.message)
                }
            } finally {
                if (syncJob == coroutineContext[Job]) {
                    syncJob = null
                }
            }
        }
    }

    override suspend fun refreshMessages(activity: Activity?) {
        val account = currentTargetAccount
        val folderId = currentTargetFolderId
        if (account == null || folderId == null) {
            Log.w(TAG, "refreshMessages: No target account or folderId set. Skipping.")
            return
        }
        Log.d(TAG, "refreshMessages called for folderId: $folderId, Account: ${account.username}")
        if (_messageSyncState.value is MessageSyncState.Syncing && (_messageSyncState.value as MessageSyncState.Syncing).folderId == folderId) {
            Log.d(TAG, "refreshMessages: Already syncing this folder. Skipping duplicate refresh.")
            return
        }
        syncMessagesForFolderInternal(account, folderId, isRefresh = true, explicitRequest = true)
    }

    private fun cancelAndClearSyncJob(reason: String) {
        syncJob?.let {
            if (it.isActive) {
                Log.d(
                    TAG,
                    "Cancelling previous message sync job. Reason: $reason. Hash: ${it.hashCode()}"
                )
                it.cancel(CancellationException("Sync job cancelled: $reason"))
            }
        }
        syncJob = null
        Log.d(TAG, "cancelAndClearSyncJob: Cleared syncJob. Reason: $reason")
    }

    override suspend fun getMessageDetails(messageId: String, accountId: String): Flow<Message?> {
        Log.d(TAG, "getMessageDetails (API direct): $messageId, accountId: $accountId")
        val account = accountRepository.getAccountById(accountId).firstOrNull()
        if (account == null) {
            Log.e(TAG, "getMessageDetails: Account not found for id $accountId")
            return flowOf(null)
        }
        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        if (apiService == null) {
            Log.e(
                TAG,
                "getMessageDetails: ApiService not found for provider ${account.providerType}"
            )
            return flowOf(null)
        }
        return apiService.getMessageDetails(messageId)
    }

    override suspend fun markMessageRead(
        account: Account,
        messageId: String,
        isRead: Boolean
    ): Result<Unit> {
        Log.d(
            TAG,
            "markMessageRead for id: $messageId, account: ${account.username}, isRead: $isRead. DB first."
        )
        try {
            val messageEntity = messageDao.getMessageById(messageId).firstOrNull()
            if (messageEntity != null) {
                // Update DB first
                messageDao.insertOrUpdateMessages(
                    listOf(
                        messageEntity.copy(
                            isRead = isRead,
                            needsSync = true
                        )
                    )
                )
                Log.d(TAG, "Updated read status in DB for $messageId to $isRead, needsSync=true")

                // TODO: Enqueue WorkManager task to sync this change to the backend.
                // For now, still attempt direct API call after DB update.

                val apiService = mailApiServices[account.providerType.uppercase()]
                    ?: return Result.failure(NotImplementedError("MailApiService not found for ${account.providerType} to sync markMessageRead"))

                val apiResult = apiService.markMessageRead(messageId, isRead)
                if (apiResult.isSuccess) {
                    // If API call is successful, update needsSync to false
                    messageDao.getMessageById(messageId).firstOrNull()?.let { updatedEntity ->
                        messageDao.insertOrUpdateMessages(listOf(updatedEntity.copy(needsSync = false)))
                        Log.d(
                            TAG,
                            "markMessageRead: API sync successful for $messageId, needsSync set to false."
                        )
                    }
                    return Result.success(Unit)
                } else {
                    Log.w(
                        TAG,
                        "markMessageRead: API sync failed for $messageId. DB change will be synced later by worker. Error: ${apiResult.exceptionOrNull()?.message}"
                    )
                    // DB already updated with needsSync = true, so worker should pick it up.
                    return Result.failure(
                        apiResult.exceptionOrNull()
                            ?: Exception("API error during markMessageRead sync")
                    )
                }
            } else {
                Log.w(
                    TAG,
                    "markMessageRead: Message $messageId not found in DB to update read status."
                )
                return Result.failure(NoSuchElementException("Message $messageId not found in DB"))
            }
        } catch (e: Exception) {
            Log.e(e, "markMessageRead: Failed to update message $messageId read status in DB.")
            return Result.failure(e)
        }
    }

    override suspend fun deleteMessage(account: Account, messageId: String): Result<Unit> {
        Log.d(TAG, "deleteMessage for id: $messageId, account: ${account.username}")
        return Result.failure(NotImplementedError("deleteMessage not yet implemented with DB sync"))
    }

    override suspend fun moveMessage(
        account: Account,
        messageId: String,
        newFolderId: String
    ): Result<Unit> {
        Log.d(TAG, "moveMessage for id: $messageId to $newFolderId")
        return Result.failure(NotImplementedError("moveMessage not yet implemented with DB sync"))
    }

    override suspend fun sendMessage(draft: MessageDraft, account: Account): Result<String> {
        return Result.failure(NotImplementedError("sendMessage not part of this repository's direct caching scope"))
    }

    override suspend fun getMessageAttachments(
        accountId: String,
        messageId: String
    ): Flow<List<Attachment>> {
        Log.d(TAG, "getMessageAttachments called for accountId: $accountId, messageId: $messageId")
        val account = accountRepository.getAccountById(accountId).firstOrNull()

        if (account == null) {
            Log.e(TAG, "getMessageAttachments: Account not found for id $accountId")
            return flowOf(emptyList())
        }
        val apiService = mailApiServices[account.providerType.uppercase()]
        return if (apiService != null) {
            // apiService.getMessageAttachments(messageId) // Method doesn't exist in MailApiService yet
            Log.w(
                TAG,
                "getMessageAttachments: MailApiService.getMessageAttachments not yet implemented."
            )
            flowOf(emptyList())
        } else {
            Log.e(
                TAG,
                "getMessageAttachments: ApiService not found for provider ${account.providerType}"
            )
            flowOf(emptyList())
        }
    }

    override suspend fun downloadAttachment(
        accountId: String,
        messageId: String,
        attachmentId: String
    ): Result<ByteArray> {
        Log.d(
            TAG,
            "downloadAttachment called for accountId: $accountId, messageId: $messageId, attachmentId: $attachmentId"
        )
        val account = accountRepository.getAccountById(accountId).firstOrNull()
        if (account == null) {
            Log.e(TAG, "downloadAttachment: Account $accountId not found.")
            return Result.failure(NotImplementedError("Account not found for attachment download."))
        }
        mailApiServices[account.providerType.uppercase()]
        // apiService?.downloadAttachment(messageId, attachmentId) // Method doesn't exist in MailApiService yet
        Log.w(TAG, "downloadAttachment: MailApiService.downloadAttachment not yet implemented.")
        return Result.failure(NotImplementedError("downloadAttachment not yet implemented in MailApiService"))
    }

    // --- Stubs for methods not yet refactored for full DB interaction in Phase 2a ---

    override suspend fun createDraftMessage(
        accountId: String,
        draftDetails: MessageDraft
    ): Result<Message> {
        Log.d(
            TAG,
            "createDraftMessage called for accountId: $accountId. Not implemented with DB sync."
        )
        // This would typically involve saving a draft to a local Drafts table or directly to the server.
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
            "searchMessages called for accountId: $accountId, query: '$query'. Not implemented with DB FTS yet."
        )
        // Phase 3 plans for MessageFtsEntity. For now, this would be a network-only search or empty.
        // For local FTS, this would query the MessageFtsEntity in Room.
        return flowOf(emptyList()) // Placeholder
    }
}