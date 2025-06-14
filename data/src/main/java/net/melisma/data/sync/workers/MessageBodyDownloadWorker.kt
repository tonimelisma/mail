package net.melisma.data.sync.workers

import android.content.Context
// import android.net.ConnectivityManager // No longer needed
// import android.net.NetworkCapabilities // No longer needed
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.melisma.core_data.connectivity.NetworkMonitor // Added
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.preferences.DownloadPreference
import kotlinx.coroutines.flow.first
import timber.log.Timber

@HiltWorker
class MessageBodyDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val messageBodyDao: MessageBodyDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val networkMonitor: NetworkMonitor,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_MESSAGE_ID = "MESSAGE_ID"
        const val KEY_RESULT_BODY = "RESULT_BODY"
        const val KEY_RESULT_ERROR = "RESULT_ERROR"
    }

    private val TAG = "MessageBodyDownloadWorker"

    // Removed local isOnline() and isWifiConnected() methods

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val messageId = inputData.getString(KEY_MESSAGE_ID)

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank()) {
            Timber.e("$TAG: Missing required IDs in input data.")
            return Result.failure()
        }

        if (!networkMonitor.isOnline.first()) {
            Timber.i("$TAG: Network offline. Retrying later.")
            return Result.retry()
        }
        
        Timber.d("$TAG: Starting body download for message $messageId.")

        try {
            // Set status to PENDING_DOWNLOAD
            messageDao.setSyncStatus(messageId, EntitySyncStatus.PENDING_DOWNLOAD)

            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("$TAG: MailApiService not available for account $accountId. Aborting message body download.")
                return Result.failure()
            }

            val messageResult = mailService.getMessageContent(messageId)

            if (messageResult.isSuccess) {
                val messageWithBody = messageResult.getOrThrow()
                val bodyContent = messageWithBody.body
                val contentType = messageWithBody.bodyContentType
                val bodySize = bodyContent?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

                val bodyEntity = MessageBodyEntity(
                    messageId = messageId,
                    content = bodyContent,
                    contentType = contentType ?: "TEXT",
                    sizeInBytes = bodySize,
                    lastFetchedTimestamp = System.currentTimeMillis()
                )
                messageBodyDao.insertOrUpdateMessageBody(bodyEntity)
                messageDao.updateMessageBody(messageId, bodyContent ?: "")
                messageDao.setSyncStatus(messageId, EntitySyncStatus.SYNCED)

                Timber.i("$TAG: Successfully downloaded and saved body for message $messageId.")
                return Result.success()
            } else {
                val exception = messageResult.exceptionOrNull()
                val error = "API Error: ${exception?.message}"
                Timber.e(exception, "$TAG: Failed to download body for message $messageId. Error: $error")
                messageDao.setSyncError(messageId, error)

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(accountId, true)
                    Timber.w("$TAG: Marked account $accountId for re-authentication.")
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            val error = "Worker Error: ${e.message}"
            Timber.e(e, "$TAG: Unhandled exception downloading body for message $messageId. Error: $error")
            messageDao.setSyncError(messageId, error)
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                accountDao.setNeedsReauthentication(accountId, true)
                Timber.w("$TAG: Marked account $accountId for re-authentication.")
            }
            return Result.failure()
        }
    }
}
