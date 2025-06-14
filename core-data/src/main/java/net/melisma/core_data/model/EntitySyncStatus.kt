package net.melisma.core_data.model

/**
 * Represents the synchronization status of a single database entity.
 * This is distinct from [SyncControllerStatus], which represents the global state of the sync engine.
 */
enum class EntitySyncStatus {
    /** The entity is synced with the server and has no local changes. */
    SYNCED,

    /** The entity has local changes that need to be uploaded to the server. */
    PENDING_UPLOAD,

    /** The entity's full data is being downloaded from the server. */
    PENDING_DOWNLOAD,

    /** The last attempt to sync this entity resulted in an error. */
    ERROR,

    /** The entity has been marked for local deletion. */
    PENDING_DELETE
} 