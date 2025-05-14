package net.melisma.backend_google.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response model for Gmail API /users/me/labels endpoint
 */
@Serializable
data class GmailLabelList(
    @SerialName("labels") val labels: List<GmailLabel> = emptyList()
)

/**
 * Model for a Gmail label
 */
@Serializable
data class GmailLabel(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("type") val type: String,
    @SerialName("messageListVisibility") val messageListVisibility: String? = "show",
    @SerialName("labelListVisibility") val labelListVisibility: String? = "labelShow",
    @SerialName("color") val color: LabelColor? = null,
    @SerialName("messagesTotal") val messagesTotal: Int? = 0,
    @SerialName("messagesUnread") val messagesUnread: Int? = 0
)

/**
 * Model for label color
 */
@Serializable
data class LabelColor(
    @SerialName("textColor") val textColor: String? = null,
    @SerialName("backgroundColor") val backgroundColor: String? = null
)

/**
 * Response model for Gmail API /users/me/messages endpoint
 */
@Serializable
data class GmailMessageList(
    @SerialName("messages") val messages: List<GmailMessageIdentifier> = emptyList(),
    @SerialName("nextPageToken") val nextPageToken: String? = null,
    @SerialName("resultSizeEstimate") val resultSizeEstimate: Int? = 0
)

/**
 * Identifier for a Gmail message
 */
@Serializable
data class GmailMessageIdentifier(
    @SerialName("id") val id: String,
    @SerialName("threadId") val threadId: String
)

/**
 * Model for a full Gmail message
 */
@Serializable
data class GmailMessage(
    @SerialName("id") val id: String,
    @SerialName("threadId") val threadId: String,
    @SerialName("labelIds") val labelIds: List<String> = emptyList(),
    @SerialName("snippet") val snippet: String? = null,
    @SerialName("historyId") val historyId: String? = null,
    @SerialName("internalDate") val internalDate: String? = null,
    @SerialName("sizeEstimate") val sizeEstimate: Int? = 0,
    @SerialName("payload") val payload: MessagePayload? = null
)

/**
 * Message payload containing headers and parts
 */
@Serializable
data class MessagePayload(
    @SerialName("mimeType") val mimeType: String? = "text/plain",
    @SerialName("headers") val headers: List<MessagePartHeader> = emptyList(),
    @SerialName("body") val body: MessagePartBody? = null,
    @SerialName("parts") val parts: List<MessagePart>? = null
)

/**
 * Message part header
 */
@Serializable
data class MessagePartHeader(
    @SerialName("name") val name: String,
    @SerialName("value") val value: String
)

/**
 * Message part body
 */
@Serializable
data class MessagePartBody(
    @SerialName("size") val size: Int? = 0,
    @SerialName("data") val data: String? = null,
    @SerialName("attachmentId") val attachmentId: String? = null
)

/**
 * Message part (for multipart messages)
 */
@Serializable
data class MessagePart(
    @SerialName("partId") val partId: String? = null,
    @SerialName("mimeType") val mimeType: String? = "text/plain",
    @SerialName("filename") val filename: String? = null,
    @SerialName("headers") val headers: List<MessagePartHeader>? = null,
    @SerialName("body") val body: MessagePartBody? = null,
    @SerialName("parts") val parts: List<MessagePart>? = null
)

/**
 * Model for a Gmail thread resource, typically returned by threads.get API.
 */
@Serializable
data class GmailThread(
    @SerialName("id") val id: String,
    @SerialName("snippet") val snippet: String? = null, // Snippet of the latest message in the thread
    @SerialName("historyId") val historyId: String,
    // Messages are often included directly when fetching a thread,
    // ensure your `GmailMessage` model is compatible.
    @SerialName("messages") val messages: List<GmailMessage> = emptyList()
)