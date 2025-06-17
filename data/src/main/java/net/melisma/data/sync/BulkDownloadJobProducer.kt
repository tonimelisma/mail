package net.melisma.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.model.SyncJob
import net.melisma.core_db.AppDatabase
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Opportunistically queues bulk body / attachment downloads when:
 *   • device is online
 *   • cache pressure gatekeeper will likely allow (we still let it veto)
 *   • there are missing bodies / attachments in the DB.
 */
@Singleton
class BulkDownloadJobProducer @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val appDatabase: AppDatabase,
) : JobProducer {

    override suspend fun produce(): List<SyncJob> = withContext(Dispatchers.IO) {
        if (!networkMonitor.isOnline.first()) {
            return@withContext emptyList()
        }
        val jobs = mutableListOf<SyncJob>()
        val accountDao = appDatabase.accountDao()
        val messageDao = appDatabase.messageDao()
        val attachmentDao = appDatabase.attachmentDao()

        val accounts = accountDao.getAccountsSuspend()
        accounts.forEach { account ->
            val missingBodies = messageDao.getMessagesMissingBody(account.id, limit = 1) // sentinel check
            if (missingBodies.isNotEmpty()) {
                jobs += SyncJob.BulkFetchBodies(account.id)
            }
            val undownloadedAtts = attachmentDao.getUndownloadedAttachmentsForAccount(account.id, limit = 1)
            if (undownloadedAtts.isNotEmpty()) {
                jobs += SyncJob.BulkFetchAttachments(account.id)
            }
        }
        if (jobs.isNotEmpty()) Timber.d("BulkDownloadJobProducer queued ${jobs.size} jobs")
        jobs
    }
} 