package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.PendingActionDao
import net.melisma.core_db.entity.PendingActionEntity
import net.melisma.core_db.model.PendingActionStatus
import net.melisma.data.mapper.toEntity
import timber.log.Timber

@HiltWorker
class ActionUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val appDatabase: AppDatabase,
    private val accountDao: AccountDao,
    private val folderDao: FolderDao,
    private val pendingActionDao: PendingActionDao,
    private val networkMonitor: NetworkMonitor,
    private val attachmentDao: AttachmentDao,
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "ActionUploadWorker"

    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_ENTITY_ID = "ENTITY_ID"
        const val KEY_ACTION_TYPE = "ACTION_TYPE"
        const val KEY_ACTION_PAYLOAD = "ACTION_PAYLOAD"

        const val KEY_DRAFT_DETAILS = "draft_json"
        const val KEY_IS_READ = "isRead"
        const val KEY_IS_STARRED = "isStarred"
        const val KEY_OLD_FOLDER_ID = "oldFolderId"
        const val KEY_NEW_FOLDER_ID = "newFolderId"

        const val ACTION_MARK_AS_READ = "MARK_AS_READ"
        const val ACTION_MARK_AS_UNREAD = "MARK_AS_UNREAD"
        const val ACTION_STAR_MESSAGE = "STAR_MESSAGE"
        const val ACTION_DELETE_MESSAGE = "DELETE_MESSAGE"
        const val ACTION_MOVE_MESSAGE = "MOVE_MESSAGE"
        const val ACTION_SEND_MESSAGE = "SEND_MESSAGE"
        const val ACTION_CREATE_DRAFT = "CREATE_DRAFT"
        const val ACTION_UPDATE_DRAFT = "UPDATE_DRAFT"

        const val ACTION_MARK_THREAD_AS_READ = "MARK_THREAD_AS_READ"
        const val ACTION_MARK_THREAD_AS_UNREAD = "MARK_THREAD_AS_UNREAD"
        const val ACTION_DELETE_THREAD = "DELETE_THREAD"
        const val ACTION_MOVE_THREAD = "MOVE_THREAD"
    }

    override suspend fun doWork(): Result {
        Timber.d("$TAG: Starting ActionUploadWorker run.")

        if (!networkMonitor.isOnline.first()) {
            Timber.w("$TAG: Network offline. Worker will retry later.")
            return Result.retry()
        }

        var actionsProcessedInThisRun = 0
        while (true) {
            currentCoroutineContext().ensureActive()

            val action = pendingActionDao.getNextActionToProcess()
            if (action == null) {
                Timber.d("$TAG: No more pending actions to process in this run.")
                break // Exit loop, no more actions
            }

            Timber.i("$TAG: Processing action ID: ${action.id}, Type: ${action.actionType}, Entity: ${action.entityId}, Attempt: ${action.attemptCount + 1}")

            action.attemptCount++
            action.lastAttemptAt = System.currentTimeMillis()
            pendingActionDao.updateAction(action)

            val account = accountDao.getAccountByIdSuspend(action.accountId)
            if (account == null) {
                Timber.e("$TAG: Account ${action.accountId} not found for action ${action.id}. Marking as FAILED.")
                action.status = PendingActionStatus.FAILED
                action.lastError = "Account not found"
                pendingActionDao.updateAction(action)
                continue
            }

            val mailService = mailApiServiceSelector.getServiceByProviderType(account.providerType)
            if (mailService == null) {
                Timber.e("$TAG: MailService for provider ${account.providerType} not found (action ${action.id}). Marking as FAILED.")
                action.status = PendingActionStatus.FAILED
                action.lastError = "Mail service not available for provider ${account.providerType}"
                pendingActionDao.updateAction(action)
                continue
            }

            val actionOutcome = performActionInternal(mailService, action)

            if (actionOutcome.isSuccess) {
                Timber.i("$TAG: Action ID ${action.id} (${action.actionType}) for entity ${action.entityId} SUCCEEDED.")
                pendingActionDao.deleteActionById(action.id)
                actionsProcessedInThisRun++
            } else {
                val exception = actionOutcome.exceptionOrNull()
                val errorMessage = exception?.message ?: "Action failed without specific error message"
                Timber.w(exception, "$TAG: Action ID ${action.id} (${action.actionType}) for entity ${action.entityId} FAILED. Error: $errorMessage. Attempt: ${action.attemptCount}/${action.maxAttempts}")
                action.lastError = errorMessage
                if (action.attemptCount >= action.maxAttempts) {
                    action.status = PendingActionStatus.FAILED
                    Timber.w("$TAG: Action ID ${action.id} reached max attempts (${action.maxAttempts}). Marked as FAILED.")
                } else {
                    action.status = PendingActionStatus.RETRY
                    Timber.w("$TAG: Action ID ${action.id} marked for RETRY.")
                }
                pendingActionDao.updateAction(action)
            }
        }

        Timber.d("$TAG: Finished ActionUploadWorker run. Processed $actionsProcessedInThisRun actions in this cycle.")
        return Result.success()
    }

    private suspend fun performActionInternal(
        mailService: MailApiService,
        action: PendingActionEntity,
    ): kotlin.Result<Unit> {
        val accountId = action.accountId
        val entityId = action.entityId
        val actionType = action.actionType
        val payload = action.payload

        return when (actionType) {
            ACTION_MARK_AS_READ, ACTION_MARK_AS_UNREAD -> {
                val isRead = payload[KEY_IS_READ]?.toBooleanStrictOrNull()
                    ?: return kotlin.Result.failure(IllegalArgumentException("Missing or invalid 'isRead' payload."))
                mailService.markMessageRead(entityId, isRead)
            }
            ACTION_STAR_MESSAGE -> {
                val isStarred = payload[KEY_IS_STARRED]?.toBooleanStrictOrNull()
                    ?: return kotlin.Result.failure(IllegalArgumentException("Missing or invalid 'isStarred' payload."))
                mailService.starMessage(entityId, isStarred)
            }
            ACTION_DELETE_MESSAGE -> mailService.deleteMessage(entityId)
            ACTION_MOVE_MESSAGE -> {
                val targetFolderId = payload[KEY_NEW_FOLDER_ID]
                    ?: return kotlin.Result.failure(IllegalArgumentException("Missing target folder ID"))
                val sourceFolderId = payload[KEY_OLD_FOLDER_ID]
                    ?: return kotlin.Result.failure(IllegalArgumentException("Missing source folder ID"))
                mailService.moveMessage(entityId, sourceFolderId, targetFolderId)
            }
            ACTION_SEND_MESSAGE -> handleSendMessage(entityId, payload)
            ACTION_CREATE_DRAFT -> handleCreateDraft(mailService, action)
            ACTION_UPDATE_DRAFT -> handleUpdateDraft(mailService, action)
            ACTION_MARK_THREAD_AS_READ, ACTION_MARK_THREAD_AS_UNREAD -> {
                 val isRead = payload[KEY_IS_READ]?.toBooleanStrictOrNull()
                    ?: return kotlin.Result.failure(IllegalArgumentException("Missing or invalid 'isRead' payload."))
                mailService.markThreadRead(entityId, isRead)
            }
            ACTION_DELETE_THREAD -> mailService.deleteThread(entityId)
            ACTION_MOVE_THREAD -> {
                val destinationFolderId = payload[KEY_NEW_FOLDER_ID]
                     ?: return kotlin.Result.failure(IllegalArgumentException("Missing 'destinationFolderId' payload."))
                val sourceFolderId = payload[KEY_OLD_FOLDER_ID]
                     ?: return kotlin.Result.failure(IllegalArgumentException("Missing 'oldFolderId' payload."))
                mailService.moveThread(entityId, sourceFolderId, destinationFolderId)
            }
            else -> {
                Timber.w("$TAG: Unhandled action type: $actionType for entity $entityId")
                kotlin.Result.failure(UnsupportedOperationException("Action type '$actionType' not supported."))
            }
        }
    }

    private suspend fun handleSendMessage(localMessageId: String, payload: Map<String, String?>): kotlin.Result<Unit> {
        return kotlin.Result.failure(UnsupportedOperationException("SEND_MESSAGE not yet supported in refactored flow"))
    }

    private suspend fun handleCreateDraft(mailService: MailApiService, action: PendingActionEntity): kotlin.Result<Unit> {
        val draftJson = action.payload[KEY_DRAFT_DETAILS]
            ?: return kotlin.Result.failure(IllegalArgumentException("Missing '$KEY_DRAFT_DETAILS' for create-draft"))
        return try {
            val draft = Json.decodeFromString<MessageDraft>(draftJson)
            val createResult = mailService.createDraftMessage(draft)
            if (createResult.isSuccess) {
                val serverMessage = createResult.getOrThrow()
                appDatabase.withTransaction {
                    val localMessage = messageDao.getMessageByIdSuspend(action.entityId)
                        ?: throw IllegalStateException("Local message ${action.entityId} not found for create-draft.")

                    val updatedEntity = serverMessage.toEntity(action.accountId).copy(
                        id = localMessage.id,
                        syncStatus = EntitySyncStatus.SYNCED,
                        lastSyncError = null,
                        lastSuccessfulSyncTimestamp = System.currentTimeMillis(),
                    )
                    messageDao.insertOrUpdateMessages(listOf(updatedEntity))

                    attachmentDao.deleteAttachmentsForMessage(action.entityId)
                    val newAttachments = serverMessage.attachments.map {
                        it.toEntity(action.entityId, action.accountId)
                            .copy(syncStatus = EntitySyncStatus.SYNCED)
                    }
                    if (newAttachments.isNotEmpty()) {
                        attachmentDao.insertAttachments(newAttachments)
                    }
                    Timber.d("$TAG: Updated local draft ${action.entityId} with server info (new remoteId: ${serverMessage.id}).")
                }
            }
            createResult.map { }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to create draft on server for local entity ${action.entityId}")
            kotlin.Result.failure(e)
        }
    }

    private suspend fun handleUpdateDraft(mailService: MailApiService, action: PendingActionEntity): kotlin.Result<Unit> {
        val draftJson = action.payload[KEY_DRAFT_DETAILS]
            ?: return kotlin.Result.failure(IllegalArgumentException("Missing '$KEY_DRAFT_DETAILS' for update-draft"))
        return try {
            val draft = Json.decodeFromString<MessageDraft>(draftJson)
            val localMessage = messageDao.getMessageByIdSuspend(action.entityId)
                ?: throw IllegalStateException("Cannot find local message ${action.entityId} to update draft.")
            val remoteId = localMessage.messageId
                ?: throw IllegalStateException("Local message ${action.entityId} has no remote ID to update draft.")

            val updateResult = mailService.updateDraftMessage(remoteId, draft)
            if (updateResult.isSuccess) {
                val serverMessage = updateResult.getOrThrow()
                appDatabase.withTransaction {
                    val updatedEntity = serverMessage.toEntity(action.accountId).copy(
                        id = localMessage.id,
                        syncStatus = EntitySyncStatus.SYNCED,
                        lastSyncError = null,
                        lastSuccessfulSyncTimestamp = System.currentTimeMillis(),
                    )
                    messageDao.insertOrUpdateMessages(listOf(updatedEntity))

                    attachmentDao.deleteAttachmentsForMessage(action.entityId)
                    val newAttachments = serverMessage.attachments.map {
                        it.toEntity(action.entityId, action.accountId)
                            .copy(syncStatus = EntitySyncStatus.SYNCED)
                    }
                    if (newAttachments.isNotEmpty()) {
                        attachmentDao.insertAttachments(newAttachments)
                    }
                    Timber.d("$TAG: Updated local draft ${action.entityId} with server info.")
                }
            }
            updateResult.map { }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to update draft on server for local entity ${action.entityId}")
            kotlin.Result.failure(e)
        }
    }
}

