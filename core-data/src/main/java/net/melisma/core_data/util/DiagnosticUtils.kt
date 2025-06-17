package net.melisma.core_data.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.app.usage.UsageStatsManager
import timber.log.Timber

/**
 * Collects a snapshot of device state that might influence background networking
 * (Doze, App Standby, power-save, data-saver, foreground-service, etc.) and logs
 * it via Timber. Call this whenever a suspicious network failure occurs.
 */
object DiagnosticUtils {

    @JvmStatic
    fun logDeviceState(context: Context, reason: String? = null) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val usm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            } else null

            val sb = StringBuilder("📊 Device diagnostics")
            reason?.let { sb.append(" | reason=$it") }
            sb.append(" | interactive=").append(pm.isInteractive)
            sb.append(" | idleMode=").append(pm.isDeviceIdleMode)
            sb.append(" | powerSave=").append(pm.isPowerSaveMode)
            sb.append(" | ignoreBattOpt=").append(pm.isIgnoringBatteryOptimizations(context.packageName))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val restrict = when (cm.restrictBackgroundStatus) {
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "ENABLED"
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "WHITELISTED"
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "DISABLED"
                    else -> cm.restrictBackgroundStatus.toString()
                }
                sb.append(" | dataSaver=").append(restrict)
            }

            if (usm != null) {
                val bucket = when (usm.appStandbyBucket) {
                    UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
                    UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
                    UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
                    UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
                    UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
                    else -> usm.appStandbyBucket.toString()
                }
                sb.append(" | standbyBucket=").append(bucket)
            }

            val net = cm.activeNetwork
            val caps: NetworkCapabilities? = cm.getNetworkCapabilities(net)
            sb.append(" | networkCaps=").append(caps?.toString() ?: "null")

            // foreground service running? (set by InitialSyncForegroundService)
            try {
                val isFgServiceRunning = Class.forName("net.melisma.mail.sync.InitialSyncForegroundService")
                    .getDeclaredField("isRunning")
                    .getBoolean(null)
                sb.append(" | fgServiceRunning=").append(isFgServiceRunning)
            } catch (_: Throwable) { /* service not on classpath of this module */ }

            Timber.tag("Diagnostics").i(sb.toString())
        } catch (t: Throwable) {
            Timber.tag("Diagnostics").e(t, "Failed to collect diagnostics")
        }
    }
} 