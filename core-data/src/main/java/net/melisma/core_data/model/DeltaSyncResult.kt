package net.melisma.core_data.model

/**
 * Represents the result of a delta synchronization operation.
 *
 * @param T The type of the items being synced (e.g., MailFolder, Message).
 * @property newOrUpdatedItems A list of items that are new or have been updated since the last sync.
 * @property deletedItemIds A list of remote IDs for items that have been deleted since the last sync.
 * @property nextSyncToken The token to be used for the next delta sync request. Null if there are no more changes
 *                         or if the provider doesn't support further delta syncs with this token.
 */
data class DeltaSyncResult<T>(
    val newOrUpdatedItems: List<T>,
    val deletedItemIds: List<String>,
    val nextSyncToken: String?
) 