package net.melisma.core_data.model

import kotlinx.serialization.Serializable

// Assuming Message content might be complex, placeholder for now if not already defined
// For example, if body is HTML or has attachments to reference before sending.
// If Message.kt defines a suitable structure for a draft, this could be simplified
// or Message could be made more flexible.

@Serializable
data class MessageDraft(
    val to: List<String> = emptyList(),
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val subject: String? = null,
    val body: String? = null, // Could be plain text or HTML
    val originalMessageId: String? = null, // For replies/forwards, references the ID of the source message
    val type: DraftType,
    val attachments: List<String> = emptyList() // Placeholder for attachment identifiers if needed during compose
) 