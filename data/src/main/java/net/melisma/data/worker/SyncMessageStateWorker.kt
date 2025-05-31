package net.melisma.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.MessageDao
import timber.log.Timber
import kotlin.Result as KotlinResult

@HiltWorker
class SyncMessageStateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>, // For unified error handling
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val accountRepository: AccountRepository, // To fetch account details
    private val appDatabase: AppDatabase
) : CoroutineWorker(appContext, workerParams) {

    // TODO: Get these from a common constants place if they grow
    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_MESSAGE_ID = "MESSAGE_ID"
        const val KEY_OPERATION_TYPE = "OPERATION_TYPE"
        const val KEY_IS_READ = "IS_READ" // For MarkRead/Unread
        const val KEY_IS_STARRED = "IS_STARRED" // For Star/Unstar
        const val KEY_NEW_FOLDER_ID = "NEW_FOLDER_ID" // For MoveMessage
        const val KEY_OLD_FOLDER_ID = "OLD_FOLDER_ID" // For MoveMessage

        // Operation Types
        const val OP_MARK_READ = "MARK_READ"
        const val OP_STAR_MESSAGE = "STAR_MESSAGE"
        const val OP_DELETE_MESSAGE = "DELETE_MESSAGE" // Added delete operation type
        const val OP_MOVE_MESSAGE = "MOVE_MESSAGE"
    }

    private val TAG = "SyncMsgStateWorker"

    override suspend fun doWork(): ListenableWorker.Result = withContext(ioDispatcher) {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val messageId = inputData.getString(KEY_MESSAGE_ID)
        val operationType = inputData.getString(KEY_OPERATION_TYPE)

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank() || operationType.isNullOrBlank()) {
            Timber.tag(TAG)
                .e("Missing vital input data: accountId, messageId, or operationType. Failing.")
            return@withContext ListenableWorker.Result.failure()
        }

        Timber.tag(TAG)
            .i("Starting work for messageId: $messageId, accountId: $accountId, operation: $operationType")

        val account = try {
            accountRepository.getAccountByIdSuspend(accountId) // Changed to suspend version
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch account details for accountId: $accountId")
            // updateSyncError is tricky here as we don't have messageId for it if account fetch fails early
            // Depending on how critical account fetch is, could retry or fail.
            return@withContext ListenableWorker.Result.retry()
        }

        if (account == null) {
            Timber.tag(TAG).e("Account not found for ID: $accountId. Cannot determine API service.")
            // This specific message ID won't be updated with this error, as it's an account-level issue.
            // The job will retry, and if the account remains missing, it will continue to retry.
            return@withContext ListenableWorker.Result.retry()
        }

        val providerType = account.providerType?.uppercase() // providerType is now nullable

        if (providerType == null) {
            Timber.tag(TAG)
                .e("Provider type could not be determined for account $accountId (account might be null or have no provider type). Retrying.")
            // updateSyncError is not called here because the issue is with the account data itself.
            return@withContext ListenableWorker.Result.retry()
        }

        val mailApiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (mailApiService == null) {
            Timber.tag(TAG)
                .e("Could not find MailApiService for provider: $providerType (account $accountId)")
            updateSyncErrorState(
                messageId,
                "API service not found for provider: $providerType",
                false
            )
            return@withContext ListenableWorker.Result.retry()
        }

        if (errorMapper == null) {
            Timber.tag(TAG)
                .e("Could not find ErrorMapperService for provider: $providerType (account $accountId)")
            updateSyncErrorState(
                messageId,
                "Error mapper service not found for provider: $providerType",
                false
            )
            return@withContext ListenableWorker.Result.retry() // Or Result.failure() if this is unrecoverable
        }

        try {
            val apiResult: KotlinResult<Unit> = when (operationType) {
                OP_MARK_READ -> {
                    val isRead = inputData.getBoolean(KEY_IS_READ, false)
                    Timber.tag(TAG)
                        .d("Executing $OP_MARK_READ for $messageId to $isRead using $providerType API")
                    mailApiService.markMessageRead(
                        messageId,
                        isRead
                    ) // mailApiService is now guaranteed non-null
                }

                OP_STAR_MESSAGE -> {
                    val isStarred = inputData.getBoolean(KEY_IS_STARRED, false)
                    Timber.tag(TAG)
                        .d("Executing $OP_STAR_MESSAGE for $messageId to $isStarred using $providerType API")
                    mailApiService.starMessage(
                        messageId,
                        isStarred
                    ) // mailApiService is now guaranteed non-null
                }

                OP_DELETE_MESSAGE -> {
                    Timber.tag(TAG)
                        .d("Executing $OP_DELETE_MESSAGE for $messageId using $providerType API")
                    mailApiService.deleteMessage(messageId) // mailApiService is now guaranteed non-null
                }

                OP_MOVE_MESSAGE -> {
                    val newFolderId = inputData.getString(KEY_NEW_FOLDER_ID)
                    val oldFolderId =
                        inputData.getString(KEY_OLD_FOLDER_ID) // oldFolderId is needed by Gmail API
                    if (newFolderId.isNullOrBlank()) {
                        Timber.tag(TAG)
                            .e("Missing newFolderId for $OP_MOVE_MESSAGE on $messageId. Failing.")
                        // Not updating sync error state here as it's a worker input data issue.
                        return@withContext ListenableWorker.Result.failure()
                    }
                    Timber.tag(TAG)
                        .d("Executing $OP_MOVE_MESSAGE for $messageId from '$oldFolderId' to '$newFolderId' using $providerType API")
                    // Note: currentFolderId (oldFolderId) might be null if it's not available or relevant for some providers,
                    // but MailApiService.moveMessage expects it. It can be an empty string if truly not applicable from source.
                    // The repository should ideally always try to provide it.
                    mailApiService.moveMessage(messageId, oldFolderId ?: "", newFolderId)
                }

                else -> {
                    Timber.tag(TAG).w("Unknown operation type: $operationType")
                    return@withContext ListenableWorker.Result.failure()
                }
            }
            return@withContext processApiResult(apiResult, messageId, operationType, errorMapper)
        } catch (e: Exception) { // Catch exceptions from the API calls directly if they aren't Result-wrapped
            Timber.tag(TAG).e(e, "Unhandled exception during API operation for $messageId")
            val errorDetails =
                errorMapper.mapExceptionToErrorDetails(e) // errorMapper is now guaranteed non-null
            updateSyncErrorState(
                messageId,
                errorDetails.message ?: "Unknown error during sync",
                false
            )
            return@withContext ListenableWorker.Result.retry()
        }
    }

    private suspend fun processApiResult(
        apiResult: KotlinResult<Unit>,
        messageId: String,
        operationType: String, // Added operationType
        errorMapper: ErrorMapperService // Pass errorMapper
    ): ListenableWorker.Result {
        if (apiResult.isSuccess) {
            Timber.tag(TAG)
                .i("API call successful for $messageId, operation $operationType.")
            appDatabase.withTransaction {
                if (operationType == OP_DELETE_MESSAGE) {
                    Timber.tag(TAG)
                        .d("Permanently deleting message $messageId from local DB after successful API deletion.")
                    messageDao.deletePermanentlyById(messageId)
                } else if (operationType == OP_MOVE_MESSAGE) {
                    val newFolderId = inputData.getString(KEY_NEW_FOLDER_ID)
                    if (newFolderId.isNullOrBlank()) {
                        // This should ideally not happen if validated before API call
                        Timber.tag(TAG)
                            .e("New folder ID is blank in processApiResult for move operation. Message $messageId sync state might be inconsistent.")
                        // Update error state to reflect this problem
                        messageDao.updateLastSyncError(
                            messageId,
                            "Move success, but new folder ID missing post-API call."
                        )
                    } else {
                        Timber.tag(TAG)
                            .d("Updating folderId to $newFolderId and clearing sync state for message $messageId after successful API move.")
                        messageDao.updateFolderIdAndClearSyncStateOnSuccess(messageId, newFolderId)
                    }
                } else {
                    Timber.tag(TAG)
                        .d("Clearing needsSync and lastSyncError for message $messageId.")
                    messageDao.clearSyncState(messageId) // Resets needsSync and lastSyncError
                }
            }
            return ListenableWorker.Result.success()
        } else {
            val exception = apiResult.exceptionOrNull()
            val errorDetails = exception?.let { errorMapper.mapExceptionToErrorDetails(it) }
            val errorMessage = errorDetails?.message ?: exception?.message ?: "Unknown API error"

            Timber.tag(TAG)
                .w("API call failed for $messageId, operation $operationType: $errorMessage")
            // For delete operations, if the API fails, we do not want to clear isLocallyDeleted.
            // We want to keep it marked for deletion attempt later. updateSyncErrorState will just set error and keep needsSync.
            // For move operations, if API fails, we want to revert folderId to oldFolderId if possible.
            if (operationType == OP_MOVE_MESSAGE) {
                val oldFolderId = inputData.getString(KEY_OLD_FOLDER_ID)
                val newFolderId = inputData.getString(KEY_NEW_FOLDER_ID) // for error message
                if (!oldFolderId.isNullOrBlank()) {
                    Timber.tag(TAG)
                        .w("Move operation failed for $messageId. Reverting folderId to $oldFolderId and setting error. Target was $newFolderId")
                    messageDao.updateFolderIdSyncErrorAndNeedsSync(
                        messageId = messageId,
                        folderId = oldFolderId,
                        errorMessage = "API move to '$newFolderId' failed: $errorMessage".take(500),
                        needsSync = true
                    )
                } else {
                    // Fallback if oldFolderId is not available (should be rare)
                    Timber.tag(TAG)
                        .w("Move operation failed for $messageId. OldFolderId not available. Updating error on current (new) folderId $newFolderId")
                    messageDao.updateLastSyncError(
                        messageId,
                        "API move to '$newFolderId' failed: $errorMessage".take(500)
                    )
                }
            } else {
                // Existing logic for other operations (like delete)
                val shouldClearLocalDeletionMarker =
                    false // For OP_DELETE_MESSAGE, a failure means we keep the marker
                updateSyncErrorState(messageId, errorMessage, shouldClearLocalDeletionMarker)
            }

            // TODO: Implement more sophisticated retry logic based on error type
            // For now, always retry for API failures.
            return ListenableWorker.Result.retry()
        }
    }

    private suspend fun updateSyncErrorState(
        messageId: String,
        errorMessage: String,
        clearLocallyDeletedOnError: Boolean
    ) {
        try {
            Timber.tag(TAG).d("Updating sync error for $messageId: $errorMessage")
            val message = messageDao.getMessageByIdSuspend(messageId)
            if (message != null) {
                appDatabase.withTransaction {
                    if (clearLocallyDeletedOnError && message.isLocallyDeleted) {
                        // This case might be for operations OTHER than delete, where an error means we revert optimistic state
                        messageDao.markAsLocallyDeleted(
                            messageId,
                            isLocallyDeleted = false,
                            needsSync = true
                        ) // Revert and keep needsSync for error
                    }
                    messageDao.updateLastSyncError(
                        messageId,
                        errorMessage.take(500)
                    ) // updateLastSyncError already sets needsSync = true
                }
            } else {
                Timber.tag(TAG)
                    .w("Message $messageId not found to update sync error. It might have been deleted.")
            }
        } catch (dbException: Exception) {
            Timber.tag(TAG).e(dbException, "Failed to update lastSyncError in DB for $messageId")
        }
    }
} 