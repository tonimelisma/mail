package net.melisma.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.model.SyncJob
import net.melisma.core_data.model.SyncControllerStatus
import net.melisma.data.sync.work.SyncWorkManager
import timber.log.Timber
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncController @Inject constructor(
    @ApplicationScope private val externalScope: CoroutineScope,
    private val queue: PriorityBlockingQueue<SyncJob>,
    private val networkMonitor: NetworkMonitor,
    private val syncWorkManager: SyncWorkManager
) {

    private val _status = MutableStateFlow(SyncControllerStatus())
    val status = _status.asStateFlow()

    init {
        externalScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                _status.value = _status.value.copy(networkAvailable = isOnline)
            }
        }
        externalScope.launch { run() }
        Timber.d("SyncController initialized and running.")
    }

    fun submit(job: SyncJob) {
        if (queue.contains(job)) {
            Timber.d("Ignoring duplicate job: $job")
            return
        }
        Timber.d("Submitting new job: $job")
        queue.put(job)
    }

    private suspend fun run() {
        while (true) {
            val job = queue.take()
            _status.value = _status.value.copy(isSyncing = true, currentJob = job.toString())
            Timber.i("Processing job: $job")

            try {
                when (job) {
                    is SyncJob.SyncFolderList -> syncWorkManager.enqueueFolderSync(job.accountId)
                    is SyncJob.ForceRefreshFolder -> syncWorkManager.enqueueFolderContentSync(job.accountId, job.folderId, true)
                    is SyncJob.DownloadMessageBody -> syncWorkManager.enqueueMessageBodyDownload(job.accountId, job.messageId)
                    is SyncJob.RefreshFolderContents -> syncWorkManager.enqueueFolderContentSync(job.accountId, job.folderId, true)
                    is SyncJob.UploadAction -> syncWorkManager.enqueueActionUpload(
                        accountId = job.accountId,
                        actionType = job.actionType,
                        entityId = job.entityId,
                        payload = job.payload
                    )
                    is SyncJob.DownloadAttachment -> syncWorkManager.enqueueAttachmentDownload(
                        accountId = job.accountId,
                        messageId = job.messageId,
                        attachmentId = job.attachmentId
                    )
                    is SyncJob.FetchFullMessageBody -> Timber.w("No-op handler for FetchFullMessageBody")
                    is SyncJob.FetchMessageHeaders -> Timber.w("No-op handler for FetchMessageHeaders")
                    is SyncJob.FetchNextMessageListPage -> Timber.w("No-op handler for FetchNextMessageListPage")
                    is SyncJob.SearchOnline -> Timber.w("No-op handler for SearchOnline")
                    is SyncJob.EvictFromCache -> Timber.w("No-op handler for EvictFromCache")
                }
                _status.value = _status.value.copy(error = null)
            } catch (e: Exception) {
                Timber.e(e, "Error processing job: $job")
                _status.value = _status.value.copy(error = e.message)
            } finally {
                _status.value = _status.value.copy(isSyncing = false, currentJob = null)
            }
        }
    }
} 