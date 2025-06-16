package net.melisma.data.sync.gate

import kotlinx.coroutines.flow.first
import net.melisma.core_data.model.SyncJob
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.MessageBodyDao
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CachePressureGatekeeper @Inject constructor(
    private val attachmentDao: AttachmentDao,
    private val messageBodyDao: MessageBodyDao,
    private val userPrefs: UserPreferencesRepository,
) : Gatekeeper {

    private val CACHE_PRESSURE_THRESHOLD = 0.90f

    override suspend fun isAllowed(job: SyncJob): Boolean {
        // Only apply this gate to proactive, non-user-initiated downloads
        if (!isProactiveDownload(job)) return true

        val prefs = userPrefs.userPreferencesFlow.first()
        val hardLimitBytes = prefs.cacheSizeLimitBytes
        val softThresholdBytes = (hardLimitBytes * CACHE_PRESSURE_THRESHOLD).toLong()

        val attachmentsBytes = attachmentDao.getTotalDownloadedSize() ?: 0L
        val bodiesBytes = messageBodyDao.getTotalBodiesSize() ?: 0L
        val totalUsageBytes = attachmentsBytes + bodiesBytes

        val isAllowed = totalUsageBytes < softThresholdBytes
        if (!isAllowed) {
            Timber.w("CachePressureGatekeeper: VETOED job ${job::class.simpleName} due to cache pressure. " +
                    "Usage: ${totalUsageBytes / 1024 / 1024}MB / ${hardLimitBytes / 1024 / 1024}MB")
        }
        return isAllowed
    }

    private fun isProactiveDownload(job: SyncJob): Boolean {
        return when (job) {
            // These jobs are for filling history and are not directly requested by the user viewing a message.
            is SyncJob.FetchMessageHeaders -> true
            // Potentially add other opportunistic jobs here in the future
            else -> false
        }
    }
} 