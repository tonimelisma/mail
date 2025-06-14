package net.melisma.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import net.melisma.data.sync.work.SyncWorkManager

/**
 * Observes the application process lifecycle and instructs [SyncController] to start/stop
 * its aggressive foreground polling while also scheduling/cancelling the passive
 * background polling PeriodicWorkRequest.
 */
@Singleton
class SyncLifecycleObserver @Inject constructor(
    private val syncController: SyncController,
    private val syncWorkManager: SyncWorkManager,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        Timber.d("SyncLifecycleObserver: onStart – app entered foreground")
        syncWorkManager.cancelPassivePolling()
        syncController.startActivePolling()
    }

    override fun onStop(owner: LifecycleOwner) {
        Timber.d("SyncLifecycleObserver: onStop – app entered background")
        syncController.stopActivePolling()
        syncWorkManager.schedulePassivePolling()
    }
} 