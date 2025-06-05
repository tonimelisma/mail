package net.melisma.data.sync

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.datasource.MailApiServiceSelector
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.data.sync.workers.ActionUploadWorker
import net.melisma.data.sync.workers.AttachmentDownloadWorker
import net.melisma.data.sync.workers.CacheCleanupWorker
import net.melisma.data.sync.workers.FolderContentSyncWorker
import net.melisma.data.sync.workers.FolderListSyncWorker
import net.melisma.data.sync.workers.MessageBodyDownloadWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
    private val messageDao: MessageDao,  // Example DAO
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val networkMonitor: NetworkMonitor,
    @ApplicationScope private val externalScope: CoroutineScope,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "SyncEngine"

    private val _overallSyncState = MutableStateFlow(SyncProgress.IDLE)
    val overallSyncState: StateFlow<SyncProgress> = _overallSyncState.asStateFlow()

    // TODO: P3_SYNC - Observe WorkManager job statuses to update overallSyncState more accurately.

    // --- Action Enqueuing (from Phase 2) ---
    fun enqueueMessageAction(accountId: String, messageId: String, actionType: String, payload: Map<String, Any?>) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping ActionUploadWorker for message action: $actionType, messageId: $messageId")
                return@launch
            }
            Timber.d("$TAG: Enqueuing ActionUploadWorker for message action: $actionType, messageId: $messageId")
            val workRequestBuilder = OneTimeWorkRequestBuilder<ActionUploadWorker>()
                .setInputData(
                    workDataOf(
                        ActionUploadWorker.KEY_ACCOUNT_ID to accountId,
                        ActionUploadWorker.KEY_ENTITY_ID to messageId,
                        ActionUploadWorker.KEY_ACTION_TYPE to actionType,
                        *payload.toList().toTypedArray()
                    )
                )
                .addTag("${actionType}_${accountId}_${messageId}")
            workManager.enqueue(workRequestBuilder.build())
            _overallSyncState.value = SyncProgress.SYNCING
        }
    }

    fun enqueueThreadAction(accountId: String, threadId: String, actionType: String, payload: Map<String, Any?>) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping ActionUploadWorker for thread action: $actionType, threadId: $threadId")
                return@launch
            }
            Timber.d("$TAG: Enqueuing ActionUploadWorker for thread action: $actionType, threadId: $threadId")
            val workRequest = OneTimeWorkRequestBuilder<ActionUploadWorker>()
                .setInputData(
                    workDataOf(
                        ActionUploadWorker.KEY_ACCOUNT_ID to accountId,
                        ActionUploadWorker.KEY_ENTITY_ID to threadId,
                        ActionUploadWorker.KEY_ACTION_TYPE to actionType,
                        *payload.toList().toTypedArray()
                    )
                )
                .addTag("${actionType}_${accountId}_${threadId}")
                .build()
            workManager.enqueue(workRequest)
            _overallSyncState.value = SyncProgress.SYNCING
        }
    }

    // --- Content Sync/Download Triggers ---
    fun syncFolders(accountId: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping FolderListSyncWorker for accountId: $accountId")
                return@launch
            }
            Timber.d("Enqueuing FolderListSyncWorker for accountId: $accountId")
            _overallSyncState.value = SyncProgress.SYNCING
            val workRequest = OneTimeWorkRequestBuilder<FolderListSyncWorker>()
                .setInputData(workDataOf("ACCOUNT_ID" to accountId))
                .addTag("FolderListSync_${accountId}")
                .build()
            workManager.enqueue(workRequest)
        }
    }

    fun syncFolderContent(accountId: String, folderId: String, folderRemoteId: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping FolderContentSyncWorker for accountId: $accountId, folderId: $folderId")
                return@launch
            }
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
    }

    fun downloadMessageBody(accountId: String, messageId: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping MessageBodyDownloadWorker for accountId: $accountId, messageId: $messageId")
                return@launch
            }
            Timber.d("Enqueuing MessageBodyDownloadWorker for accountId: $accountId, messageId: $messageId")
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
    }

    fun downloadAttachment(accountId: String, messageId: String, attachmentId: String, attachmentName: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping AttachmentDownloadWorker for accountId: $accountId, messageId: $messageId, attachmentId: $attachmentId")
                return@launch
            }
            Timber.d("Enqueuing AttachmentDownloadWorker for accountId: $accountId, messageId: $messageId, attachmentId: $attachmentId")
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
    }

    // --- Sync Scheduling Logic (Conceptual) ---

    fun schedulePeriodicSync(accountId: String) {
        Timber.d("$TAG: Scheduling periodic sync for accountId: $accountId")
        val periodicFolderListSync = PeriodicWorkRequestBuilder<FolderListSyncWorker>(4, TimeUnit.HOURS)
            .setInputData(workDataOf("ACCOUNT_ID" to accountId))
            .addTag("PeriodicFolderListSync_$accountId")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "PeriodicFolderListSyncWork_$accountId",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicFolderListSync
        )
        _overallSyncState.value = SyncProgress.IDLE
    }

    fun triggerSyncOnNetworkChange(accountId: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.i("$TAG: Network change detected, but network is offline. Skipping sync for accountId: $accountId")
                return@launch
            }
            Timber.d("$TAG: Network change detected (online), triggering sync for accountId: $accountId")
            syncFolders(accountId)
        }
    }

    fun triggerSyncOnAppForeground(accountId: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.i("$TAG: App came to foreground, but network is offline. Skipping sync for accountId: $accountId")
                return@launch
            }
            Timber.d("$TAG: App came to foreground (network online), triggering sync for accountId: $accountId")
            syncFolders(accountId)
        }
    }

    // TODO: P3_SYNC - Consider a method to trigger sync for ALL accounts for each category (folders, content of key folders)
    // fun triggerFullSyncForAllAccountsOnNetworkChange() { ... }
    // fun triggerFullSyncForAllAccountsOnAppForeground() { ... }


    // --- Cache Cleanup Scheduling ---

    fun schedulePeriodicCacheCleanup() {
        Timber.d("$TAG: Scheduling periodic cache cleanup.")
        val dailyCacheCleanupRequest = PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
            .addTag("PeriodicCacheCleanup")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "PeriodicCacheCleanupWork",
            ExistingPeriodicWorkPolicy.KEEP,
            dailyCacheCleanupRequest
        )
        Timber.i("$TAG: Periodic cache cleanup worker enqueued.")
    }
}
