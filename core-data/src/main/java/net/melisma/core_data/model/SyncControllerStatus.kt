package net.melisma.core_data.model

import java.io.Serializable

/**
 * Represents the real-time status of the [net.melisma.data.sync.SyncController].
 *
 * @param isSyncing Whether the controller is actively processing a job.
 * @param currentJob A description of the job currently being processed.
 * @param networkAvailable Whether the device has an active network connection.
 * @param error A description of the last error that occurred, if any.
 */
data class SyncControllerStatus(
    val isSyncing: Boolean = false,
    val currentJob: String? = null,
    val networkAvailable: Boolean = true,
    val error: String? = null,
) : Serializable 