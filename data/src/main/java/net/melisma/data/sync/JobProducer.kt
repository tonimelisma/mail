package net.melisma.data.sync

import net.melisma.core_data.model.SyncJob

/**
 * Discrete component that can examine application state and generate [SyncJob]s
 * for the central [SyncController] queue.  Implementations should be *lightweight*
 * and avoid heavy I/O on the caller thread.  All expensive work must be done
 * on a dispatcher appropriate for the task (typically IO).
 */
interface JobProducer {
    suspend fun produce(): List<SyncJob>
} 