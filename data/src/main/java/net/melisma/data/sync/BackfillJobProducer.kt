package net.melisma.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.melisma.core_data.model.SyncJob
import net.melisma.core_db.AppDatabase
import net.melisma.core_data.preferences.UserPreferencesRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Periodically inspects each folder and determines whether additional header pages are
 * required to satisfy the 90-day offline cache guarantee.  This implementation is intentionally
 * simple – it uses the earliest received timestamp currently in the DB and compares that to
 * (now − 90 days). It also pauses itself if the cache is under pressure.
 */
@Singleton
class BackfillJobProducer @Inject constructor(
    private val appDatabase: AppDatabase,
    private val userPrefs: UserPreferencesRepository,
) : JobProducer {

    private val CACHE_PRESSURE_THRESHOLD = 0.90f

    override suspend fun produce(): List<SyncJob> = withContext(Dispatchers.IO) {
        // First, check for cache pressure. If high, don't produce any backfill jobs.
        val prefs = userPrefs.userPreferencesFlow.first()
        val hardLimitBytes = prefs.cacheSizeLimitBytes
        val softThresholdBytes = (hardLimitBytes * CACHE_PRESSURE_THRESHOLD).toLong()

        val attachmentsBytes = appDatabase.attachmentDao().getTotalDownloadedSize() ?: 0L
        val bodiesBytes = appDatabase.messageBodyDao().getTotalBodiesSize() ?: 0L
        val totalUsageBytes = attachmentsBytes + bodiesBytes

        if (totalUsageBytes >= softThresholdBytes) {
            Timber.d("BackfillJobProducer: Paused due to high cache pressure.")
            return@withContext emptyList()
        }

        val jobs = mutableListOf<SyncJob>()
        val folderDao = appDatabase.folderDao()
        val messageDao = appDatabase.messageDao()
        val accountDao = appDatabase.accountDao()

        val daysWindow = prefs.initialSyncDurationDays
        val millisPerDay = 24L * 60 * 60 * 1000
        val cutoff = if (daysWindow <= 0L) 0L else System.currentTimeMillis() - (daysWindow * millisPerDay)

        val accounts = accountDao.getAccountsSuspend()
        accounts.forEach { account ->
            val folders = folderDao.getFoldersForAccountSuspend(account.id)
            folders.forEach { folder ->
                val oldestMsgTs = messageDao.getOldestMessageTimestamp(folder.id)
                if (oldestMsgTs == null || oldestMsgTs > cutoff) {
                    // We are missing part of the 90-day window – request more.
                    jobs += SyncJob.FetchMessageHeaders(folder.id, pageToken = null, accountId = account.id)
                }
            }
        }
        if (jobs.isNotEmpty()) Timber.d("BackfillJobProducer queued ${jobs.size} header pages")
        jobs
    }
} 