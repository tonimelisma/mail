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
            // Get MailApiService for accountId
            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("MailService not found for account $accountId. Failing message body download.")
                messageBodyDao.updateSyncStatusAndError(
                    messageId,
                    SyncStatus.ERROR,
                    "Mail service not found"
                )
                return Result.failure()
            }

            // Update MessageBodyEntity status to PENDING_DOWNLOAD before API call
            // Fetch existing to update it, or prepare for insert if it doesn't exist
            var bodyEntityToUpdate = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            if (bodyEntityToUpdate == null) {
                bodyEntityToUpdate = MessageBodyEntity(
                    messageId = messageId,
                    content = null, // Content will be fetched
                    contentType = null, // ContentType will be fetched
                    lastFetchedTimestamp = System.currentTimeMillis(), // Placeholder, will be updated on success
                    syncStatus = SyncStatus.PENDING_DOWNLOAD,
                    lastSyncAttemptTimestamp = System.currentTimeMillis()
                )
            } else {
                bodyEntityToUpdate = bodyEntityToUpdate.copy(
                    syncStatus = SyncStatus.PENDING_DOWNLOAD,
                    lastSyncError = null, // Clear previous error
                    lastSyncAttemptTimestamp = System.currentTimeMillis()
                )
            }
            messageBodyDao.insertOrUpdateMessageBody(bodyEntityToUpdate)
            Timber.d("Set message body $messageId status to PENDING_DOWNLOAD.")

            // Fetch message body for messageId from API.
            val bodyResult = mailService.getMessageBody(messageId, accountId)

            if (bodyResult.isSuccess) {
                val (bodyContent, contentType) = bodyResult.getOrThrow()
                Timber.d("Fetched body for message $messageId. ContentType: $contentType")

                val bodyEntity = MessageBodyEntity(
                    messageId = messageId,
                    content = bodyContent,
                    contentType = contentType ?: "text/plain", // Default if null
                    lastFetchedTimestamp = System.currentTimeMillis(),
                    syncStatus = SyncStatus.SYNCED,
                    lastSuccessfulSyncTimestamp = System.currentTimeMillis()
                )
                messageBodyDao.insertOrUpdateMessageBody(bodyEntity)
                Timber.d("Saved message body for $messageId to DB.")
                return Result.success()
            } else {
                val exception = bodyResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to fetch message body"
                Timber.e(exception, "Failed to fetch body for message $messageId: $errorMessage")
                messageBodyDao.updateSyncStatusAndError(messageId, SyncStatus.ERROR, errorMessage)

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
            // Update sync metadata to reflect error for this message body.
            // This is already handled in the 'else' branch of bodyResult.isSuccess if the specific API call failed.
            // However, if another exception occurs (e.g., before API call, or DB issue after),
            // ensure the status is ERROR.
            messageBodyDao.updateSyncStatusAndError(
                messageId,
                SyncStatus.ERROR,
                e.message ?: "Unknown error in worker"
            )

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
