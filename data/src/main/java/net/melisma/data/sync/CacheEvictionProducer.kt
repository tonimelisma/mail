package net.melisma.data.sync

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val userPrefs: UserPreferencesRepository,
) : JobProducer {
    private val HARD_PRESSURE_THRESHOLD = 0.98f
    private val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

    private val prefs by lazy {
        context.getSharedPreferences("cache_eviction_producer_state", Context.MODE_PRIVATE)
    }

    override suspend fun produce(): List<SyncJob> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIMESTAMP, 0L)
        val dueForPeriodicCheck = (now - lastCheck) > TWENTY_FOUR_HOURS_MS

        val userPrefs = userPrefs.userPreferencesFlow.first()
        val hardLimit = userPrefs.cacheSizeLimitBytes
        val hardThreshold = (hardLimit * HARD_PRESSURE_THRESHOLD).toLong()

        val attachmentsBytes = appDatabase.attachmentDao().getTotalDownloadedSize() ?: 0L
        val bodiesBytes = appDatabase.messageBodyDao().getTotalBodiesSize() ?: 0L
        val totalUsage = attachmentsBytes + bodiesBytes

        val isOverHardThreshold = totalUsage >= hardThreshold

        if (isOverHardThreshold || dueForPeriodicCheck) {
            prefs.edit { putLong(KEY_LAST_CHECK_TIMESTAMP, now) }
            return@withContext listOf(SyncJob.EvictFromCache)
        }

        return@withContext emptyList()
    }

    companion object {
        private const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"
    }
} 