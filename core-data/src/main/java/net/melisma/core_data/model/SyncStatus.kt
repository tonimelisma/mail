package net.melisma.core_data.model

enum class SyncStatus {
    IDLE, // No sync operation active, and data is considered up-to-date or no action needed.
    SYNCED, // Data has been successfully synced with the server.
    PENDING_UPLOAD, // Local changes are pending upload to the server.
    PENDING_DOWNLOAD, // Waiting for data to be downloaded from the server.
    ERROR // A sync error occurred.
} 