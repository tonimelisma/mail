package net.melisma.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.melisma.core_data.model.SyncJob
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class CacheEvictionProducer @Inject constructor(
    private val appDatabase: AppDatabase,
    private val userPrefs: UserPreferencesRepository,
) : JobProducer {
    private val HARD_PRESSURE_THRESHOLD = 0.98f // 98%
    private val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var lastEvictionCheckTimestamp = 0L

    override suspend fun produce(): List<SyncJob> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dueForPeriodicCheck = (now - lastEvictionCheckTimestamp) > TWENTY_FOUR_HOURS_MS

        val jobs = mutableListOf<SyncJob>()
        val attachmentDao = appDatabase.attachmentDao()
        val messageBodyDao = appDatabase.messageBodyDao()
        val accountDao = appDatabase.accountDao()

        val prefs = userPrefs.userPreferencesFlow.first()
        val hardLimit = prefs.cacheSizeLimitBytes
        val hardThreshold = (hardLimit * HARD_PRESSURE_THRESHOLD).toLong()

        val accounts = accountDao.getAccountsSuspend()
        var needsEviction = false
        for (account in accounts) {
            val attsBytes = attachmentDao.getTotalDownloadedSizeForAccount(account.id) ?: 0L
            val bodyBytes = messageBodyDao.getTotalBodiesSizeForAccount(account.id) ?: 0L
            val used = attsBytes + bodyBytes
            if (used >= hardThreshold) {
                needsEviction = true
                jobs += SyncJob.EvictFromCache(account.id)
            }
        }

        if (jobs.isEmpty() && dueForPeriodicCheck) {
            // If no account is over the hard threshold, but it's time for a check,
            // run eviction on all accounts just in case there's old stuff to clean.
            accounts.forEach { account ->
                jobs += SyncJob.EvictFromCache(account.id)
            }
        }

        if (jobs.isNotEmpty() || dueForPeriodicCheck) {
            lastEvictionCheckTimestamp = now
        }

        jobs
    }
} 