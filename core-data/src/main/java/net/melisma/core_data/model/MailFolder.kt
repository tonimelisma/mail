package net.melisma.core_data.model

/**
 * Represents essential information about a mail folder, primarily used for display
 * in navigation drawers or folder lists.
 *
 * @property id The unique identifier of the folder provided by the backend service.
 * @property displayName The user-visible name of the folder (e.g., "Inbox", "Sent Items").
 * @property totalItemCount The total number of items (messages) within the folder.
 * @property unreadItemCount The number of unread items within the folder.
 */
data class MailFolder(
    val id: String,
    val displayName: String,
    val totalItemCount: Int,
    val unreadItemCount: Int
)
