package net.melisma.data.mapper

import net.melisma.core_data.model.Attachment
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_db.entity.AttachmentEntity

fun AttachmentEntity.toDomainModel(): Attachment {
    return Attachment(
        id = this.attachmentId, // remote ID of the attachment
        messageId = this.messageId, // local DB ID of the parent message
        accountId = this.accountId, // Added field
        fileName = this.fileName,
        contentType = this.mimeType,
        size = this.size,
        isInline = this.isInline,
        contentId = this.contentId,
        localUri = this.localFilePath,
        remoteId = this.remoteAttachmentId, // Added field
        downloadStatus = when (this.syncStatus) {
            EntitySyncStatus.SYNCED -> if (this.isDownloaded) "DOWNLOADED" else "NOT_DOWNLOADED"
            EntitySyncStatus.PENDING_DOWNLOAD -> "DOWNLOADING"
            EntitySyncStatus.ERROR -> "ERROR"
            else -> "NOT_DOWNLOADED" // Default for PENDING_UPLOAD, PENDING_DELETE
        },
        lastSyncError = this.lastSyncError
    )
}

fun Attachment.toEntity(messageDbId: String, accountId: String): AttachmentEntity {
    // Note: The domain 'Attachment.id' is the remote attachment ID.
    // 'AttachmentEntity.attachmentId' is the primary key and should be this remote ID.
    // 'Attachment.messageId' from domain is the remote message ID.
    // 'AttachmentEntity.messageId' is the local DB ID of the message.

    return AttachmentEntity(
        attachmentId = this.id,
        messageId = messageDbId, // This needs to be the local DB ID of the message
        accountId = accountId, // Added field
        fileName = this.fileName,
        mimeType = this.contentType,
        size = this.size,
        isInline = this.isInline,
        contentId = this.contentId,
        remoteAttachmentId = this.remoteId, // Added field
        isDownloaded = this.localUri != null,
        localFilePath = this.localUri,
        downloadTimestamp = if (this.localUri != null) System.currentTimeMillis() else null,
        // Initial sync status based on domain model's downloadStatus
        syncStatus = when (this.downloadStatus) {
            "DOWNLOADED" -> EntitySyncStatus.SYNCED
            "DOWNLOADING" -> EntitySyncStatus.PENDING_DOWNLOAD
            "ERROR" -> EntitySyncStatus.ERROR
            else -> EntitySyncStatus.SYNCED // Assume not downloaded but synced server-side
        },
        lastSyncError = this.lastSyncError
    )
} 