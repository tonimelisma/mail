package net.melisma.core_data.model

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val id: String,
    val fileName: String,
    val size: Long, // Size in bytes
    val contentType: String, // MIME type
    val contentId: String? = null, // Optional content ID for inline attachments
    val isInline: Boolean = false
) 