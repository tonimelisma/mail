package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.MessageDao
import timber.log.Timber

@HiltWorker
class ActionUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val appDatabase: AppDatabase,
    private val accountDao: AccountDao
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "ActionUploadWorker"

    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_ENTITY_ID = "ENTITY_ID" // e.g., messageId, folderId
        const val KEY_ACTION_TYPE = "ACTION_TYPE"
        const val KEY_ACTION_PAYLOAD = "ACTION_PAYLOAD" // JSON string for complex data

        // Example Action Types (define these more robustly, perhaps in a shared consts file or enum)
        const val ACTION_MARK_MESSAGE_READ = "MARK_MESSAGE_READ"
        const val ACTION_STAR_MESSAGE = "STAR_MESSAGE"
        const val ACTION_DELETE_MESSAGE = "DELETE_MESSAGE"
        const val ACTION_MOVE_MESSAGE = "MOVE_MESSAGE"
        const val ACTION_SEND_MESSAGE = "SEND_MESSAGE"
        const val ACTION_CREATE_DRAFT = "CREATE_DRAFT"
        const val ACTION_UPDATE_DRAFT = "UPDATE_DRAFT"
        // Add other action types for folders, accounts etc. as needed
    }

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val entityId = inputData.getString(KEY_ENTITY_ID)
        val actionType = inputData.getString(KEY_ACTION_TYPE)

        if (accountId.isNullOrBlank() || entityId.isNullOrBlank() || actionType.isNullOrBlank()) {
            Timber.e("$TAG: Missing required input data (accountId, entityId, or actionType).")
            return Result.failure()
        }

        Timber.d("$TAG: Started for accountId: $accountId, entityId: $entityId, actionType: $actionType")

        try {
            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("$TAG: Could not get MailService for account $accountId. Failing task.")
                // No specific API call failed here, but service itself is unavailable for the account.
                // This could imply an issue with account setup, potentially re-auth if account is non-functional.
                // For now, just fail. The flag needsReAuth is typically set by Auth layer or API call explicitly needing it.
                try {
                    messageDao.updateSyncStatusAndError(
                        entityId,
                        SyncStatus.ERROR,
                        "Mail service not found"
                    )
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "Failed to update error status for $entityId when mail service was null"
                    )
                }
                return Result.failure()
            }

            var success = false
            var finalErrorMessage: String? = null
            var needsReAuth = false

            // Generic lambda to handle result failure
            val handleFailure = { result: Result<*> ->
                val exception = result.exceptionOrNull()
                finalErrorMessage = exception?.message ?: "Unknown server error"
                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    needsReAuth = true
                }
            }

            try {
                when (actionType) {
                    ACTION_MARK_MESSAGE_READ -> {
                        val isRead = inputData.getBoolean("IS_READ", false)
                        val applyToThread = inputData.getBoolean("APPLY_TO_THREAD", false)
                        val result = if (applyToThread) mailService.markThreadRead(
                            entityId,
                            isRead,
                            accountId
                        )
                        else mailService.markMessageRead(entityId, isRead, accountId)
                        if (result.isSuccess) {
                            appDatabase.withTransaction {
                                if (applyToThread) {
                                    val messageIds =
                                        messageDao.getMessageIdsByThreadId(entityId, accountId)
                                    messageDao.updateReadState(
                                        messageIds,
                                        isRead,
                                        SyncStatus.SYNCED
                                    )
                                } else {
                                    messageDao.updateReadState(entityId, isRead, SyncStatus.SYNCED)
                                }
                            }
                            success = true
                        } else handleFailure(result)
                    }

                    ACTION_STAR_MESSAGE -> {
                        val isStarred = inputData.getBoolean("IS_STARRED", false)
                        val result = mailService.starMessage(entityId, isStarred, accountId)
                        if (result.isSuccess) {
                            messageDao.updateStarredState(entityId, isStarred, SyncStatus.SYNCED)
                            success = true
                        } else handleFailure(result)
                    }

                    ACTION_DELETE_MESSAGE -> {
                        val applyToThread = inputData.getBoolean("APPLY_TO_THREAD", false)
                        val result =
                            if (applyToThread) mailService.deleteThread(entityId, accountId)
                            else mailService.deleteMessage(entityId, accountId)
                        if (result.isSuccess) {
                            appDatabase.withTransaction {
                                if (applyToThread) {
                                    val messageIds =
                                        messageDao.getMessageIdsByThreadId(entityId, accountId)
                                    messageDao.deleteMessagesByIds(messageIds) // Requires new DAO method
                                } else {
                                    messageDao.deleteMessageById(entityId)
                                }
                            }
                            success = true
                        } else handleFailure(result)
                    }

                    ACTION_MOVE_MESSAGE -> {
                        val newFolderId = inputData.getString("NEW_FOLDER_ID")
                        val oldFolderId = inputData.getString("OLD_FOLDER_ID")
                        val applyToThread = inputData.getBoolean("APPLY_TO_THREAD", false)
                        if (newFolderId.isNullOrBlank()) {
                            finalErrorMessage = "Move failed: newFolderId is missing."
                        } else {
                            val result = if (applyToThread) mailService.moveThread(
                                entityId,
                                newFolderId,
                                oldFolderId,
                                accountId
                            )
                            else mailService.moveMessage(
                                entityId,
                                newFolderId,
                                oldFolderId,
                                accountId
                            )
                            if (result.isSuccess) {
                                appDatabase.withTransaction {
                                    if (applyToThread) {
                                        val messageIds =
                                            messageDao.getMessageIdsByThreadIdAndFolder(
                                                entityId,
                                                accountId,
                                                oldFolderId ?: ""
                                            ) // oldFolderId might be null if not provided
                                        messageDao.updateFolderIdForMessages(
                                            messageIds,
                                            newFolderId,
                                            SyncStatus.SYNCED
                                        )
                                    } else {
                                        messageDao.updateMessageFolderAndSyncState(
                                            entityId,
                                            newFolderId,
                                            SyncStatus.SYNCED
                                        )
                                    }
                                }
                                success = true
                            } else handleFailure(result)
                        }
                    }

                    ACTION_SEND_MESSAGE -> {
                        val draftJson = inputData.getString("DRAFT_JSON")
                        val sentFolderId = inputData.getString("SENT_FOLDER_ID") ?: "SENT"
                        if (draftJson == null) {
                            finalErrorMessage =
                                "Draft details missing for sending message $entityId"
                        } else {
                            val draft = Json.decodeFromString<MessageDraft>(draftJson)
                            val result = mailService.sendMessage(draft, accountId)
                            if (result.isSuccess) {
                                val sentMessageServerId = result.getOrThrow()
                                appDatabase.withTransaction {
                                    messageDao.markAsSent(
                                        localOutboxMessageId = entityId,
                                        sentTimestamp = System.currentTimeMillis(),
                                        targetFolderId = sentFolderId,
                                        newServerId = sentMessageServerId // Update with server ID if different
                                    )
                                }
                                success = true
                            } else handleFailure(result)
                        }
                    }

                    ACTION_CREATE_DRAFT, ACTION_UPDATE_DRAFT -> {
                        val isCreate = actionType == ACTION_CREATE_DRAFT
                        val draftJson = inputData.getString("DRAFT_JSON")
                        if (draftJson == null) {
                            finalErrorMessage = "Draft details missing for $actionType on $entityId"
                        } else {
                            val draft = Json.decodeFromString<MessageDraft>(draftJson)
                            val result = if (isCreate) mailService.createDraft(draft, accountId)
                            else mailService.updateDraft(entityId, draft, accountId)
                            if (result.isSuccess) {
                                val (returnedId, _) = result.getOrThrow()
                                appDatabase.withTransaction {
                                    messageDao.updateDraftAfterSync(
                                        localDraftId = entityId,
                                        newServerId = returnedId, // Use server ID
                                        syncStatus = SyncStatus.SYNCED,
                                        subject = draft.subject, // Re-affirm from local draft
                                        // body might be updated by server, but local body is source of truth before send
                                        // recipient info also from local draft
                                        recipientNames = draft.to,
                                        recipientAddresses = draft.to
                                    )
                                    // If body content can change on server and needs to be re-synced:
                                    // messageBodyDao.updateContentAndStatus(returnedId, returnedBody, SyncStatus.SYNCED)
                                }
                                success = true
                            } else handleFailure(result)
                        }
                    }

                    else -> {
                        Timber.w("$TAG: Unknown actionType: $actionType for entityId: $entityId")
                        finalErrorMessage = "Unknown action type"
                    }
                }
            } catch (e: Exception) { // Catch-all for unexpected issues within the when block
                Timber.e(
                    e,
                    "$TAG: Exception while processing $actionType for $entityId inside when block"
                )
                finalErrorMessage = e.message ?: "Unexpected exception in worker's action handling"
                if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                    needsReAuth = true
                } // else, it's some other exception, re-auth not implied by this catch directly
            }

            if (success) {
                Timber.d("$TAG: Action $actionType for entity $entityId processed successfully.")
                return Result.success()
            } else {
                Timber.e("$TAG: Action $actionType for entity $entityId failed. Error: $finalErrorMessage")
                if (needsReAuth) {
                    Timber.w("$TAG: Marking account $accountId for re-authentication due to $actionType failure.")
                    try {
                        accountDao.setNeedsReauthentication(accountId, true)
                    } catch (dbException: Exception) {
                        Timber.e(
                            dbException,
                            "$TAG: Failed to mark account $accountId for re-authentication in DB."
                        )
                    }
                }
                // Update sync metadata to reflect error for this entity and action.
                try {
                    // This assumes entityId is a messageId. If it can be folderId, this needs adjustment.
                    // For now, ActionUploadWorker primarily deals with message-like actions.
                    messageDao.updateLastSyncError(
                        entityId,
                        finalErrorMessage ?: "Unknown server error",
                        SyncStatus.ERROR
                    )
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "$TAG: Failed to update message sync error status for $entityId after $actionType failure."
                    )
                }

                return Result.failure()
            }
        } catch (outerException: Exception) { // Catch exceptions from mailServiceSelector or initial setup
            Timber.e(
                outerException,
                "$TAG: Unhandled exception in ActionUploadWorker for $actionType, $entityId. Account $accountId"
            )
            // Check if this outer exception implies re-auth (less likely here, but good for robustness)
            if (outerException is ApiServiceException && outerException.errorDetails.isNeedsReAuth) {
                try {
                    accountDao.setNeedsReauthentication(
                        accountId!!,
                        true
                    ) // accountId should be non-null if we reached here
                    Timber.w("$TAG: Marking account $accountId for re-authentication due to outer exception.")
                } catch (dbException: Exception) {
                    Timber.e(
                        dbException,
                        "$TAG: Failed to mark account $accountId for re-auth in DB (outer exception)."
                    )
                }
            }
            return Result.failure()
        }
    }
}
