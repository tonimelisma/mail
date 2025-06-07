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
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
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
    private val folderDao: FolderDao
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

        if (performAction(mailService, accountId, entityId, actionType, inputData.keyValueMap)) {
            Timber.d("$TAG: Action '$actionType' completed successfully for entity $entityId")
            return Result.success()
        } else {
            Timber.w("$TAG: Action '$actionType' failed for entity $entityId. Will retry.")
            return Result.retry()
        }
    }

    private suspend fun performAction(
        mailService: MailApiService,
        accountId: String,
        entityId: String,
        actionType: String,
        payload: Map<String, Any?>
    ): Boolean {
        val apiCallResult: Result<Any> = when (actionType) {
            ACTION_MARK_AS_READ, ACTION_MARK_AS_UNREAD -> {
                val isReadStr = payload[KEY_IS_READ] as? String
                val isRead = isReadStr?.toBoolean()
                if (isRead == null) {
                    Timber.e("$TAG: Missing 'isRead' payload for $actionType on entity $entityId")
                    return false
                }
                mailService.markMessageRead(entityId, isRead)
            }
            ACTION_STAR_MESSAGE -> {
                val isStarredStr = payload[KEY_IS_STARRED] as? String
                val isStarred = isStarredStr?.toBoolean()
                if (isStarred == null) {
                    Timber.e("$TAG: Missing 'isStarred' payload for $actionType on entity $entityId")
                    return false
                }
                mailService.starMessage(entityId, isStarred)
            }

            ACTION_DELETE_MESSAGE -> mailService.deleteMessage(entityId)
            ACTION_MOVE_MESSAGE -> {
                val targetFolderId = payload[KEY_NEW_FOLDER_ID] as? String
                val sourceFolderId = payload[KEY_OLD_FOLDER_ID] as? String
                when {
                    targetFolderId == null -> {
                        Timber.e("$TAG: Missing target folder ID for $actionType on entity $entityId")
                        return false
                    }

                    sourceFolderId == null -> {
                        Timber.e("$TAG: Missing source folder ID for $actionType on entity $entityId")
                        return false
                    }
                    else -> mailService.moveMessage(entityId, sourceFolderId, targetFolderId)
                }
            }
            ACTION_SEND_MESSAGE -> {
                val message = messageDao.getMessageByIdSuspend(entityId)
                if (message == null) {
                    Timber.e("$TAG: Message to send not found in DB: $entityId for $actionType")
                    return false
                }
                val draft = MessageDraft(
                    subject = message.subject ?: "",
                    body = message.body ?: ""
                )
                mailService.sendMessage(draft)
            }
            ACTION_CREATE_DRAFT -> {
                val draftJson = payload[KEY_DRAFT_DETAILS] as? String
                if (draftJson == null) {
                    Timber.w("$TAG: $actionType action missing 'draftJson' payload for entity $entityId.")
                    Result.failure(IllegalArgumentException("Missing 'draftJson' for CREATE_DRAFT"))
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

                            appDatabase.withTransaction {
                                if (serverMessage.id != entityId) {
                                    messageDao.deleteMessageById(entityId)
                                }
                                val newMessageEntity =
                                    serverMessage.toEntity(accountId, draftsFolderId ?: "")
                                messageDao.insertOrUpdateMessages(listOf(newMessageEntity))
                                Timber.d("$TAG: Draft $entityId (now ${serverMessage.id}) created and saved locally.")
                            }
                            Result.success(Unit)
                        } else {
                            Timber.e(
                                createResult.exceptionOrNull(),
                                "$TAG: $actionType API call failed for entity $entityId."
                            )
                            createResult
                        }
                    } catch (e: Exception) {
                        Timber.e(
                            e,
                            "$TAG: $actionType action failed to decode draftJson or during DB op for entity $entityId."
                        )
                        Result.failure(e)
                    }
                }
            }
            ACTION_UPDATE_DRAFT -> {
                val draftJson = payload[KEY_DRAFT_DETAILS] as? String
                if (draftJson == null) {
                    Timber.w("$TAG: $actionType action missing 'draftJson' payload for entity $entityId.")
                    Result.failure(IllegalArgumentException("Missing 'draftJson' for UPDATE_DRAFT"))
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

                            appDatabase.withTransaction {
                                val updatedMessageEntity =
                                    serverMessage.toEntity(accountId, draftsFolderId ?: "")
                                messageDao.insertOrUpdateMessages(listOf(updatedMessageEntity))
                                Timber.d("$TAG: Draft $entityId updated locally.")
                            }
                            Result.success(Unit)
                        } else {
                            Timber.e(
                                updateResult.exceptionOrNull(),
                                "$TAG: $actionType API call failed for entity $entityId."
                            )
                            updateResult
                        }
                    } catch (e: Exception) {
                        Timber.e(
                            e,
                            "$TAG: $actionType action failed to decode draftJson or during DB op for entity $entityId."
                        )
                        Result.failure(e)
                    }
                }
            }
            else -> {
                Timber.e("$TAG: Unknown action type: $actionType for entity $entityId")
                return false
            }
        }

        return if (apiCallResult.isSuccess) {
            try {
                appDatabase.withTransaction {
                    when (actionType) {
                        ACTION_DELETE_MESSAGE -> messageDao.deleteMessageById(entityId)
                        ACTION_MARK_AS_READ, ACTION_MARK_AS_UNREAD -> {
                            val isRead = (payload[KEY_IS_READ] as? String)?.toBoolean()
                                ?: return@withTransaction
                            messageDao.updateReadStatus(entityId, isRead)
                        }

                        ACTION_STAR_MESSAGE -> {
                            val isStarred = (payload[KEY_IS_STARRED] as? String)?.toBoolean()
                                ?: return@withTransaction
                            messageDao.updateStarredStatus(entityId, isStarred)
                        }

                        ACTION_MOVE_MESSAGE -> {
                            val newFolderId =
                                payload[KEY_NEW_FOLDER_ID] as? String ?: return@withTransaction
                            messageDao.updateFolderOnMoveSyncSuccess(entityId, newFolderId)
                        }

                        ACTION_SEND_MESSAGE -> {
                            val sentFolder = folderDao.getFolderByWellKnownTypeSuspend(
                                accountId,
                                WellKnownFolderType.SENT_ITEMS
                            )
                            if (sentFolder != null) {
                                messageDao.markAsSent(
                                    entityId,
                                    sentFolder.id,
                                    System.currentTimeMillis()
                                )
                                Timber.d("$TAG: Marked message $entityId as sent and moved to folder ${sentFolder.id}")
                            } else {
                                Timber.w("$TAG: Could not find Sent folder (type: ${WellKnownFolderType.SENT_ITEMS.name}) for account $accountId. Message $entityId not marked as sent locally.")
                            }
                        }
                    }
                }
                true
            } catch (dbException: Exception) {
                Timber.e(
                    dbException,
                    "$TAG: DB update failed after successful API call for action $actionType on entity $entityId."
                )
                false
            }
        } else {
            val error = apiCallResult.exceptionOrNull()
            Timber.e(
                error,
                "$TAG: API Error for action $actionType on entity $entityId: ${error?.message}"
            )
            false
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

