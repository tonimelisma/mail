package net.melisma.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import net.melisma.core_data.repository.AccountRepository
import android.content.Context
import net.melisma.core_data.util.DiagnosticUtils
import androidx.core.content.ContextCompat

/**
 * Observes the application process lifecycle and instructs [SyncController] to start/stop
 * its aggressive foreground polling while also scheduling/cancelling the passive
 * background polling PeriodicWorkRequest.
 */
@Singleton
class SyncLifecycleObserver @Inject constructor(
    private val syncController: SyncController,
    private val accountRepository: AccountRepository,
    @net.melisma.core_data.di.ApplicationScope private val appScope: CoroutineScope,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        Timber.d("SyncLifecycleObserver: onStart – app entered foreground")
        net.melisma.core_data.util.DiagnosticUtils.logDeviceState(appContext, "lifecycle onStart")
        // Kick off a full bootstrap sync for every account (de-duplicated by the queue).
//        appScope.launch {
//            val accounts = accountRepository.getAccounts().first()
//            accounts.forEach { account ->
//                syncController.submit(net.melisma.core_data.model.SyncJob.FullAccountBootstrap(account.id))
//            }
//        }
//        syncController.stopPassivePolling()
//        syncController.startActivePolling()
    }

    override fun onStop(owner: LifecycleOwner) {
        Timber.d("SyncLifecycleObserver: onStop – app entered background")
        net.melisma.core_data.util.DiagnosticUtils.logDeviceState(appContext, "lifecycle onStop")
        syncController.stopActivePolling()
        syncController.startPassivePolling()

        // Ensure foreground sync service starts right away if there's outstanding work.
        if (syncController.totalWorkScore.value > 0) {
            val intent = android.content.Intent().apply {
                setClassName(appContext, "net.melisma.mail.sync.InitialSyncForegroundService")
            }
            androidx.core.content.ContextCompat.startForegroundService(appContext, intent)
        }
    }
} 