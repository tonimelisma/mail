package net.melisma.mail.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.melisma.core_data.model.SyncControllerStatus
import net.melisma.data.sync.SyncController
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that runs during a brand-new account's first sync. It shows an ongoing
 * notification so the system treats the operation as high-priority and the user can see progress.
 *
 * The service stops itself automatically once [SyncController.status] reports that the controller
 * is no longer syncing **and** [SyncControllerStatus.initialSyncDurationDays] is honoured for at
 * least one folder (i.e. first page fetched).
 *
 * This new implementation uses the [SyncController.totalWorkScore] as the primary signal.
 * The service starts when the score is above a threshold and stops when it drops to zero
 * for a few seconds (debounced) to avoid flickering.
 */
@AndroidEntryPoint
class InitialSyncForegroundService : Service() {

    @Inject lateinit var syncController: SyncController
    @Inject lateinit var connectivityHealthTracker: net.melisma.core_data.connectivity.ConnectivityHealthTracker
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var watchJob: Job? = null
    private val WORK_SCORE_THRESHOLD = 5

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Timber.d("InitialSyncForegroundService: onCreate - Service is being created.")
        startInForeground()
        watchJob = serviceScope.launch {
            syncController.totalWorkScore
                .collect { score ->
                    val netState = connectivityHealthTracker.state.value.name
                    if (score > 0) {
                        updateNotification("Syncing mail… ($netState) score=$score")
                    } else {
                        delay(5000)
                        if (syncController.totalWorkScore.value == 0) {
                            Timber.d("InitialSyncForegroundService: Work score is zero, stopping service.")
                            stopSelf()
                        }
                    }
                }
        }
    }

    override fun onDestroy() {
        isRunning = false
        Timber.d("InitialSyncForegroundService: onDestroy - isRunning set to false")
        watchJob?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We now also check the work score here. If a start command comes in but there's
        // no work, we can stop immediately. This handles cases where the service is
        // started manually or restarted by the system.
        serviceScope.launch {
            if (syncController.totalWorkScore.value < WORK_SCORE_THRESHOLD) {
                Timber.d("Service started with work score below threshold. Stopping.")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @android.annotation.SuppressLint("MissingPermission", "NotificationPermission")
    private fun startInForeground() {
        val channelId = ensureChannel()
        val notification = buildNotification(channelId, "Preparing to sync mail…")
        Timber.d("InitialSyncForegroundService: startInForeground - Calling startForeground().")
        startForeground(NOTIFICATION_ID, notification)
    }

    @android.annotation.SuppressLint("MissingPermission", "NotificationPermission")
    private fun updateNotification(text: String) {
        val channelId = DEFAULT_CHANNEL_ID
        val updated = buildNotification(channelId, text)
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, updated)
        } else {
            // Permission not granted; skip update to avoid security exception.
            Timber.w("Missing POST_NOTIFICATIONS permission – skipping status update")
        }
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Mail Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Synchronization status"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return DEFAULT_CHANNEL_ID
    }

    private fun buildNotification(channelId: String, text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Mail Sync")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val DEFAULT_CHANNEL_ID = "mail_sync"
        private const val NOTIFICATION_ID = 1001
        @Volatile
        var isRunning: Boolean = false
    }
} 