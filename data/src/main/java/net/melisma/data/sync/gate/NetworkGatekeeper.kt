package net.melisma.data.sync.gate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.model.SyncJob
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import net.melisma.core_data.di.ApplicationScope

@Singleton
class NetworkGatekeeper @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    @ApplicationScope private val appScope: CoroutineScope
) : Gatekeeper {
    private val onlineState: StateFlow<Boolean> =
        networkMonitor.isOnline.stateIn(appScope, SharingStarted.Eagerly, false)

    override suspend fun isAllowed(job: SyncJob): Boolean {
        if (!job.requiresNetwork()) return true

        val isOnline = onlineState.value
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