package net.melisma.data.sync

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.data.sync.workers.ActionUploadWorker
import net.melisma.data.sync.workers.AttachmentDownloadWorker
import net.melisma.data.sync.workers.FolderContentSyncWorker
import net.melisma.data.sync.workers.FolderListSyncWorker
import net.melisma.data.sync.workers.MessageBodyDownloadWorker
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import java.util.concurrent.TimeUnit

enum class SyncProgress {
    IDLE,
    SYNCING,
    ERROR
}

@Singleton
class SyncEngine @Inject constructor(
    private val workManager: WorkManager,
    private val accountDao: AccountDao, // Example DAO, add others as needed
    private val folderDao: FolderDao,   // Example DAO
    private val messageDao: MessageDao  // Example DAO
    // TODO: P1_SYNC - Inject MailApiServiceSelector or similar
) {
    private val TAG = "SyncEngine"

    private val _overallSyncState = MutableStateFlow(SyncProgress.IDLE)
    val overallSyncState: StateFlow<SyncProgress> = _overallSyncState.asStateFlow()

    // TODO: P3_SYNC - Observe WorkManager job statuses to update overallSyncState more accurately.

    // --- Action Enqueuing (from Phase 2) ---
    fun enqueueMessageAction(accountId: String, messageId: String, actionType: String, payload: Map<String, Any?>) {
        Timber.d("$TAG: Enqueuing ActionUploadWorker for message action: $actionType, messageId: $messageId")
        // TODO: P3_SYNC - Implement actual priority using WorkManager's setExpedited or by chaining work requests.
        // User-initiated actions like this should generally have higher priority.
        val workRequestBuilder = OneTimeWorkRequestBuilder<ActionUploadWorker>()
            .setInputData(
                workDataOf(
                    ActionUploadWorker.KEY_ACCOUNT_ID to accountId,
                    ActionUploadWorker.KEY_ENTITY_ID to messageId,
                    ActionUploadWorker.KEY_ACTION_TYPE to actionType,
                    *payload.toList().toTypedArray() // Spread operator for map to varargs pairs
                )
            )
            .addTag("${actionType}_${accountId}_${messageId}")

        // Example of how priority could be conceptualized:
        // if (actionType == ActionUploadWorker.ACTION_SEND_MESSAGE) {
        //    workRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        // }

        workManager.enqueue(workRequestBuilder.build())
        _overallSyncState.value = SyncProgress.SYNCING // Basic state update
        // TODO: More robust state update based on work completion
    }

    fun enqueueThreadAction(accountId: String, threadId: String, actionType: String, payload: Map<String, Any?>) {
        Timber.d("$TAG: Enqueuing ActionUploadWorker for thread action: $actionType, threadId: $threadId")
        // TODO: P3_SYNC - Implement actual priority.
        val workRequest = OneTimeWorkRequestBuilder<ActionUploadWorker>()
            .setInputData(
                workDataOf(
                    ActionUploadWorker.KEY_ACCOUNT_ID to accountId,
                    ActionUploadWorker.KEY_ENTITY_ID to threadId, // Assuming threadId is the entityId for thread actions
                    ActionUploadWorker.KEY_ACTION_TYPE to actionType,
                    *payload.toList().toTypedArray()
                )
            )
            .addTag("${actionType}_${accountId}_${threadId}")
            .build()
        workManager.enqueue(workRequest)
        _overallSyncState.value = SyncProgress.SYNCING
    }

    // --- Content Sync/Download Triggers ---
    fun syncFolders(accountId: String) {
        Timber.d("Enqueuing FolderListSyncWorker for accountId: $accountId")
        // TODO: P3_SYNC - Consider if this should be unique work to prevent duplicates if called rapidly.
        _overallSyncState.value = SyncProgress.SYNCING
        val workRequest = OneTimeWorkRequestBuilder<FolderListSyncWorker>()
            .setInputData(workDataOf("ACCOUNT_ID" to accountId))
            .addTag("FolderListSync_${accountId}") // Tag for observation or cancellation
            .build()
        workManager.enqueue(workRequest)
        // Basic state update, real update would await worker completion
    }

    fun syncFolderContent(accountId: String, folderId: String, folderRemoteId: String) {
        Timber.d("Enqueuing FolderContentSyncWorker for accountId: $accountId, folderId: $folderId, folderRemoteId: $folderRemoteId")
        _overallSyncState.value = SyncProgress.SYNCING
        val workRequest = OneTimeWorkRequestBuilder<FolderContentSyncWorker>()
            .setInputData(
                workDataOf(
                    "ACCOUNT_ID" to accountId,
                    "FOLDER_ID" to folderId,
                    "FOLDER_REMOTE_ID" to folderRemoteId
                )
            )
            .addTag("FolderContentSync_${accountId}_${folderId}")
            .build()
        workManager.enqueue(workRequest)
    }

    fun downloadMessageBody(accountId: String, messageId: String) {
        Timber.d("Enqueuing MessageBodyDownloadWorker for accountId: $accountId, messageId: $messageId")
        // This is typically a user-triggered action, could be higher priority.
        _overallSyncState.value = SyncProgress.SYNCING
        val workRequest = OneTimeWorkRequestBuilder<MessageBodyDownloadWorker>()
            .setInputData(
                workDataOf(
                    "ACCOUNT_ID" to accountId,
                    "MESSAGE_ID" to messageId
                )
            )
            .addTag("MessageBodyDownload_${accountId}_${messageId}")
            .build()
        workManager.enqueue(workRequest)
    }

    fun downloadAttachment(accountId: String, messageId: String, attachmentId: String, attachmentName: String) {
        Timber.d("Enqueuing AttachmentDownloadWorker for accountId: $accountId, messageId: $messageId, attachmentId: $attachmentId")
        // User-triggered, could be higher priority.
        _overallSyncState.value = SyncProgress.SYNCING
        val workRequest = OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
            .setInputData(
                workDataOf(
                    "ACCOUNT_ID" to accountId,
                    "MESSAGE_ID" to messageId,
                    "ATTACHMENT_ID" to attachmentId,
                    "ATTACHMENT_NAME" to attachmentName
                )
            )
            .addTag("AttachmentDownload_${accountId}_${messageId}_${attachmentId}")
            .build()
        workManager.enqueue(workRequest)
    }

    // --- Sync Scheduling Logic (Conceptual) ---

    fun schedulePeriodicSync(accountId: String) {
        Timber.d("$TAG: Scheduling periodic sync for accountId: $accountId")
        // TODO: P3_SYNC - Define appropriate repeat interval and constraints (e.g., metered network, battery not low).
        // For example, sync every 4 hours.
        val periodicFolderListSync = PeriodicWorkRequestBuilder<FolderListSyncWorker>(4, TimeUnit.HOURS)
            .setInputData(workDataOf("ACCOUNT_ID" to accountId))
            // .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .addTag("PeriodicFolderListSync_$accountId")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "PeriodicFolderListSyncWork_$accountId",
            ExistingPeriodicWorkPolicy.KEEP, // Or REPLACE
            periodicFolderListSync
        )

        // TODO: P3_SYNC - Consider periodic sync for important folder contents as well.
        // This would require knowing which folders are "important" (e.g., Inbox).
        // val periodicInboxSync = PeriodicWorkRequestBuilder<FolderContentSyncWorker>(...)
        // workManager.enqueueUniquePeriodicWork("PeriodicInboxSyncWork_$accountId", ..., periodicInboxSync)

        _overallSyncState.value = SyncProgress.IDLE // Scheduling itself doesn't mean active sync
    }

    fun triggerSyncOnNetworkChange(accountId: String) {
        Timber.d("$TAG: Network change detected, triggering sync for accountId: $accountId")
        // TODO: P3_SYNC - This should be called from a network callback mechanism.
        // This would typically sync essential data like folder list and perhaps inbox content.
        syncFolders(accountId)
        // TODO: P3_SYNC - Potentially sync content of key folders like Inbox.
        // val inboxFolder = folderDao.getInboxFolder(accountId) // Needs such a DAO method
        // inboxFolder?.let { syncFolderContent(accountId, it.id, it.remoteId) } // Assuming MailFolder has remoteId
        _overallSyncState.value = SyncProgress.SYNCING
    }

    fun triggerSyncOnAppForeground(accountId: String) {
        Timber.d("$TAG: App came to foreground, triggering sync for accountId: $accountId")
        // TODO: P3_SYNC - This should be called from app lifecycle callbacks.
        // Similar to network change, sync essential data.
        // Could be more aggressive than periodic sync if data is stale.
        // TODO: P3_SYNC - Implement staleness check before triggering.
        syncFolders(accountId)
        // TODO: P3_SYNC - Potentially sync content of key folders like Inbox if stale.
        _overallSyncState.value = SyncProgress.SYNCING
    }

    // TODO: P3_SYNC - Consider a method to trigger sync for ALL accounts for each category (folders, content of key folders)
    // fun triggerFullSyncForAllAccountsOnNetworkChange() { ... }
    // fun triggerFullSyncForAllAccountsOnAppForeground() { ... }


    // --- Cache Cleanup Scheduling ---

    fun schedulePeriodicCacheCleanup() {
        Timber.d("$TAG: Scheduling periodic cache cleanup.")
        // TODO: P3_CACHE - Define appropriate repeat interval for cache cleanup (e.g., daily).
        val dailyCacheCleanupRequest = PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
            // TODO: P3_CACHE - Add constraints? e.g., device idle, charging.
            // .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).setRequiresCharging(true).build())
            .addTag("PeriodicCacheCleanup")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "PeriodicCacheCleanupWork",
            ExistingPeriodicWorkPolicy.KEEP, // Or REPLACE if new parameters/logic
            dailyCacheCleanupRequest
        )
        Timber.i("$TAG: Periodic cache cleanup worker enqueued.")
    }
}
