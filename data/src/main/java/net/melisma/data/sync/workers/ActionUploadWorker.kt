package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.model.MessageDraft
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

        val account = accountDao.getAccountByIdSuspend(accountId)
        if (account == null) {
            Timber.e("$TAG: Account not found for ID: $accountId. Failing task.")
            return Result.failure()
        }

        val mailService = mailApiServiceSelector.getServiceByProviderType(account.providerType)
        if (mailService == null) {
            Timber.e("$TAG: Could not get MailService for provider ${account.providerType}. Failing task.")
            return Result.failure()
        }

        return try {
            val success =
                performAction(mailService, accountId, entityId, actionType, inputData.keyValueMap)
            if (success) {
                Timber.d("$TAG: Action '$actionType' completed successfully for entity $entityId")
                Result.success()
            } else {
                Timber.w("$TAG: Action '$actionType' failed for entity $entityId")
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Exception during action '$actionType' for entity $entityId")
            Result.retry()
        }
    }

    private suspend fun performAction(
        mailService: MailApiService,
        accountId: String,
        entityId: String,
        actionType: String,
        payload: Map<String, Any?>
    ): Boolean {
        val result: Result<out Any> = when (actionType) {
            ACTION_MARK_AS_READ, ACTION_MARK_AS_UNREAD -> {
                val isReadStr = payload[KEY_IS_READ] as? String
                val isRead = isReadStr?.toBoolean()
                if (isRead == null) {
                    Result.failure(IllegalArgumentException("Missing 'isRead' payload"))
                } else {
                    mailService.markMessageRead(entityId, isRead)
                }
            }
            ACTION_STAR_MESSAGE -> {
                val isStarredStr = payload[KEY_IS_STARRED] as? String
                val isStarred = isStarredStr?.toBoolean()
                if (isStarred == null) {
                    Result.failure(IllegalArgumentException("Missing 'isStarred' payload"))
                } else {
                    mailService.starMessage(entityId, isStarred)
                }
            }

            ACTION_DELETE_MESSAGE -> mailService.deleteMessage(entityId)
            ACTION_MOVE_MESSAGE -> {
                val targetFolderId = payload[KEY_NEW_FOLDER_ID] as? String
                val sourceFolderId = payload[KEY_OLD_FOLDER_ID] as? String
                when {
                    targetFolderId == null -> Result.failure(IllegalArgumentException("Missing target folder ID"))
                    sourceFolderId == null -> Result.failure(IllegalArgumentException("Missing source folder ID"))
                    else -> mailService.moveMessage(entityId, sourceFolderId, targetFolderId)
                }
            }
            ACTION_SEND_MESSAGE -> {
                val message = messageDao.getMessageByIdSuspend(entityId)
                if (message == null) {
                    Result.failure(IllegalArgumentException("Message to send not found in DB: $entityId"))
                } else {
                    val draft = MessageDraft(
                        subject = message.subject ?: "",
                        body = message.body ?: ""
                    )
                    mailService.sendMessage(draft)
                }
            }
            ACTION_CREATE_DRAFT -> {
                val draftJson = payload[KEY_DRAFT_DETAILS] as? String
                if (draftJson == null) {
                    Timber.w("$TAG: CREATE_DRAFT action missing 'draftJson' payload for entity $entityId.")
                    Result.failure(IllegalArgumentException("Missing 'draftJson' for CREATE_DRAFT"))
                } else {
                    try {
                        val draft = Json.decodeFromString<MessageDraft>(draftJson)
                        mailService.createDraftMessage(draft)
                    } catch (e: Exception) {
                        Timber.e(
                            e,
                            "$TAG: CREATE_DRAFT action failed to decode draftJson for entity $entityId."
                        )
                        Result.failure(e)
                    }
                }
            }
            ACTION_UPDATE_DRAFT -> {
                val draftJson = payload[KEY_DRAFT_DETAILS] as? String
                if (draftJson == null) {
                    Timber.w("$TAG: UPDATE_DRAFT action missing 'draftJson' payload for entity $entityId.")
                    Result.failure(IllegalArgumentException("Missing 'draftJson' for UPDATE_DRAFT"))
                } else {
                    try {
                        val draft = Json.decodeFromString<MessageDraft>(draftJson)
                        mailService.updateDraftMessage(entityId, draft)
                    } catch (e: Exception) {
                        Timber.e(
                            e,
                            "$TAG: UPDATE_DRAFT action failed to decode draftJson for entity $entityId."
                        )
                        Result.failure(e)
                    }
                }
            }
            else -> {
                Result.failure(IllegalArgumentException("Unknown action type: $actionType"))
            }
        }

        if (result.isSuccess) {
            appDatabase.withTransaction {
                when (actionType) {
                    ACTION_DELETE_MESSAGE -> messageDao.deleteMessageById(entityId)
                }
            }
        } else {
            val error = result.exceptionOrNull()
            Timber.e(
                error,
                "API Error for action $actionType on entity $entityId: ${error?.message}"
            )
            messageDao.updateLastSyncError(entityId, error?.message ?: "Unknown API error")
        }

        return result.isSuccess
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
