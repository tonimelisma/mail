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

    override suspend fun produce(): List<SyncJob> = withContext(Dispatchers.IO) {
        // The CachePressureGatekeeper is now the single source of truth for this check.
        // This producer will queue jobs, and the gatekeeper will veto them if needed.

        val jobs = mutableListOf<SyncJob>()
        val folderDao = appDatabase.folderDao()
        val messageDao = appDatabase.messageDao()
        val accountDao = appDatabase.accountDao()
        val syncStateDao = appDatabase.folderSyncStateDao()

        val prefs = userPrefs.userPreferencesFlow.first()
        val daysWindow = prefs.initialSyncDurationDays
        val millisPerDay = 24L * 60 * 60 * 1000
        val cutoff = if (daysWindow <= 0L) 0L else System.currentTimeMillis() - (daysWindow * millisPerDay)

        val accounts = accountDao.getAccountsSuspend()
        accounts.forEach { account ->
            val folders = folderDao.getFoldersForAccountSuspend(account.id)
            folders.forEach { folder ->
                val syncState = syncStateDao.getState(folder.id)
                val watermark = syncState?.continuousHistoryToTimestamp

                if (watermark == null || watermark > cutoff) {
                    // The watermark is either unknown or not old enough to satisfy the user's preference.
                    // We may need to start a new backfill or continue an existing one.
                    if (watermark == null || syncState.nextPageToken != null) {
                        Timber.i("BackfillJobProducer: Queuing backfill for folder ${folder.name}. " +
                                "Reason: Watermark is ${if(watermark == null) "null" else "too recent"} and pagination is possible.")
                        jobs += SyncJob.HeaderBackfill(folder.id, pageToken = syncState?.nextPageToken, accountId = account.id)
                    }
                }
            }
        }
        if (jobs.isNotEmpty()) Timber.d("BackfillJobProducer queued ${jobs.size} jobs")
        jobs
    }
} 