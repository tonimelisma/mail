package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.AttachmentDao
import timber.log.Timber
import java.io.File

@HiltWorker
class AttachmentDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val attachmentDao: AttachmentDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val networkMonitor: NetworkMonitor
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_MESSAGE_ID = "MESSAGE_ID"
        const val KEY_ATTACHMENT_ID = "ATTACHMENT_ID"
        const val KEY_ATTACHMENT_NAME = "ATTACHMENT_NAME"
        const val KEY_RESULT_ERROR = "RESULT_ERROR"
    }

    private val TAG = "AttachmentDownloadWorker"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val messageId = inputData.getString(KEY_MESSAGE_ID)
        val attachmentId = inputData.getString(KEY_ATTACHMENT_ID)

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank() || attachmentId.isNullOrBlank()) {
            Timber.e("$TAG: Missing required IDs in input data.")
            return Result.failure()
        }

        if (!networkMonitor.isOnline.first()) {
            Timber.i("$TAG: Network offline. Retrying later.")
            return Result.retry()
        }

        Timber.d("$TAG: Starting attachment download for $attachmentId.")

        try {
            attachmentDao.updateSyncStatus(attachmentId, EntitySyncStatus.PENDING_DOWNLOAD, null)

            val attachmentEntity = attachmentDao.getAttachmentByIdSuspend(attachmentId)
                ?: return Result.failure().also { Timber.e("$TAG: Attachment $attachmentId not found in DB.") }

            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
                ?: return Result.retry().also { Timber.w("$TAG: Mail service not found for account $accountId. Retrying later.") }

            val downloadResult = mailService.downloadAttachment(messageId, attachmentId)

            if (downloadResult.isSuccess) {
                val attachmentData = downloadResult.getOrThrow()
                val attachmentsDir = File(appContext.filesDir, "attachments/$messageId")
                if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
                
                val localFile = File(attachmentsDir, attachmentEntity.fileName)
                localFile.outputStream().use { it.write(attachmentData) }

                attachmentDao.updateDownloadSuccess(
                    attachmentId = attachmentId,
                    localPath = localFile.absolutePath,
                    downloadTimestamp = System.currentTimeMillis()
                )

                Timber.i("$TAG: Successfully downloaded attachment $attachmentId to ${localFile.absolutePath}")
                return Result.success()
            } else {
                val exception = downloadResult.exceptionOrNull()
                val error = "API Error: ${exception?.message}"
                Timber.e(exception, "$TAG: Failed to download attachment $attachmentId: $error")
                attachmentDao.updateSyncStatus(attachmentId, EntitySyncStatus.ERROR, error)

                if (exception is ApiServiceException && exception.errorDetails.isNeedsReAuth) {
                    accountDao.setNeedsReauthentication(accountId, true)
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            val error = "Worker Error: ${e.message}"
            Timber.e(e, "$TAG: Error downloading attachment $attachmentId: $error")
            attachmentDao.updateSyncStatus(attachmentId, EntitySyncStatus.ERROR, error)
            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                accountDao.setNeedsReauthentication(accountId, true)
            }
            return Result.failure()
        }
    }
} 