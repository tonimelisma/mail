package net.melisma.data.sync

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineDispatcher
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
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.dao.PendingActionDao
import net.melisma.core_db.entity.PendingActionEntity
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

const val FOLDER_LIST_SYNC_WORK_NAME_PREFIX = "folderListSync_"
const val ACTION_UPLOAD_WORK_NAME = "actionUploadWork"

enum class SyncProgress {
    IDLE,
    SYNCING,
    ERROR
}

@Singleton
class SyncEngine @Inject constructor(
    private val workManager: WorkManager,
    private val accountDao: AccountDao,
    private val folderDao: FolderDao,
    private val messageDao: MessageDao,
    private val pendingActionDao: PendingActionDao,
    private val mailApiServiceSelector: MailApiServiceSelector,
    private val networkMonitor: NetworkMonitor,
    @ApplicationScope private val externalScope: CoroutineScope,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val TAG = "SyncEngine"

    private val _overallSyncState = MutableStateFlow(SyncProgress.IDLE)
    val overallSyncState: StateFlow<SyncProgress> = _overallSyncState.asStateFlow()

    // TODO: P3_SYNC - Observe WorkManager job statuses to update overallSyncState more accurately.
    // TODO: P2_SYNC - Add per-account sync state tracking
    // Map<String (accountId), StateFlow<AccountSyncProgress>>
    // AccountSyncProgress could be: IDLE, FOLDER_LIST_SYNCING, FOLDER_LIST_FAILED, CONTENT_SYNCING_INBOX, etc.

    // --- Action Enqueuing ---
    fun queueAction(
        accountId: String,
        entityId: String,
        actionType: String,
        payload: Map<String, String?>
    ) {
        externalScope.launch(ioDispatcher) {
            Timber.d("$TAG: Queuing action: $actionType for entityId: $entityId, accountId: $accountId")
            
            val pendingAction = PendingActionEntity(
                accountId = accountId,
                entityId = entityId,
                actionType = actionType,
                payload = payload
            )
            pendingActionDao.insertAction(pendingAction)
            Timber.d("$TAG: Saved action to DB with id: ${pendingAction.id}. Enqueuing ActionUploadWorker.")

            val workRequestBuilder = OneTimeWorkRequestBuilder<ActionUploadWorker>()
            workManager.enqueueUniqueWork(
                ACTION_UPLOAD_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequestBuilder.build()
            )
        }
    }

    // --- Content Sync/Download Triggers ---
    fun syncFolders(accountId: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping FolderListSyncWorker for accountId: $accountId")
                return@launch
            }
            Timber.d("$TAG: Enqueuing FolderListSyncWorker for accountId: $accountId for full folder structure sync.")
            _overallSyncState.value = SyncProgress.SYNCING // Or a more specific per-account state

            val uniqueWorkName = FOLDER_LIST_SYNC_WORK_NAME_PREFIX + accountId
            val folderListWorkRequest = OneTimeWorkRequestBuilder<FolderListSyncWorker>()
                .setInputData(workDataOf(FolderListSyncWorker.KEY_ACCOUNT_ID to accountId))
                .addTag("FolderListSync_${accountId}") // Tag can still be useful for querying by tag
                .build()

            // Use enqueueUniqueWork to prevent duplicate syncs for the same account
            workManager.enqueueUniqueWork(
                uniqueWorkName,
                androidx.work.ExistingWorkPolicy.KEEP, // KEEP: If work already exists, keep it and ignore the new request.
                folderListWorkRequest
            )

            // Observe the result of the unique work
            workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
                .collect { workInfoList ->
                    // A unique work request might result in a list (usually one item if active/successful)
                    val workInfo = workInfoList.firstOrNull()
                    if (workInfo != null) {
                        when (workInfo.state) {
                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                Timber.i("$TAG: FolderListSyncWorker for account $accountId (unique work: $uniqueWorkName) SUCCEEDED. Proceeding to sync content for key folders.")
                                // TODO: P2_SYNC - Update per-account state to FOLDER_LIST_SYNC_SUCCESS

                                val keyFolderTypesToSync = listOf(
                                    WellKnownFolderType.INBOX,
                                    WellKnownFolderType.DRAFTS,
                                    WellKnownFolderType.SENT_ITEMS
                                )

                                keyFolderTypesToSync.forEach { folderType ->
                                    val folder = folderDao.getFolderByWellKnownTypeSuspend(
                                        accountId,
                                        folderType
                                    )
                                    if (folder != null) {
                                        Timber.d("$TAG: Found key folder type \'${folderType.name}\' (Name: \'${folder.name}\', local PK: ${folder.id}, remoteId: ${folder.remoteId ?: "N/A"}) for account $accountId. Enqueuing content sync.")
                                        if (folder.remoteId != null) { // Ensure remoteId is not null for API calls
                                            syncFolderContent(
                                                accountId,
                                                folder.id,
                                                folder.remoteId!!
                                            )
                                        } else {
                                            // This case should ideally not happen for well-known folders that require a remoteId for content sync.
                                            // For some local-only or special types, remoteId might be null, but those usually don\'t need remote content sync.
                                            Timber.w("$TAG: Key folder type \'${folderType.name}\' (Name: \'${folder.name}\', local PK: ${folder.id}) for account $accountId has null remoteId. Content sync might be N/A or fail.")
                                        }
                                    } else {
                                        Timber.w("$TAG: Key folder type \'${folderType.name}\' not found for account $accountId after folder list sync. Cannot sync its content.")
                                    }
                                }
                                _overallSyncState.value =
                                    SyncProgress.IDLE // Or SYNCING if content sync started and we track it more granularly
                            }

                            androidx.work.WorkInfo.State.FAILED -> {
                                val errorMessage =
                                    workInfo.outputData.getString(FolderListSyncWorker.KEY_ERROR_MESSAGE)
                                Timber.e("$TAG: FolderListSyncWorker for account $accountId (unique work: $uniqueWorkName) FAILED. Error: $errorMessage")
                                // TODO: P2_SYNC - Update per-account state to FOLDER_LIST_SYNC_FAILED
                                _overallSyncState.value = SyncProgress.ERROR
                            }

                            androidx.work.WorkInfo.State.CANCELLED -> {
                                Timber.w("$TAG: FolderListSyncWorker for account $accountId (unique work: $uniqueWorkName) CANCELLED.")
                                // TODO: P2_SYNC - Update per-account state to FOLDER_LIST_SYNC_CANCELLED
                                _overallSyncState.value =
                                    SyncProgress.IDLE // Or ERROR depending on policy
                            }

                            else -> {
                                // RUNNING, ENQUEUED, BLOCKED -
                                // Timber.d("$TAG: FolderListSyncWorker for account $accountId (unique work: $uniqueWorkName) is ${workInfo.state}")
                            }
                        }
                    }
                }
        }
    }

    fun syncFolderContent(accountId: String, folderId: String, folderRemoteId: String) {
        externalScope.launch(ioDispatcher) {
            if (!networkMonitor.isOnline.first()) {
                Timber.w("$TAG: Network offline. Skipping FolderContentSyncWorker for accountId: $accountId, folderId: $folderId")
                return@launch
            }

            // Diagnostic check REMOVED as upstream UUID handling for folder.id is now robust.
            // The folderId parameter here is expected to be the local UUID (FolderEntity.id).

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
        // TODO: P3_SYNC - This periodic sync should also use the new orchestrated approach.
        // It should probably trigger syncFolders(accountId) which handles the chain.
        val periodicFolderListSync = PeriodicWorkRequestBuilder<FolderListSyncWorker>(4, TimeUnit.HOURS)
            .setInputData(workDataOf(FolderListSyncWorker.KEY_ACCOUNT_ID to accountId))
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
