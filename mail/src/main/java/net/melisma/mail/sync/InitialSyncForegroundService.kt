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
 * least one folder (i.e. first page fetched). For a first iteration we simply monitor the global
 * isSyncing flag and stop when it turns false for 5 consecutive seconds.
 */
@AndroidEntryPoint
class InitialSyncForegroundService : Service() {

    @Inject lateinit var syncController: SyncController
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        watchJob = serviceScope.launch {
            var idleTicks = 0
            syncController.status.collect { status ->
                updateNotification(status)
                if (!status.isSyncing) {
                    idleTicks++
                    if (idleTicks >= 5) {
                        Timber.d("InitialSyncForegroundService: Sync idle, stopping service")
                        stopSelf()
                    }
                } else {
                    idleTicks = 0
                }
            }
        }
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nothing to do – logic handled in onCreate.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @android.annotation.SuppressLint("MissingPermission", "NotificationPermission")
    private fun startInForeground() {
        val channelId = ensureChannel()
        val notification = buildNotification(channelId, "Initial mail sync in progress…")
        startForeground(NOTIFICATION_ID, notification)
    }

    @android.annotation.SuppressLint("MissingPermission", "NotificationPermission")
    private fun updateNotification(status: SyncControllerStatus) {
        val channelId = DEFAULT_CHANNEL_ID
        val text = if (status.isSyncing) {
            "Syncing mail…"
        } else {
            "Finishing up…"
        }
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
            .setContentTitle("Mail is syncing")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val DEFAULT_CHANNEL_ID = "mail_sync"
        private const val NOTIFICATION_ID = 1001
    }
} 