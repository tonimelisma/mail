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
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.entity.MessageBodyEntity
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.preferences.DownloadPreference
import kotlinx.coroutines.flow.first
import timber.log.Timber

@HiltWorker
class MessageBodyDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageBodyDao: MessageBodyDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val accountDao: AccountDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    // private val connectivityManager: ConnectivityManager, // Replaced
    private val networkMonitor: NetworkMonitor // Injected
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
        const val KEY_MESSAGE_ID = "MESSAGE_ID"
        const val KEY_RESULT_BODY = "RESULT_BODY"
        const val KEY_RESULT_ERROR = "RESULT_ERROR"
    }

    private val TAG = "MessageBodyDownloadWrkr"

    // Removed local isOnline() and isWifiConnected() methods

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val messageId = inputData.getString(KEY_MESSAGE_ID)

        if (accountId.isNullOrBlank() || messageId.isNullOrBlank()) {
            val errorMsg = "Required ID (accountId or messageId) missing"
            Timber.e("$TAG: $errorMsg in inputData.")
            val outputData = Data.Builder().putString(KEY_RESULT_ERROR, errorMsg).build()
            return Result.failure(outputData)
        }

        Timber.d("$TAG: Worker started for accountId: $accountId, messageId: $messageId")

        try {
            val preferences = userPreferencesRepository.userPreferencesFlow.first()
            val bodyPreference = preferences.bodyDownloadPreference

            Timber.d("$TAG: Body download preference for $messageId: $bodyPreference")

            val currentIsOnline = networkMonitor.isOnline.first()
            val currentIsWifi = networkMonitor.isWifiConnected.first()

            if (bodyPreference == DownloadPreference.ON_WIFI && !currentIsWifi) {
                Timber.i("$TAG: Preference is ON_WIFI, but not connected to Wi-Fi. Retrying for message $messageId.")
                return Result.retry()
            }

            if (!currentIsOnline) {
                Timber.i("$TAG: No internet connection. Retrying for message $messageId (preference: $bodyPreference).")
                return Result.retry()
            }

            val existingBodyEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            if (existingBodyEntity != null && existingBodyEntity.syncStatus == SyncStatus.SYNCED && existingBodyEntity.content != null) {
                Timber.i("$TAG: Message body for $messageId already synced. Skipping download.")
                return Result.success()
            }

            val mailService = mailApiServiceSelector.getServiceByAccountId(accountId)
            if (mailService == null) {
                val errorMsg = "Mail service not found for account $accountId"
                Timber.e("$TAG: $errorMsg. Failing message body download.")
                var errorEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
                if (errorEntity == null) {
                    errorEntity = MessageBodyEntity(
                        messageId = messageId,
                        content = null,
                        syncStatus = SyncStatus.ERROR,
                        lastSyncError = errorMsg,
                        lastSyncAttemptTimestamp = System.currentTimeMillis()
                    )
                } else {
                    errorEntity = errorEntity.copy(
                        content = null,
                        syncStatus = SyncStatus.ERROR,
                        lastSyncError = errorMsg,
                        lastSyncAttemptTimestamp = System.currentTimeMillis()
                    )
                }
                messageBodyDao.insertOrUpdateMessageBody(errorEntity)
                val outputData = Data.Builder().putString(KEY_RESULT_ERROR, errorMsg).build()
                return Result.failure(outputData)
            }

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

            val bodyResult = mailService.getMessageContent(messageId)

            if (bodyResult.isSuccess) {
                val messageWithBody = bodyResult.getOrThrow()
                val bodyContent = messageWithBody.body
                val contentType = messageWithBody.bodyContentType
                Timber.d("Fetched body for message $messageId. ContentType: $contentType")

                val bodySizeInBytes = bodyContent?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

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
                        Timber.e(dbException, "$TAG: Failed to mark account $accountId for re-authentication in DB.")
                    }
                }
                val outputData = Data.Builder().putString(KEY_RESULT_ERROR, errorMessage).build()
                return Result.failure(outputData)
            }

        } catch (e: Exception) {
            val generalErrorMessage = e.message ?: "Unknown error in worker"
            Timber.e(e, "$TAG: Error downloading message body for accountId: $accountId, messageId: $messageId. Error: $generalErrorMessage")
            var generalErrorEntity = messageBodyDao.getMessageBodyByIdSuspend(messageId)
            if (generalErrorEntity == null) {
                generalErrorEntity = MessageBodyEntity(
                    messageId = messageId,
                    content = null,
                    sizeInBytes = 0L,
                    syncStatus = SyncStatus.ERROR,
                    lastSyncError = generalErrorMessage,
                    lastSyncAttemptTimestamp = System.currentTimeMillis()
                )
            } else {
                generalErrorEntity = generalErrorEntity.copy(
                    content = null,
                    sizeInBytes = if (generalErrorEntity.content == null) 0L else generalErrorEntity.sizeInBytes,
                    syncStatus = SyncStatus.ERROR,
                    lastSyncError = generalErrorMessage,
                    lastSyncAttemptTimestamp = System.currentTimeMillis()
                )
            }
            messageBodyDao.insertOrUpdateMessageBody(generalErrorEntity)

            if (e is ApiServiceException && e.errorDetails.isNeedsReAuth) {
                Timber.w("$TAG: Marking account $accountId for re-authentication due to outer exception during message body download.")
                try {
                    accountDao.setNeedsReauthentication(accountId, true)
                } catch (dbException: Exception) {
                    Timber.e(dbException, "$TAG: Failed to mark account $accountId for re-auth in DB (outer exception).")
                }
            }
            val outputData = Data.Builder().putString(KEY_RESULT_ERROR, generalErrorMessage).build()
            return Result.failure(outputData)
        }
    }
}
