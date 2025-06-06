package net.melisma.core_data.model

import kotlinx.serialization.Serializable

// Assuming Message content might be complex, placeholder for now if not already defined
// For example, if body is HTML or has attachments to reference before sending.
// If Message.kt defines a suitable structure for a draft, this could be simplified
// or Message could be made more flexible.

@Serializable
data class MessageDraft(
    val existingMessageId: String? = null, // The local ID of the message if it's an existing draft
    val inReplyTo: String? = null, // The remote ID of the message being replied to
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val attachments: List<Attachment> = emptyList(),
    val type: DraftType = DraftType.NEW // Default to new
)

@Serializable
data class EmailAddress(
    val emailAddress: String,
    val displayName: String? = null
) 