package net.melisma.data.sync.gate

import kotlinx.coroutines.flow.first
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.model.SyncJob
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkGatekeeper @Inject constructor(
    private val networkMonitor: NetworkMonitor
) : Gatekeeper {
    override suspend fun isAllowed(job: SyncJob): Boolean {
        if (!job.requiresNetwork()) return true

        val isOnline = networkMonitor.isOnline.first()
        if (!isOnline) {
            Timber.d("NetworkGatekeeper: VETOED job ${job::class.simpleName} because network is offline.")
        }
        return isOnline
    }

    private fun SyncJob.requiresNetwork(): Boolean {
        return when (this) {
            is SyncJob.EvictFromCache -> false
            else -> true
        }
    }
} 