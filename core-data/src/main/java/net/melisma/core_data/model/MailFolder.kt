package net.melisma.core_data.model

// Define the WellKnownFolderType enum
enum class WellKnownFolderType {
    INBOX,
    SENT_ITEMS,
    DRAFTS,
    ARCHIVE,
    TRASH,
    SPAM,
    IMPORTANT,
    STARRED,
    USER_CREATED, // For folders created by the user that don't match a well-known type
    HIDDEN,       // For system folders that should be hidden from the user (e.g., "Conversation History")
    OTHER         // For any other system folders that aren't explicitly mapped or hidden but are visible
}

/**
 * Represents essential information about a mail folder, primarily used for display
 * in navigation drawers or folder lists.
 *
 * @property id The unique identifier of the folder provided by the backend service.
 * @property displayName The user-visible name of the folder (e.g., "Inbox", "Sent Items").
 * @property totalItemCount The total number of items (messages) within the folder.
 * @property unreadItemCount The number of unread items within the folder.
 * @property type The well-known type of the folder.
 */
data class MailFolder(
    val id: String,
    val displayName: String,
    val totalItemCount: Int,
    val unreadItemCount: Int,
    val type: WellKnownFolderType = WellKnownFolderType.USER_CREATED // Default to USER_CREATED
)