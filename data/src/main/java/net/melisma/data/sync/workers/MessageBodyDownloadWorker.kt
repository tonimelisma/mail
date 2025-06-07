package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.entity.MessageBodyEntity
import timber.log.Timber

@HiltWorker
class MessageBodyDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageBodyDao: MessageBodyDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "MessageBodyDownloadWrkr"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString("ACCOUNT_ID")
        val messageId = inputData.getString("MESSAGE_ID")

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank()) {
            Timber.e("Required ID (accountId or messageId) missing in inputData.")
            return Result.failure()
        }

        Timber.d("Worker started for accountId: $accountId, messageId: $messageId")

        try {
            // Check if body is already successfully synced
            val existingBodyEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            if (existingBodyEntity != null && existingBodyEntity.syncStatus == SyncStatus.SYNCED && existingBodyEntity.content != null) {
                Timber.i("$TAG: Message body for $messageId already synced. Skipping download.")
                // Optionally update lastSuccessfulSyncTimestamp if we want to track "access" or "check" time
                // messageBodyDao.insertOrUpdateMessageBody(existingBodyEntity.copy(lastSuccessfulSyncTimestamp = System.currentTimeMillis()))
                return Result.success()
            }

            // Get MailApiService for accountId
            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("MailService not found for account $accountId. Failing message body download.")
                var errorEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
                if (errorEntity == null) {
                    errorEntity = MessageBodyEntity(
                        messageId = messageId,
                        content = null,
                        syncStatus = SyncStatus.ERROR,
                        lastSyncError = "Mail service not found",
                        lastSyncAttemptTimestamp = System.currentTimeMillis()
                    )
                } else {
                    errorEntity = errorEntity.copy(
                        content = null,
                        syncStatus = SyncStatus.ERROR,
                        lastSyncError = "Mail service not found",
                        lastSyncAttemptTimestamp = System.currentTimeMillis()
                    )
                }
                messageBodyDao.insertOrUpdateMessageBody(errorEntity)
                return Result.failure()
            }

            // Update MessageBodyEntity status to PENDING_DOWNLOAD before API call
            var bodyEntityToUpdate = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            if (bodyEntityToUpdate == null) {
                bodyEntityToUpdate = MessageBodyEntity(
                    messageId = messageId,
                    content = null,
                    syncStatus = SyncStatus.PENDING_DOWNLOAD,
                    lastSyncAttemptTimestamp = System.currentTimeMillis(),
                    lastSuccessfulSyncTimestamp = null,
                    lastSyncError = null
                )
            } else {
                bodyEntityToUpdate = bodyEntityToUpdate.copy(
                    content = if (bodyEntityToUpdate.syncStatus != SyncStatus.SYNCED) null else bodyEntityToUpdate.content,
                    syncStatus = SyncStatus.PENDING_DOWNLOAD,
                    lastSyncError = null,
                    lastSyncAttemptTimestamp = System.currentTimeMillis()
                )
            }
            messageBodyDao.insertOrUpdateMessageBody(bodyEntityToUpdate)
            Timber.d("Set message body $messageId status to PENDING_DOWNLOAD.")

            // Fetch message body for messageId from API.
            val bodyResult =
                mailService.getMessageContent(messageId) // Corrected method call, removed accountId

            if (bodyResult.isSuccess) {
                val messageWithBody = bodyResult.getOrThrow() // Get the Message object
                val bodyContent = messageWithBody.body // Access body property
                val contentType = messageWithBody.bodyContentType // Access bodyContentType property
                Timber.d("Fetched body for message $messageId. ContentType: $contentType")

                val bodySizeInBytes = bodyContent?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

                // Create a new entity or update existing one with fetched content
                var successEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
                if (successEntity == null) {
                    successEntity = MessageBodyEntity(
                        messageId = messageId,
                        content = bodyContent,
                        contentType = contentType ?: "TEXT",
                        sizeInBytes = bodySizeInBytes,
                        lastFetchedTimestamp = System.currentTimeMillis(),
                        syncStatus = SyncStatus.SYNCED,
                        lastSuccessfulSyncTimestamp = System.currentTimeMillis(),
                        lastSyncAttemptTimestamp = System.currentTimeMillis(),
                        lastSyncError = null
                    )
                } else {
                    successEntity = successEntity.copy(
                        content = bodyContent,
                        contentType = contentType ?: "TEXT",
                        sizeInBytes = bodySizeInBytes,
                        lastFetchedTimestamp = System.currentTimeMillis(),
                        syncStatus = SyncStatus.SYNCED,
                        lastSuccessfulSyncTimestamp = System.currentTimeMillis(),
                        lastSyncAttemptTimestamp = System.currentTimeMillis(),
                        lastSyncError = null
                    )
                }
                messageBodyDao.insertOrUpdateMessageBody(successEntity)
                Timber.d("Saved message body for $messageId to DB.")
                return Result.success()
            } else {
                val exception = bodyResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to fetch message body"
                Timber.e(exception, "Failed to fetch body for message $messageId: $errorMessage")
                var failureEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
                if (failureEntity == null) {
                    failureEntity = MessageBodyEntity(
                        messageId = messageId,
                        content = null,
                        sizeInBytes = 0L,
                        syncStatus = SyncStatus.ERROR,
                        lastSyncError = errorMessage,
                        lastSyncAttemptTimestamp = System.currentTimeMillis()
                    )
                } else {
                    failureEntity = failureEntity.copy(
                        content = null,
                        sizeInBytes = if (failureEntity.content == null) 0L else failureEntity.sizeInBytes,
                        syncStatus = SyncStatus.ERROR,
                        lastSyncError = errorMessage,
                        lastSyncAttemptTimestamp = System.currentTimeMillis()
                    )
                }
                messageBodyDao.insertOrUpdateMessageBody(failureEntity)

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    Timber.w("$TAG: Marking account $accountId for re-authentication due to message body download failure.")
                    try {
                        accountDao.setNeedsReauthentication(accountId, true)
                    } catch (dbException: Exception) {
                        Timber.e(
                            dbException,
                            "$TAG: Failed to mark account $accountId for re-authentication in DB."
                        )
                    }
                }
                return Result.failure()
            }

        } catch (e: Exception) {
            Timber.e(e, "Error downloading message body for accountId: $accountId, messageId: $messageId")
            var generalErrorEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            if (generalErrorEntity == null) {
                generalErrorEntity = MessageBodyEntity(
                    messageId = messageId,
                    content = null,
                    sizeInBytes = 0L,
                    syncStatus = SyncStatus.ERROR,
                    lastSyncError = e.message ?: "Unknown error in worker",
                    lastSyncAttemptTimestamp = System.currentTimeMillis()
                )
            } else {
                generalErrorEntity = generalErrorEntity.copy(
                    content = null,
                    sizeInBytes = if (generalErrorEntity.content == null) 0L else generalErrorEntity.sizeInBytes,
                    syncStatus = SyncStatus.ERROR,
                    lastSyncError = e.message ?: "Unknown error in worker",
                    lastSyncAttemptTimestamp = System.currentTimeMillis()
                )
            }
            messageBodyDao.insertOrUpdateMessageBody(generalErrorEntity)

            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                Timber.w("$TAG: Marking account $accountId for re-authentication due to outer exception during message body download.")
                try {
                    accountDao.setNeedsReauthentication(accountId, true)
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
