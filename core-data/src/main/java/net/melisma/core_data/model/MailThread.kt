package net.melisma.core_data.model

import java.util.Date // For parsed lastMessageDateTime

/**
 * Represents a conversation thread, containing a list of messages and summary information.
 *
 * @property id The unique identifier of the thread (from Gmail's threadId or Outlook's conversationId).
 * @property messages A list of [Message] objects belonging to this thread, typically sorted by date.
 * @property subject The subject line for the thread, often taken from the latest or first message.
 * @property snippet A short preview of the latest message or overall thread content.
 * @property lastMessageDateTime The parsed [Date] of the last message in the thread, used for sorting threads.
 * @property participantsSummary A display string summarizing the participants (e.g., "John, Jane, +2 more").
 * @property unreadMessageCount The number of unread messages within this thread.
 * @property totalMessageCount The total number of messages within this thread.
 * @property accountId The ID of the [Account] this thread belongs to.
 */
data class MailThread(
    val id: String,
    val messages: List<Message>,
    val subject: String?,
    val snippet: String?,
    val lastMessageDateTime: Date?,
    val participantsSummary: String?,
    val unreadMessageCount: Int = 0,
    val totalMessageCount: Int = 0,
    val accountId: String
) 