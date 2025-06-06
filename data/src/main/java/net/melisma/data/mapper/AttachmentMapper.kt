package net.melisma.data.mapper

import net.melisma.core_data.model.Attachment
import net.melisma.core_db.entity.AttachmentEntity

fun AttachmentEntity.toDomainModel(): Attachment {
    return Attachment(
        id = this.attachmentId, // remote ID of the attachment
        messageId = this.messageId, // local DB ID of the parent message
        fileName = this.fileName,
        contentType = this.mimeType,
        size = this.size,
        isInline = this.isInline,
        contentId = this.contentId,
        localUri = this.localFilePath,
        downloadStatus = when {
            this.isDownloaded -> "DOWNLOADED" // Or use a more specific SyncStatus if applicable
            this.syncStatus == net.melisma.core_data.model.SyncStatus.ERROR -> "ERROR"
            // TODO: Determine other statuses based on SyncStatus or other fields
            else -> "NOT_DOWNLOADED"
        },
        lastSyncError = this.lastSyncError
    )
}

fun Attachment.toEntity(messageDbId: String): AttachmentEntity {
    // Note: The domain 'Attachment.id' is the remote attachment ID.
    // 'AttachmentEntity.attachmentId' is the primary key and should be this remote ID.
    // 'Attachment.messageId' from domain is the remote message ID.
    // 'AttachmentEntity.messageId' is the local DB ID of the message.

    return AttachmentEntity(
        attachmentId = this.id,
        messageId = messageDbId, // This needs to be the local DB ID of the message
        fileName = this.fileName,
        mimeType = this.contentType,
        size = this.size,
        isInline = this.isInline,
        contentId = this.contentId,
        isDownloaded = this.localUri != null,
        localFilePath = this.localUri,
        downloadTimestamp = if (this.localUri != null) System.currentTimeMillis() else null,
        // Initial sync status based on domain model's downloadStatus
        syncStatus = when (this.downloadStatus) {
            "DOWNLOADED" -> net.melisma.core_data.model.SyncStatus.SYNCED
            "ERROR" -> net.melisma.core_data.model.SyncStatus.ERROR
            else -> net.melisma.core_data.model.SyncStatus.IDLE // Or PENDING_DOWNLOAD if that's implied
        },
        lastSyncError = this.lastSyncError
    )
} 