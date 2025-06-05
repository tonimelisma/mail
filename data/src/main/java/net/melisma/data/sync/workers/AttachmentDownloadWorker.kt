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
import net.melisma.core_db.dao.AttachmentDao
import timber.log.Timber
import java.io.File

@HiltWorker
class AttachmentDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context, // Renamed for clarity
    @Assisted workerParams: WorkerParameters,
    private val attachmentDao: AttachmentDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao // Inject AccountDao
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
            // Get MailApiService for accountId
            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                Timber.e("MailService not found for account $accountId. Failing download.")
                attachmentDao.updateDownloadStatus(
                    attachmentId,
                    false,
                    null,
                    null,
                    "Mail service not found",
                    SyncStatus.ERROR
                )
                return Result.failure()
            }

            // Set status to PENDING_DOWNLOAD before attempting download
            attachmentDao.updateDownloadStatus(
                attachmentId = attachmentId,
                isDownloaded = false,
                localFilePath = null, // Keep localFilePath as is, or clear if re-downloading
                timestamp = null, // Don't update timestamp yet
                error = null, // Clear previous error
                syncStatus = SyncStatus.PENDING_DOWNLOAD
            )
            Timber.d("Set attachment $attachmentId status to PENDING_DOWNLOAD.")

            // Fetch attachment data for attachmentId from API.
            val downloadResult = mailService.downloadAttachment(messageId, attachmentId)

            if (downloadResult.isSuccess) {
                val attachmentData = downloadResult.getOrThrow()
                Timber.d("Fetched attachment $attachmentId for message $messageId.")

                // Save the attachment data to internal storage
                val attachmentsDir = File(appContext.filesDir, "attachments/$messageId")
                if (!attachmentsDir.exists()) {
                    attachmentsDir.mkdirs()
                }
                val localFile = File(attachmentsDir, attachmentName)

                localFile.outputStream().use { it.write(attachmentData) }
                Timber.i("Attachment $attachmentName saved to ${localFile.absolutePath}")

                attachmentDao.updateDownloadStatus(
                    attachmentId = attachmentId,
                    isDownloaded = true,
                    localFilePath = localFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    error = null,
                    syncStatus = SyncStatus.SYNCED
                )
                Timber.d("Worker finished successfully for attachment $attachmentId")
                return Result.success()
            } else {
                val exception = downloadResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to download attachment"
                Timber.e(exception, "Failed to download attachment $attachmentId: $errorMessage")
                attachmentDao.updateDownloadStatus(
                    attachmentId,
                    false,
                    null,
                    null,
                    errorMessage,
                    SyncStatus.ERROR
                )

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    Timber.w("$TAG: Marking account $accountId for re-authentication due to attachment download failure.")
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
            Timber.e(e, "Error downloading attachment $attachmentId for message $messageId")
            // Update AttachmentEntity sync metadata to reflect error.
            attachmentDao.updateDownloadStatus(
                attachmentId,
                false,
                null,
                null,
                e.message ?: "Unknown error during download",
                SyncStatus.ERROR
            )
            // Check if this exception implies re-auth
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                Timber.w("$TAG: Marking account $accountId for re-authentication due to outer exception during attachment download.")
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
