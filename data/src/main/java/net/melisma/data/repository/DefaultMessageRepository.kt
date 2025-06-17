package net.melisma.data.repository

import android.app.Activity
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.MessageSyncState
import net.melisma.core_data.model.SyncJob
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.MessageFolderJunctionDao
import net.melisma.core_db.dao.PendingActionDao
import net.melisma.core_db.entity.PendingActionEntity
import net.melisma.core_db.model.PendingActionStatus
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import net.melisma.data.sync.SyncConstants
import net.melisma.data.sync.SyncController
import net.melisma.core_db.entity.MessageFolderJunction
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultMessageRepository @Inject constructor(
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val folderDao: FolderDao,
    private val pendingActionDao: PendingActionDao,
    private val messageFolderJunctionDao: MessageFolderJunctionDao,
    private val appDatabase: AppDatabase,
    private val syncController: SyncController
) : MessageRepository {

    private val _messageSyncState = MutableStateFlow<MessageSyncState>(MessageSyncState.Idle)
    override val messageSyncState: StateFlow<MessageSyncState> = _messageSyncState.asStateFlow()

    init {
        Timber.d("Initializing DefaultMessageRepository.")
    }

    override fun observeMessagesForFolder(accountId: String, folderId: String): Flow<List<Message>> {
        Timber.d("observeMessagesForFolder: accountId=$accountId, folderId=$folderId")

        externalScope.launch(ioDispatcher) {
            val messagesCount = messageDao.getMessagesCountForFolder(accountId, folderId)
            val folderEntity = folderDao.getFolderByIdSuspend(folderId)

            if (folderEntity != null) {
                val needsSync = messagesCount == 0 || folderEntity.lastSuccessfulSyncTimestamp == null
                if (needsSync && folderEntity.remoteId != null) {
                    Timber.d("Observe: Folder $folderId needs content sync. Submitting job.")
                    syncController.submit(SyncJob.ForceRefreshFolder(folderId, accountId))
                } else if (folderEntity.remoteId == null) {
                    Timber.w("Observe: Cannot sync folder $folderId, remoteId is null.")
                }
            } else {
                Timber.w("Observe: FolderEntity for id $folderId not found.")
            }
        }

        return messageDao.getMessagesForFolder(accountId, folderId)
            .map { entities -> entities.map { it.toDomainModel(folderId) } }
    }

    @OptIn(ExperimentalPagingApi::class)
    override fun getMessagesPager(
        accountId: String,
        folderId: String,
        pagingConfig: PagingConfig
    ): Flow<PagingData<Message>> {
        Timber.d("getMessagesPager for accountId=$accountId, folderId=$folderId.")
        return Pager(
            config = pagingConfig,
            pagingSourceFactory = { messageDao.getMessagesPagingSource(accountId, folderId) }
        ).flow.map { pagingData -> pagingData.map { it.toDomainModel(folderId) } }
    }

    override fun getUnifiedInboxPager(
        pagingConfig: PagingConfig,
        filterUnread: Boolean,
        filterStarred: Boolean
    ): Flow<PagingData<Message>> {
        Timber.d("getUnifiedInboxPager called with unread=$filterUnread, starred=$filterStarred")
        return Pager(
            config = pagingConfig,
            pagingSourceFactory = { messageDao.getUnifiedInboxPagingSource(filterUnread, filterStarred) }
        ).flow.map { pagingData -> pagingData.map { it.toDomainModel() } }
    }

    override suspend fun setTargetFolder(account: Account?, folder: MailFolder?) {
        // This method is now obsolete and intentionally left blank.
        // Sync is handled by other mechanisms like observeMessagesForFolder and refreshMessages.
    }


    override suspend fun refreshMessages(activity: Activity?) {
        // This method is now obsolete and intentionally left blank.
        // The more specific syncMessagesForFolder should be used.
    }

    override suspend fun syncMessagesForFolder(
        accountId: String,
        folderId: String,
        activity: Activity?
    ) {
        syncController.submit(SyncJob.ForceRefreshFolder(folderId, accountId))
    }


    override suspend fun getMessageDetails(messageId: String, accountId: String): Flow<Message?> {
        externalScope.launch(ioDispatcher) {
            val message = messageDao.getMessageByIdSuspend(messageId)
            if (message != null) {
                messageDao.updateLastAccessedTimestamp(message.id, System.currentTimeMillis())
                if (message.body.isNullOrEmpty()) {
                    Timber.d("Message body for $messageId is empty, requesting download.")
                    syncController.submit(SyncJob.FetchFullMessageBody(messageId = message.id, accountId = accountId))
                }
            }
        }

        return messageDao.getMessageById(messageId).map { entity ->
            entity?.toDomainModel("")
        }
    }

    override suspend fun getMessageById(messageId: String): Flow<Message?> {
        return messageDao.getMessageById(messageId).map { entity ->
            entity?.toDomainModel("")
        }
    }

    override suspend fun markMessageRead(account: Account, messageId: String, isRead: Boolean): Result<Unit> = withContext(ioDispatcher) {
        try {
            messageDao.updateReadStatus(messageId, isRead)
            val actionType = if (isRead) SyncConstants.ACTION_MARK_AS_READ else SyncConstants.ACTION_MARK_AS_UNREAD
            val payload = mapOf(SyncConstants.KEY_IS_READ to isRead.toString())

            // Persist pending action
            queuePendingAction(account.id, messageId, actionType, payload)

            // Submit sync job
            syncController.submit(SyncJob.UploadAction(account.id))
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to mark message $messageId as read=$isRead")
            Result.failure(e)
        }
    }

    override suspend fun starMessage(account: Account, messageId: String, isStarred: Boolean): Result<Unit> = withContext(ioDispatcher) {
        try {
            messageDao.updateStarredStatus(messageId, isStarred)
            val payload = mapOf(SyncConstants.KEY_IS_STARRED to isStarred.toString())

            queuePendingAction(account.id, messageId, SyncConstants.ACTION_STAR_MESSAGE, payload)

            syncController.submit(SyncJob.UploadAction(account.id))
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to star message $messageId with isStarred=$isStarred")
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(account: Account, messageId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            messageDao.setSyncStatus(messageId, EntitySyncStatus.PENDING_DELETE)

            queuePendingAction(account.id, messageId, SyncConstants.ACTION_DELETE_MESSAGE)

            syncController.submit(SyncJob.UploadAction(account.id))
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete message $messageId")
            Result.failure(e)
        }
    }

    override suspend fun moveMessage(account: Account, messageId: String, newFolderId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val currentFolders = messageFolderJunctionDao.getFoldersForMessage(messageId)
            val oldFolderId = currentFolders.firstOrNull() ?: ""

            // Remove old label link (if any) and add new label link
            if (oldFolderId.isNotBlank()) {
                messageFolderJunctionDao.removeLabel(messageId, oldFolderId)
            }
            messageFolderJunctionDao.addLabel(messageId, newFolderId)

            val payload = mapOf(
                SyncConstants.KEY_NEW_FOLDER_ID to newFolderId,
                SyncConstants.KEY_OLD_FOLDER_ID to oldFolderId
            )

            queuePendingAction(account.id, messageId, SyncConstants.ACTION_MOVE_MESSAGE, payload)

            syncController.submit(SyncJob.UploadAction(account.id))
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to move message $messageId to folder $newFolderId")
            Result.failure(e)
        }
    }

    override suspend fun sendMessage(account: Account, draft: MessageDraft): Result<String> = withContext(ioDispatcher) {
        try {
            val tempId = draft.existingMessageId ?: "local-sent-${UUID.randomUUID()}"
            val messageEntity = draft.toEntity(
                id = tempId,
                accountId = account.id,
                senderName = account.displayName,
                senderAddress = account.emailAddress,
                isRead = true,
                syncStatus = EntitySyncStatus.PENDING_UPLOAD
            )
            val attachmentEntities = draft.attachments.map {
                it.toEntity(messageDbId = tempId, accountId = account.id)
            }

            appDatabase.withTransaction {
                messageDao.insertOrUpdateMessages(listOf(messageEntity))
                attachmentDao.insertAttachments(attachmentEntities)
            }

            val payload = mapOf(
                SyncConstants.KEY_DRAFT_DETAILS to Json.encodeToString(MessageDraft.serializer(), draft)
            )

            queuePendingAction(
                accountId = account.id,
                entityId = tempId,
                actionType = SyncConstants.ACTION_SEND_MESSAGE,
                payload = payload
            )

            syncController.submit(SyncJob.UploadAction(account.id))
            Result.success(tempId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message for draft: ${draft.existingMessageId}")
            Result.failure(e)
        }
    }

    override suspend fun createDraftMessage(account: Account, draftDetails: MessageDraft): Result<Message> = withContext(ioDispatcher) {
        try {
            val tempId = "local-draft-${UUID.randomUUID()}"

            val messageEntity = draftDetails.toEntity(
                id = tempId,
                accountId = account.id,
                senderName = account.displayName,
                senderAddress = account.emailAddress,
                isRead = true,
                syncStatus = EntitySyncStatus.PENDING_UPLOAD
            )
            val localAttachments = draftDetails.attachments.map { it.toEntity(messageDbId = tempId, accountId = account.id) }

            appDatabase.withTransaction {
                messageDao.insertOrUpdateMessages(listOf(messageEntity))
                attachmentDao.insertAttachments(localAttachments)
                val draftFolder = folderDao.getFolderByWellKnownTypeSuspend(account.id, WellKnownFolderType.DRAFTS)
                if (draftFolder != null) {
                    messageFolderJunctionDao.addLabel(tempId, draftFolder.id)
                }
            }
            Timber.d("Saved new draft $tempId locally.")

            val payload = mapOf(SyncConstants.KEY_DRAFT_DETAILS to Json.encodeToString(MessageDraft.serializer(), draftDetails))

            queuePendingAction(
                accountId = account.id,
                entityId = tempId,
                actionType = SyncConstants.ACTION_CREATE_DRAFT,
                payload = payload
            )

            syncController.submit(SyncJob.UploadAction(account.id))
            Result.success(messageEntity.toDomainModel())
        } catch (e: Exception) {
            Timber.e(e, "Error creating draft")
            Result.failure(e)
        }
    }

    override suspend fun updateDraftMessage(
        account: Account,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message> = withContext(ioDispatcher) {
        try {
            val messageEntity = draftDetails.toEntity(
                id = messageId,
                accountId = account.id,
                senderName = account.displayName,
                senderAddress = account.emailAddress,
                isRead = true,
                syncStatus = EntitySyncStatus.PENDING_UPLOAD
            )
            val localAttachments = draftDetails.attachments.map { it.toEntity(messageDbId = messageId, accountId = account.id) }

            appDatabase.withTransaction {
                messageDao.insertOrUpdateMessages(listOf(messageEntity))
                attachmentDao.deleteAttachmentsForMessage(messageId)
                attachmentDao.insertAttachments(localAttachments)
            }
            Timber.d("Updated draft $messageId locally.")

            val payload = mapOf(SyncConstants.KEY_DRAFT_DETAILS to Json.encodeToString(MessageDraft.serializer(), draftDetails))

            queuePendingAction(
                accountId = account.id,
                entityId = messageId,
                actionType = SyncConstants.ACTION_UPDATE_DRAFT,
                payload = payload
            )

            syncController.submit(SyncJob.UploadAction(account.id))
            Result.success(messageEntity.toDomainModel())
        } catch (e: Exception) {
            Timber.e(e, "Error updating draft $messageId")
            Result.failure(e)
        }
    }

    override fun searchMessages(
        accountId: String,
        query: String,
        folderId: String?
    ): Flow<List<Message>> {
        externalScope.launch {
            syncController.submit(SyncJob.SearchOnline(query, folderId, accountId))
        }
        val pattern = "%${query}%"
        return messageDao.searchMessages(pattern, accountId, folderId)
            .map { list -> list.map { it.toDomainModel(folderId ?: "") } }
    }

    override suspend fun getMessageAttachments(
        accountId: String,
        messageId: String
    ): Flow<List<Attachment>> {
        return attachmentDao.getAttachmentsForMessage(messageId)
            .map { entities -> entities.map { it.toDomainModel() } }
    }

    override suspend fun downloadAttachment(
        accountId: String,
        messageId: String,
        attachment: Attachment
    ): Flow<String?> = channelFlow {
        send(null) // Initially, no path is available
        syncController.submit(
            SyncJob.DownloadAttachment(
                attachmentId = attachment.id.toLong(),
                messageId = messageId,
                accountId = accountId
            )
        )

        // This part would need to observe download progress. For now, it's fire-and-forget.
        // A more complete implementation would observe a flow from a DownloadManager or SyncController
        // that reports the status of this specific download job.
    }

    override fun observeMessageAttachments(messageId: String): Flow<List<Attachment>> {
        return attachmentDao.getAttachmentsForMessage(messageId).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * Helper to persist a PendingActionEntity and return its DB id.
     */
    private suspend fun queuePendingAction(
        accountId: String,
        entityId: String,
        actionType: String,
        payload: Map<String, String?> = emptyMap()
    ): Long {
        val action = PendingActionEntity(
            accountId = accountId,
            entityId = entityId,
            actionType = actionType,
            payload = payload,
            status = PendingActionStatus.PENDING
        )
        return pendingActionDao.insertAction(action)
    }
}