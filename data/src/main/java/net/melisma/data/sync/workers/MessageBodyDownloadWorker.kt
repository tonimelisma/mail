package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import net.melisma.core_db.dao.MessageBodyDao
import timber.log.Timber

@HiltWorker
class MessageBodyDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageBodyDao: MessageBodyDao
    // TODO: P1_SYNC - Inject MailApiServiceSelector or specific MailApiService
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
            // TODO: P1_SYNC - Get MailApiService for accountId
            // TODO: P1_SYNC - Fetch message body for messageId from API.
            // Simulate network delay
            delay(1000)
            val fetchedBodyContent = "<html><body><p>Simulated fetched body for $messageId.</p></body></html>" // Placeholder
            val fetchedContentType = "text/html" // Placeholder

            Timber.d("Simulated fetching body for message $messageId in account $accountId.")

            // TODO: P1_SYNC - Create MessageBodyEntity from API response.
            // TODO: P1_SYNC - Save MessageBodyEntity to messageBodyDao.
            // TODO: P1_SYNC - Update sync metadata for this message body.

            Timber.d("Worker finished successfully for accountId: $accountId, messageId: $messageId")
            return Result.success()

        } catch (e: Exception) {
            Timber.e(e, "Error downloading message body for accountId: $accountId, messageId: $messageId")
            // TODO: P1_SYNC - Update sync metadata to reflect error for this message body.
            return Result.failure()
        }
    }
}
