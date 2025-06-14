package net.melisma.data.sync.work

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import net.melisma.data.sync.workers.ActionUploadWorker
import net.melisma.data.sync.workers.AttachmentDownloadWorker
import net.melisma.data.sync.workers.FolderSyncWorker
import net.melisma.data.sync.workers.MessageBodyDownloadWorker
import net.melisma.data.sync.workers.CacheCleanupWorker
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Singleton
class SyncWorkManager @Inject constructor(private val workManager: WorkManager) {

    private val TAG = "SyncWorkManager"

    companion object {
        const val PASSIVE_POLLING_WORK_NAME = "PassivePolling"
    }

    fun enqueueFolderSync(accountId: String) {
        val workName = "FolderSync-$accountId"
        Timber.d("$TAG: Enqueueing folder sync for account $accountId. WorkName: $workName")
        val workRequest = OneTimeWorkRequestBuilder<FolderSyncWorker>()
            .setInputData(Data.Builder().putString(FolderSyncWorker.KEY_ACCOUNT_ID, accountId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
    }

    fun enqueueActionUpload(accountId: String, actionType: String, entityId: String, payload: Map<String, String?>) {
        val workName = "ActionUpload-For-$actionType-On-$entityId"
        Timber.d("$TAG: Enqueueing action upload. WorkName: $workName")
        
        val payloadData = Data.Builder().putAll(payload).build()

        val workRequest = OneTimeWorkRequestBuilder<ActionUploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ActionUploadWorker.KEY_ACCOUNT_ID, accountId)
                    .putString(ActionUploadWorker.KEY_ENTITY_ID, entityId)
                    .putString(ActionUploadWorker.KEY_ACTION_TYPE, actionType)
                    .putAll(payload)
                    .build()
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
    }

    fun enqueueMessageBodyDownload(accountId: String, messageId: String) {
        val workName = "MessageBodyDownload-$messageId"
        Timber.d("$TAG: Enqueueing message body download for message $messageId. WorkName: $workName")
        val workRequest = OneTimeWorkRequestBuilder<MessageBodyDownloadWorker>()
            .setInputData(Data.Builder()
                .putString(MessageBodyDownloadWorker.KEY_ACCOUNT_ID, accountId)
                .putString(MessageBodyDownloadWorker.KEY_MESSAGE_ID, messageId)
                .build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
    }

    fun enqueueAttachmentDownload(accountId: String, messageId: String, attachmentId: String) {
        val workName = "AttachmentDownload-$attachmentId"
        Timber.d("$TAG: Enqueueing attachment download for attachment $attachmentId. WorkName: $workName")
        val workRequest = OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
            .setInputData(Data.Builder()
                .putString(AttachmentDownloadWorker.KEY_ACCOUNT_ID, accountId)
                .putString(AttachmentDownloadWorker.KEY_MESSAGE_ID, messageId)
                .putString(AttachmentDownloadWorker.KEY_ATTACHMENT_ID, attachmentId)
                .build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
    }

    fun enqueueCacheCleanup() {
        val workName = "CacheCleanup"
        Timber.d("$TAG: Enqueueing cache cleanup work. WorkName: $workName")
        val workRequest = OneTimeWorkRequestBuilder<CacheCleanupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, workRequest)
    }

    fun schedulePassivePolling() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val workRequest = androidx.work.PeriodicWorkRequestBuilder<net.melisma.data.sync.workers.PassivePollingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        Timber.d("$TAG: Scheduling passive polling periodic work")
        workManager.enqueueUniquePeriodicWork(
            PASSIVE_POLLING_WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    fun cancelPassivePolling() {
        Timber.d("$TAG: Cancelling passive polling work")
        workManager.cancelUniqueWork(PASSIVE_POLLING_WORK_NAME)
    }
} 