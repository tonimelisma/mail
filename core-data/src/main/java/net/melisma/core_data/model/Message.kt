package net.melisma.core_data.model

/**
 * Represents the core information needed to display a single email message
 * in a list view (e.g., the inbox). It contains key fields like sender,
 * subject, date, preview, and read status.
 *
 * @property id The unique identifier of the message provided by the backend service.
 * @property receivedDateTime The date and time the message was received, typically in ISO 8601 format string.
 * @property subject The subject line of the message. Can be null or empty.
 * @property senderName The display name of the sender, if available.
 * @property senderAddress The email address of the sender.
 * @property bodyPreview A short plain text preview of the message body.
 * @property isRead A boolean flag indicating whether the message has been marked as read.
 * @property threadId Stores Gmail threadId or Outlook conversationId
 */
data class Message(
    val id: String,
    val threadId: String?,
    val receivedDateTime: String, // Consider parsing this to a date/time type later if needed for sorting/logic
    val subject: String?,
    val senderName: String?,
    val senderAddress: String?,
    val bodyPreview: String?,
    val isRead: Boolean,
    val body: String? = null
)
