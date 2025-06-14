package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.dao.FolderDao
import net.melisma.data.sync.SyncController
import net.melisma.core_data.model.SyncJob
import timber.log.Timber

/**
 * Periodic background worker (≈15 min) that queues low-priority FetchMessageHeaders
 * jobs for key folders (currently Inbox) when the app is in the background.
 */
@HiltWorker
class PassivePollingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val networkMonitor: NetworkMonitor,
    private val accountDao: AccountDao,
    private val folderDao: FolderDao,
    private val syncController: SyncController,
) : CoroutineWorker(context, params) {

    private val TAG = "PassivePollingWorker"

    override suspend fun doWork(): Result {
        if (!networkMonitor.isOnline.first()) {
            Timber.d("$TAG: Device offline – retrying later")
            return Result.retry()
        }
        return try {
            val accounts = accountDao.getAllAccounts().first()
            accounts.forEach { accountEntity ->
                val inbox = folderDao.getFolderByWellKnownTypeSuspend(accountEntity.id, WellKnownFolderType.INBOX)
                inbox?.let {
                    syncController.submit(
                        SyncJob.FetchMessageHeaders(
                            folderId = it.id,
                            pageToken = null,
                            accountId = accountEntity.id
                        )
                    )
                }
            }
            Timber.d("$TAG: Queued freshness jobs for ${accounts.size} accounts")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error queuing freshness jobs")
            Result.failure()
        }
    }
} 