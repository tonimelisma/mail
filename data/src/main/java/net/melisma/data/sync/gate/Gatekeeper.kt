package net.melisma.data.sync.gate

import net.melisma.core_data.model.SyncJob

/**
 * A Gatekeeper can veto the queuing of a [SyncJob]. Gatekeepers are consulted by the
 * [SyncController] before a job is added to the queue.
 */
interface Gatekeeper {
    /**
     * @return `true` if the job is allowed to be queued, `false` otherwise.
     */
    suspend fun isAllowed(job: SyncJob): Boolean
} 