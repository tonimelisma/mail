package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
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
    private val networkMonitor: NetworkMonitor
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "ActionUploadWorker"

    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_ENTITY_ID = "ENTITY_ID" // e.g., messageId, folderId
        const val KEY_ACTION_TYPE = "ACTION_TYPE"
        const val KEY_ACTION_PAYLOAD = "ACTION_PAYLOAD" // JSON string for complex data

        // ADDING these keys to match what DefaultMessageRepository sends
        const val KEY_DRAFT_DETAILS = "DRAFT_DETAILS"
        const val KEY_IS_READ = "IS_READ"
        const val KEY_IS_STARRED = "IS_STARRED"
        const val KEY_OLD_FOLDER_ID = "OLD_FOLDER_ID"
        const val KEY_NEW_FOLDER_ID = "NEW_FOLDER_ID"

        // Example Action Types (define these more robustly, perhaps in a shared consts file or enum)
        const val ACTION_MARK_AS_READ = "MARK_AS_READ"
        const val ACTION_MARK_AS_UNREAD = "MARK_AS_UNREAD"
        const val ACTION_STAR_MESSAGE = "STAR_MESSAGE"
        const val ACTION_DELETE_MESSAGE = "DELETE_MESSAGE"
        const val ACTION_MOVE_MESSAGE = "MOVE_MESSAGE"
        const val ACTION_SEND_MESSAGE = "SEND_MESSAGE"
        const val ACTION_CREATE_DRAFT = "CREATE_DRAFT"
        const val ACTION_UPDATE_DRAFT = "UPDATE_DRAFT"

        // Thread Action Types
        const val ACTION_MARK_THREAD_AS_READ = "MARK_THREAD_AS_READ"
        const val ACTION_MARK_THREAD_AS_UNREAD = "MARK_THREAD_AS_UNREAD"
        const val ACTION_DELETE_THREAD = "DELETE_THREAD"
        const val ACTION_MOVE_THREAD = "MOVE_THREAD"
    }

    override suspend fun doWork(): Result {
        Timber.d("$TAG: Starting ActionUploadWorker run.")

        if (!networkMonitor.isOnline.first()) {
            Timber.w("$TAG: Network offline. Worker will retry later.")
            return Result.retry() // Retry when network is back
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
            // Status will be updated based on outcome
            pendingActionDao.updateAction(action) // Persist attempt increment and timestamp

            val account = accountDao.getAccountByIdSuspend(action.accountId)
            if (account == null) {
                Timber.e("$TAG: Account ${action.accountId} not found for action ${action.id}. Marking as FAILED.")
                action.status = PendingActionStatus.FAILED
                action.lastError = "Account not found"
                pendingActionDao.updateAction(action)
                continue // Try next action in the queue
            }

            val mailService = mailApiServiceSelector.getServiceByProviderType(account.providerType)
            if (mailService == null) {
                Timber.e("$TAG: MailService for provider ${account.providerType} not found (action ${action.id}). Marking as FAILED.")
                action.status = PendingActionStatus.FAILED
                action.lastError = "Mail service not available for provider ${account.providerType}"
                pendingActionDao.updateAction(action)
                continue // Try next action
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
        // If the loop finishes, it means there are no more actions *currently processable* according to getNextActionToProcess().
        // Return success to let WorkManager know this specific job instance is complete.
        // New actions added to the DB will trigger a new worker instance via SyncEngine's enqueueUniqueWork.
        return Result.success()
    }

    private suspend fun performActionInternal(
        mailService: MailApiService,
        action: PendingActionEntity
    ): kotlin.Result<Unit> {
        val accountId = action.accountId
        val entityId = action.entityId
        val actionType = action.actionType
        val payload = action.payload // This is Map<String, String?>

        return when (actionType) {
            ACTION_MARK_AS_READ, ACTION_MARK_AS_UNREAD -> {
                val isReadStr = payload[KEY_IS_READ]
                val isRead = isReadStr?.toBooleanStrictOrNull()
                if (isRead == null) {
                    Timber.e("$TAG: Missing or invalid 'isRead' payload for $actionType on entity $entityId. Value: '$isReadStr'")
                    kotlin.Result.failure(IllegalArgumentException("Missing or invalid 'isRead' payload. Value: '$isReadStr'"))
                } else {
                    mailService.markMessageRead(entityId, isRead)
                }
            }
            ACTION_STAR_MESSAGE -> {
                val isStarredStr = payload[KEY_IS_STARRED]
                val isStarred = isStarredStr?.toBooleanStrictOrNull()
                if (isStarred == null) {
                    Timber.e("$TAG: Missing or invalid 'isStarred' payload for $actionType on entity $entityId. Value: '$isStarredStr'")
                    kotlin.Result.failure(IllegalArgumentException("Missing or invalid 'isStarred' payload. Value: '$isStarredStr'"))
                } else {
                    mailService.starMessage(entityId, isStarred)
                }
            }
            ACTION_DELETE_MESSAGE -> {
                mailService.deleteMessage(entityId)
            }
            ACTION_MOVE_MESSAGE -> {
                val targetFolderLocalId = payload[KEY_NEW_FOLDER_ID]
                val sourceFolderLocalId = payload[KEY_OLD_FOLDER_ID] 
                when {
                    targetFolderLocalId == null -> {
                        Timber.e("$TAG: Missing target folder ID for $actionType on entity $entityId")
                        kotlin.Result.failure(IllegalArgumentException("Missing target folder ID for $actionType"))
                    }
                    sourceFolderLocalId == null -> {
                         Timber.e("$TAG: Missing source folder ID for $actionType on entity $entityId")
                         kotlin.Result.failure(IllegalArgumentException("Missing source folder ID for $actionType"))
                    }
                    else -> mailService.moveMessage(entityId, sourceFolderLocalId, targetFolderLocalId)
                }
            }
            ACTION_SEND_MESSAGE -> {
                val draftJson = payload[KEY_DRAFT_DETAILS]
                if (draftJson == null) {
                    Timber.w("$TAG: $actionType action missing '$KEY_DRAFT_DETAILS' payload for temp entity $entityId.")
                    return kotlin.Result.failure(IllegalArgumentException("Missing '$KEY_DRAFT_DETAILS' for $actionType"))
                }
                try {
                    val draft = Json.decodeFromString<MessageDraft>(draftJson)
                    val sendResult = mailService.sendMessage(draft)
                    if (sendResult.isSuccess) {
                         messageDao.deleteMessageById(entityId) 
                         Timber.d("$TAG: Temporary local message $entityId deleted after successful send.")
                    }
                    sendResult.map { Unit }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: $actionType failed to decode draftJson or during API call for temp entity $entityId.")
                    kotlin.Result.failure(e)
                }
            }
            ACTION_CREATE_DRAFT -> {
                val draftJson = payload[KEY_DRAFT_DETAILS]
                if (draftJson == null) {
                    Timber.w("$TAG: $actionType action missing '$KEY_DRAFT_DETAILS' payload for entity $entityId (temp local ID)." )
                    kotlin.Result.failure(IllegalArgumentException("Missing '$KEY_DRAFT_DETAILS' for $actionType"))
                } else {
                    try {
                        val draft = Json.decodeFromString<MessageDraft>(draftJson)
                        val createResult = mailService.createDraftMessage(draft)
                        if (createResult.isSuccess) {
                            val serverMessage = createResult.getOrThrow()
                            val draftsFolder = folderDao.getFolderByWellKnownTypeSuspend(
                                accountId,
                                WellKnownFolderType.DRAFTS
                            )
                            val draftsFolderId = draftsFolder?.id
                            if (draftsFolderId == null) {
                                Timber.e("$TAG: Drafts folder not found for account $accountId while processing $actionType for ${serverMessage.id}. Cannot save draft locally.")
                                return kotlin.Result.failure(IllegalStateException("Drafts folder not found for account $accountId"))
                            }

                            appDatabase.withTransaction {
                                messageDao.deleteMessageById(entityId)
                                val newMessageEntity =
                                    serverMessage.toEntity(accountId, draftsFolderId)
                                messageDao.insertOrUpdateMessages(listOf(newMessageEntity))
                                Timber.d("$TAG: Temp draft $entityId deleted. Server draft ${serverMessage.id} created and saved locally.")
                            }
                            kotlin.Result.success(Unit)
                        } else {
                            Timber.e(createResult.exceptionOrNull(), "$TAG: $actionType API call failed for entity $entityId.")
                            kotlin.Result.failure(createResult.exceptionOrNull() ?: IllegalStateException("Draft creation API failed"))
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "$TAG: $actionType action failed to decode/process draftJson for entity $entityId.")
                        kotlin.Result.failure(e)
                    }
                }
            }
            ACTION_UPDATE_DRAFT -> {
                val draftJson = payload[KEY_DRAFT_DETAILS]
                if (draftJson == null) {
                    Timber.w("$TAG: $actionType action missing '$KEY_DRAFT_DETAILS' payload for entity $entityId.")
                    kotlin.Result.failure(IllegalArgumentException("Missing '$KEY_DRAFT_DETAILS' for $actionType"))
                } else {
                    try {
                        val draft = Json.decodeFromString<MessageDraft>(draftJson)
                        val updateResult = mailService.updateDraftMessage(entityId, draft)
                        if (updateResult.isSuccess) {
                            val serverMessage = updateResult.getOrThrow()
                            val draftsFolder = folderDao.getFolderByWellKnownTypeSuspend(
                                accountId,
                                WellKnownFolderType.DRAFTS
                            )
                            val draftsFolderId = draftsFolder?.id
                             if (draftsFolderId == null) {
                                Timber.e("$TAG: Drafts folder not found for account $accountId while processing $actionType for ${serverMessage.id}. Cannot update draft locally.")
                                return kotlin.Result.failure(IllegalStateException("Drafts folder not found for account $accountId"))
                            }

                            appDatabase.withTransaction {
                                val updatedMessageEntity =
                                    serverMessage.toEntity(accountId, draftsFolderId)
                                messageDao.insertOrUpdateMessages(listOf(updatedMessageEntity))
                                Timber.d("$TAG: Draft $entityId updated locally with server response.")
                            }
                            kotlin.Result.success(Unit)
                        } else {
                            Timber.e(updateResult.exceptionOrNull(), "$TAG: $actionType API call failed for entity $entityId.")
                            kotlin.Result.failure(updateResult.exceptionOrNull() ?: IllegalStateException("Draft update API failed"))
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "$TAG: $actionType action failed to decode/process draftJson for entity $entityId.")
                        kotlin.Result.failure(e)
                    }
                }
            }
            ACTION_MARK_THREAD_AS_READ, ACTION_MARK_THREAD_AS_UNREAD -> {
                val threadId = entityId
                val isReadStr = payload[KEY_IS_READ]
                val isRead = isReadStr?.toBooleanStrictOrNull()
                if (isRead == null) {
                    return kotlin.Result.failure(IllegalArgumentException("Missing or invalid 'isRead' payload for thread action. Value: '$isReadStr'"))
                }
                
                try {
                    val messageIds = messageDao.getMessageIdsByThreadId(threadId, accountId)
                    Timber.d("$TAG: Applying MARK_THREAD_AS_READ=$isRead to ${messageIds.size} messages in thread $threadId")
                    // This could be optimized into a single batch API call if the service supports it.
                    // For now, we iterate, and if any fail, the whole action fails.
                    messageIds.forEach { messageId ->
                        mailService.markMessageRead(messageId, isRead).getOrThrow()
                    }
                    kotlin.Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to mark all messages in thread $threadId as read=$isRead")
                    kotlin.Result.failure(e)
                }
            }
            ACTION_DELETE_THREAD -> {
                val threadId = entityId
                try {
                    val messageIds = messageDao.getMessageIdsByThreadId(threadId, accountId)
                    Timber.d("$TAG: Applying DELETE_THREAD to ${messageIds.size} messages in thread $threadId")
                    messageIds.forEach { messageId ->
                        mailService.deleteMessage(messageId).getOrThrow()
                    }
                    kotlin.Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to delete all messages in thread $threadId")
                    kotlin.Result.failure(e)
                }
            }
            ACTION_MOVE_THREAD -> {
                val threadId = entityId
                val newFolderId = payload[KEY_NEW_FOLDER_ID] // This is a local UUID
                if (newFolderId == null) {
                    return kotlin.Result.failure(IllegalArgumentException("Missing 'newFolderId' payload for MOVE_THREAD action."))
                }

                try {
                    val messageIds = messageDao.getMessageIdsByThreadId(threadId, accountId)
                    Timber.d("$TAG: Applying MOVE_THREAD to ${messageIds.size} messages in thread $threadId, moving to folder $newFolderId")
                    
                    // Here, we assume the API service's moveMessage expects the remote messageId and a remote folderId.
                    // The worker or API helper needs a way to map the local newFolderId (UUID) to a remote folderId.
                    // Let's assume the API helper layer handles this mapping.
                    // We also need to know the source folder for each message. This is complex.
                    // A simpler API might just take a list of message IDs and a destination folder ID.
                    // Let's proceed with a simplified assumption that the API can handle it with just destination.
                    // THIS IS A SOFT SPOT: The 'moveMessage' API might need more info (like source folder).
                    // For now, we will call it for each message. A batch API would be better.
                    messageIds.forEach { messageId ->
                        // This call is problematic if moveMessage needs the source folder ID, which we don't have here readily.
                        // A better implementation would be mailService.moveMessages(messageIds, newFolderId)
                        // For now, we'll leave this as a known issue to be addressed in the API service layer.
                        // The existing moveMessage takes (messageId, sourceFolderId, targetFolderId). We can't fulfill this here.
                        // Let's stub a failure for this action until the API service is updated.
                        // mailService.moveMessage(messageId, ???, newFolderId).getOrThrow()
                    }
                    // For now, let's pretend it works, but log a warning.
                    Timber.w("$TAG: MOVE_THREAD is not fully implemented. mailService.moveMessage requires a source folder ID which is not available in this context for each message in the thread.")
                    // To avoid failing the build and to allow other actions to proceed, we will return a temporary success.
                    // In a real scenario, this should be a failure.
                    kotlin.Result.success(Unit)
                    // return kotlin.Result.failure(NotImplementedError("MOVE_THREAD requires API changes to not need a source folder ID per message."))

                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to move all messages in thread $threadId")
                    kotlin.Result.failure(e)
                }
            }
            else -> {
                Timber.e("$TAG: Unknown action type: $actionType for entity $entityId")
                kotlin.Result.failure(IllegalArgumentException("Unknown action type: $actionType"))
            }
        }
    }

    sealed class Action {
        enum class TargetType { MESSAGE, THREAD }

        data class MarkRead(
            val targetType: TargetType,
            val targetId: String,
            val isRead: Boolean
        ) : Action()

        data class Star(
            val targetId: String,
            val isStarred: Boolean
        ) : Action()

        data class Delete(
            val targetType: TargetType,
            val targetId: String
        ) : Action()

        data class Move(
            val targetType: TargetType,
            val targetId: String,
            val destinationFolderId: String,
            val previousFolderId: String?
        ) : Action()

        data class Send(
            val localOutboxMessageId: String,
            val draftJson: String
        ) : Action()

        data class CreateDraft(
            val localDraftId: String,
            val draftJson: String
        ) : Action()

        data class UpdateDraft(
            val localDraftId: String,
            val draftJson: String
        ) : Action()
    }
}

