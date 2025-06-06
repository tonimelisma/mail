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
                val existingEntity = attachmentDao.getAttachmentByIdSuspend(attachmentId)
                if (existingEntity != null) {
                    val updatedEntity = existingEntity.copy(
                        isDownloaded = false,
                        localFilePath = null,
                        downloadTimestamp = null, // Explicitly null for consistency
                        lastSyncError = "Mail service not found",
                        syncStatus = SyncStatus.ERROR
                    )
                    attachmentDao.updateAttachment(updatedEntity)
                } else {
                    Timber.w("$TAG: AttachmentEntity $attachmentId not found to update status after mailService was null.")
                }
                return Result.failure()
            }

            // Set status to PENDING_DOWNLOAD before attempting download
            var entityToUpdate = attachmentDao.getAttachmentByIdSuspend(attachmentId)
            if (entityToUpdate != null) {
                entityToUpdate = entityToUpdate.copy(
                    isDownloaded = false,
                    localFilePath = entityToUpdate.localFilePath, // Preserve existing path if any, or nullify if fresh download
                    downloadTimestamp = null, // Reset timestamp for new attempt
                    lastSyncError = null, // Clear previous error
                    syncStatus = SyncStatus.PENDING_DOWNLOAD
                )
                attachmentDao.updateAttachment(entityToUpdate)
                Timber.d("Set attachment $attachmentId status to PENDING_DOWNLOAD.")
            } else {
                Timber.w("$TAG: AttachmentEntity $attachmentId not found to set as PENDING_DOWNLOAD.")
                // Optionally, handle this as a failure if entity *must* exist
            }

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

                entityToUpdate = attachmentDao.getAttachmentByIdSuspend(attachmentId)
                if (entityToUpdate != null) {
                    entityToUpdate = entityToUpdate.copy(
                        isDownloaded = true,
                        localFilePath = localFile.absolutePath,
                        downloadTimestamp = System.currentTimeMillis(),
                        lastSyncError = null,
                        syncStatus = SyncStatus.SYNCED
                    )
                    attachmentDao.updateAttachment(entityToUpdate)
                    Timber.d("Worker finished successfully for attachment $attachmentId")
                    return Result.success()
                } else {
                    Timber.e("$TAG: AttachmentEntity $attachmentId not found to mark as SYNCED. This is unexpected after successful download.")
                    return Result.failure() // Should not happen if previous steps worked
                }
            } else {
                val exception = downloadResult.exceptionOrNull()
                val errorMessage = exception?.message ?: "Failed to download attachment"
                Timber.e(exception, "Failed to download attachment $attachmentId: $errorMessage")
                entityToUpdate = attachmentDao.getAttachmentByIdSuspend(attachmentId)
                if (entityToUpdate != null) {
                    entityToUpdate = entityToUpdate.copy(
                        isDownloaded = false,
                        lastSyncError = errorMessage,
                        syncStatus = SyncStatus.ERROR
                        // localFilePath and downloadTimestamp might remain as they were or be nulled
                    )
                    attachmentDao.updateAttachment(entityToUpdate)
                }

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
            val existingEntityOnError = attachmentDao.getAttachmentByIdSuspend(attachmentId)
            if (existingEntityOnError != null) {
                val updatedEntityOnError = existingEntityOnError.copy(
                    isDownloaded = false,
                    lastSyncError = e.message ?: "Unknown error during download",
                    syncStatus = SyncStatus.ERROR
                )
                attachmentDao.updateAttachment(updatedEntityOnError)
            }
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
