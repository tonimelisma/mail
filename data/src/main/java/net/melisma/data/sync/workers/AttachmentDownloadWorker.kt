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
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.preferences.DownloadPreference
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File

@HiltWorker
class AttachmentDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val attachmentDao: AttachmentDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    // private val connectivityManager: ConnectivityManager, // Replaced
    private val networkMonitor: NetworkMonitor // Injected
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_MESSAGE_ID = "MESSAGE_ID"
        const val KEY_ATTACHMENT_ID = "ATTACHMENT_ID"
        const val KEY_ATTACHMENT_NAME = "ATTACHMENT_NAME"
        const val KEY_RESULT_ATTACHMENT_URI = "RESULT_ATTACHMENT_URI"
        const val KEY_RESULT_ERROR = "RESULT_ERROR"
    }

    private val TAG = "AttachmentDownloadWrkr"

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val messageId = inputData.getString(KEY_MESSAGE_ID)
        val attachmentId = inputData.getString(KEY_ATTACHMENT_ID)
        val attachmentName = inputData.getString(KEY_ATTACHMENT_NAME)

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank() || attachmentId.isNullOrBlank() || attachmentName.isNullOrBlank()) {
            val errorMsg = "Required ID (accountId, messageId, attachmentId, or attachmentName) missing"
            Timber.e("$TAG: $errorMsg in inputData.")
            val outputData = Data.Builder().putString(KEY_RESULT_ERROR, errorMsg).build()
            return Result.failure(outputData)
        }

        Timber.d("$TAG: Worker started for accountId: $accountId, messageId: $messageId, attachmentId: $attachmentId, name: $attachmentName")

        try {
            val preferences = userPreferencesRepository.userPreferencesFlow.first()
            val attachmentPreference = preferences.attachmentDownloadPreference

            Timber.d("$TAG: Attachment download preference for $attachmentId: $attachmentPreference")

            val currentIsOnline = networkMonitor.isOnline.first()
            val currentIsWifi = networkMonitor.isWifiConnected.first()

            if (attachmentPreference == DownloadPreference.ON_WIFI && !currentIsWifi) {
                Timber.i("$TAG: Preference is ON_WIFI, but not connected to Wi-Fi. Retrying for attachment $attachmentId.")
                return Result.retry()
            }

            if (!currentIsOnline) {
                Timber.i("$TAG: No internet connection. Retrying for attachment $attachmentId (preference: $attachmentPreference).")
                return Result.retry()
            }

            // Get MailApiService for accountId
            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                val errorMsg = "MailService not found for account $accountId"
                Timber.e("$TAG: $errorMsg. Failing download.")
                val existingEntity = attachmentDao.getAttachmentByIdSuspend(attachmentId)
                if (existingEntity != null) {
                    val updatedEntity = existingEntity.copy(
                        isDownloaded = false,
                        localFilePath = null,
                        downloadTimestamp = null,
                        lastSyncError = errorMsg,
                        syncStatus = SyncStatus.ERROR
                    )
                    attachmentDao.updateAttachment(updatedEntity)
                } else {
                    Timber.w("$TAG: AttachmentEntity $attachmentId not found to update status after mailService was null.")
                }
                val outputData = Data.Builder().putString(KEY_RESULT_ERROR, errorMsg).build()
                return Result.failure(outputData)
            }

            var entityToUpdate = attachmentDao.getAttachmentByIdSuspend(attachmentId)
            if (entityToUpdate != null) {
                entityToUpdate = entityToUpdate.copy(
                    isDownloaded = false,
                    localFilePath = entityToUpdate.localFilePath,
                    downloadTimestamp = null,
                    lastSyncError = null,
                    syncStatus = SyncStatus.PENDING_DOWNLOAD
                )
                attachmentDao.updateAttachment(entityToUpdate)
                Timber.d("Set attachment $attachmentId status to PENDING_DOWNLOAD.")
            } else {
                Timber.w("$TAG: AttachmentEntity $attachmentId not found to set as PENDING_DOWNLOAD.")
            }

            val downloadResult = mailService.downloadAttachment(messageId, attachmentId)

            if (downloadResult.isSuccess) {
                val attachmentData = downloadResult.getOrThrow()
                Timber.d("Fetched attachment $attachmentId for message $messageId.")

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
                    val outputData = Data.Builder().putString(KEY_RESULT_ATTACHMENT_URI, localFile.absolutePath).build()
                    return Result.success(outputData)
                } else {
                    val errorMsg = "AttachmentEntity $attachmentId not found to mark as SYNCED"
                    Timber.e("$TAG: $errorMsg. This is unexpected after successful download.")
                    val outputData = Data.Builder().putString(KEY_RESULT_ERROR, errorMsg).build()
                    return Result.failure(outputData)
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
                val outputData = Data.Builder().putString(KEY_RESULT_ERROR, errorMessage).build()
                return Result.failure(outputData)
            }
        } catch (e: Exception) {
            val generalErrorMessage = e.message ?: "Unknown error during download"
            Timber.e(e, "Error downloading attachment $attachmentId for message $messageId. Error: $generalErrorMessage")
            val existingEntityOnError = attachmentDao.getAttachmentByIdSuspend(attachmentId)
            if (existingEntityOnError != null) {
                val updatedEntityOnError = existingEntityOnError.copy(
                    isDownloaded = false,
                    lastSyncError = generalErrorMessage,
                    syncStatus = SyncStatus.ERROR
                )
                attachmentDao.updateAttachment(updatedEntityOnError)
            }
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
            val outputData = Data.Builder().putString(KEY_RESULT_ERROR, generalErrorMessage).build()
            return Result.failure(outputData)
        }
    }
}
