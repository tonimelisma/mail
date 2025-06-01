package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import net.melisma.core_db.dao.AttachmentDao
import timber.log.Timber
import java.io.File // Required for file operations

@HiltWorker
class AttachmentDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context, // Renamed for clarity
    @Assisted workerParams: WorkerParameters,
    private val attachmentDao: AttachmentDao
    // TODO: P1_SYNC - Inject MailApiServiceSelector or specific MailApiService
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "AttachmentDownloadWrkr"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString("ACCOUNT_ID")
        val messageId = inputData.getString("MESSAGE_ID")
        val attachmentId = inputData.getString("ATTACHMENT_ID")
        val attachmentName = inputData.getString("ATTACHMENT_NAME") // For creating file name

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank() || attachmentId.isNullOrBlank() || attachmentName.isNullOrBlank()) {
            Timber.e("Required ID (accountId, messageId, attachmentId, or attachmentName) missing in inputData.")
            return Result.failure()
        }

        Timber.d("Worker started for accountId: $accountId, messageId: $messageId, attachmentId: $attachmentId, name: $attachmentName")

        try {
            // TODO: P1_SYNC - Get MailApiService for accountId
            // TODO: P1_SYNC - Fetch attachment data for attachmentId from API.
            // Simulate network delay and getting byte array
            delay(2000)
            val attachmentData: ByteArray = "Simulated attachment data for $attachmentId".toByteArray() // Placeholder

            Timber.d("Simulated fetching attachment $attachmentId for message $messageId in account $accountId.")

            // Save the attachment data to internal storage
            val attachmentsDir = File(appContext.filesDir, "attachments/$messageId")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            val localFile = File(attachmentsDir, attachmentName)

            localFile.outputStream().use { it.write(attachmentData) }
            Timber.i("Attachment $attachmentName saved to ${localFile.absolutePath}")

            // TODO: P1_SYNC - Update AttachmentEntity in attachmentDao: set localFilePath, downloadTimestamp, and sync status to SYNCED.
            // Example:
            // attachmentDao.updateDownloadStatus(attachmentId, true, localFile.absolutePath, System.currentTimeMillis(), null, SyncStatus.SYNCED)

            Timber.d("Worker finished successfully for attachment $attachmentId")
            return Result.success()

        } catch (e: Exception) {
            Timber.e(e, "Error downloading attachment $attachmentId for message $messageId")
            // TODO: P1_SYNC - Update AttachmentEntity sync metadata to reflect error.
            return Result.failure()
        }
    }
}
