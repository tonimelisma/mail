package net.melisma.core_data.model

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val id: String,
    val messageId: String,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val isInline: Boolean,
    val contentId: String?,
    val localUri: String?,
    val downloadStatus: String,
    val lastSyncError: String?
) 