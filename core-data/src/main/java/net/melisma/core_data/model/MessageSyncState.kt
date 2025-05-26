package net.melisma.core_data.model

/**
 * Represents the synchronization state for messages within a specific folder.
 */
sealed class MessageSyncState {
    /** The initial state, or when no specific folder sync is active. */
    data object Idle : MessageSyncState()

    /** Indicates that messages for a folder are currently being synchronized (fetched from network and saved to DB). */
    data class Syncing(val accountId: String, val folderId: String) : MessageSyncState()

    /** Indicates that messages for a folder were successfully synchronized. */
    data class SyncSuccess(val accountId: String, val folderId: String) : MessageSyncState()

    /**
     * Indicates that an error occurred while synchronizing messages for a folder.
     * @param folderId The ID of the folder for which sync failed.
     * @param error A user-friendly error message.
     */
    data class SyncError(val accountId: String, val folderId: String, val error: String) :
        MessageSyncState()
} 