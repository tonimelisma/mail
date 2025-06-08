package net.melisma.core_data.model

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val id: String,
    val messageId: String,
    val accountId: String,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val isInline: Boolean,
    val contentId: String?,
    val localUri: String?,
    val remoteId: String?,
    val downloadStatus: String,
    val lastSyncError: String?
) 