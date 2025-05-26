package net.melisma.core_data.model

import java.time.OffsetDateTime

/**
 * Represents the core information needed to display a single email message
 * in a list view (e.g., the inbox). It contains key fields like sender,
 * subject, date, preview, and read status.
 *
 * @property id The unique identifier of the message provided by the backend service.
 * @property receivedDateTime The date and time the message was received, typically in ISO 8601 format string.
 * @property sentDateTime The date and time the message was sent, typically in ISO 8601 format string.
 * @property subject The subject line of the message. Can be null or empty.
 * @property senderName The display name of the sender, if available.
 * @property senderAddress The email address of the sender.
 * @property bodyPreview A short plain text preview of the message body.
 * @property isRead A boolean flag indicating whether the message has been marked as read.
 * @property threadId Stores Gmail threadId or Outlook conversationId
 * @property recipientNames List of display names of recipients.
 * @property recipientAddresses List of email addresses of recipients.
 * @property isStarred Whether the message is starred.
 * @property hasAttachments Whether the message has attachments.
 * @property timestamp The received date and time as a Unix timestamp (milliseconds UTC).
 * @property body Full body of the message, typically fetched on demand.
 */
data class Message(
    val id: String,
    val threadId: String?,
    val receivedDateTime: String,
    val sentDateTime: String?,
    val subject: String?,
    val senderName: String?,
    val senderAddress: String?,
    val bodyPreview: String?,
    val isRead: Boolean,
    val body: String? = null, // Full body, typically loaded on demand

    // Added for alignment with MessageEntity and richer domain model
    val recipientNames: List<String>? = null,
    val recipientAddresses: List<String>? = null,
    val isStarred: Boolean = false,
    val hasAttachments: Boolean = false,
    val timestamp: Long = 0L // Derived from receivedDateTime, default to 0 or handle parsing in constructor/factory
) {
    companion object
    // Secondary constructor or init block could parse receivedDateTime to timestamp if needed here
    // For now, mappers will handle the derivation.
}

// Consider a factory function if complex initialization/derivation is needed for timestamp
fun Message.Companion.fromApi(
    id: String,
    threadId: String?,
    receivedDateTime: String,
    sentDateTime: String?,
    subject: String?,
    senderName: String?,
    senderAddress: String?,
    bodyPreview: String?,
    isRead: Boolean,
    body: String? = null,
    recipientNames: List<String>? = null,
    recipientAddresses: List<String>? = null,
    isStarred: Boolean = false,
    hasAttachments: Boolean = false
): Message {
    val derivedTimestamp = try {
        OffsetDateTime.parse(receivedDateTime).toInstant().toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis() // Fallback
    }
    return Message(
        id = id,
        threadId = threadId,
        receivedDateTime = receivedDateTime,
        sentDateTime = sentDateTime,
        subject = subject,
        senderName = senderName,
        senderAddress = senderAddress,
        bodyPreview = bodyPreview,
        isRead = isRead,
        body = body,
        recipientNames = recipientNames,
        recipientAddresses = recipientAddresses,
        isStarred = isStarred,
        hasAttachments = hasAttachments,
        timestamp = derivedTimestamp
    )
}
