package net.melisma.data.mapper

import net.melisma.core_data.model.Message
import net.melisma.core_db.entity.MessageEntity
import timber.log.Timber
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

// net.melisma.core_data.model.Message is now the enriched domain model

fun Message.toEntity(accountId: String): MessageEntity {
    val receivedTs = try {
        OffsetDateTime.parse(this.receivedDateTime).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        Timber.w(
            e,
            "Failed to parse receivedDateTime: '${this.receivedDateTime}'. Defaulting to 0L for accountId: $accountId, messageId: ${this.id}"
        )
        0L
    }

    val sentTs: Long? = this.sentDateTime?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            Timber.w(
                e,
                "Failed to parse sentDateTime: '$it'. Defaulting to null for accountId: $accountId, messageId: ${this.id}"
            )
            null
        }
    }

    return MessageEntity(
        id = this.id.ifEmpty { UUID.randomUUID().toString() },
        messageId = this.remoteId,
        accountId = accountId,
        threadId = this.threadId,
        subject = this.subject,
        snippet = this.bodyPreview,
        body = this.body,
        senderName = this.senderName,
        senderAddress = this.senderAddress,
        recipientNames = this.recipientNames,
        recipientAddresses = this.recipientAddresses,
        timestamp = receivedTs,
        sentTimestamp = sentTs,
        isRead = this.isRead,
        isStarred = this.isStarred,
        hasAttachments = this.hasAttachments,
        lastSuccessfulSyncTimestamp = this.lastSuccessfulSyncTimestamp
    )
}

fun MessageEntity.toDomainModel(): Message {
    val receivedDateTimeStr = try {
        OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(this.timestamp), ZoneOffset.UTC)
            .toString()
    } catch (e: Exception) {
        Timber.w(
            e,
            "Failed to parse timestamp in MessageEntity.toDomainModel for id ${this.id}. Defaulting receivedDateTime to empty string."
        )
        ""
    }
    val sentDateTimeStr: String? = this.sentTimestamp?.let {
        try {
            OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneOffset.UTC).toString()
        } catch (e: Exception) {
            Timber.w(
                e,
                "Failed to parse sentTimestamp in MessageEntity.toDomainModel for id ${this.id}. Defaulting sentDateTime to null."
            )
            null
        }
    }

    return Message(
        id = this.id,
        remoteId = this.messageId,
        accountId = this.accountId,
        folderId = "",
        threadId = this.threadId,
        receivedDateTime = receivedDateTimeStr,
        sentDateTime = sentDateTimeStr,
        subject = this.subject,
        senderName = this.senderName,
        senderAddress = this.senderAddress,
        bodyPreview = this.snippet,
        isRead = this.isRead,
        body = this.body,
        bodyContentType = null,
        recipientNames = this.recipientNames,
        recipientAddresses = this.recipientAddresses,
        isStarred = this.isStarred,
        hasAttachments = this.hasAttachments,
        timestamp = this.timestamp,
        attachments = emptyList(),
        lastSuccessfulSyncTimestamp = this.lastSuccessfulSyncTimestamp,
        isOutbox = this.isOutbox
    )
}

// Legacy overload kept for transitional compilation; folderId now handled via junction
fun Message.toEntity(accountId: String, folderId: String): MessageEntity = this.toEntity(accountId)

// Context-aware overload that injects the provided folderId for UI convenience
fun MessageEntity.toDomainModel(folderIdContext: String): Message {
    return this.toDomainModel().copy(folderId = folderIdContext)
}

// NOTE: Added here due to tool limitations preventing new file creation.
// Ideally, this would be in its own AttachmentMapper.kt file.
fun net.melisma.core_data.model.Attachment.toEntity(): net.melisma.core_db.entity.AttachmentEntity {
    return net.melisma.core_db.entity.AttachmentEntity(
        id = this.localId ?: 0,
        messageId = this.messageId,
        accountId = this.accountId,
        remoteAttachmentId = this.remoteId,
        fileName = this.fileName,
        size = this.size,
        mimeType = this.contentType,
        contentId = this.contentId,
        isInline = this.isInline,
        isDownloaded = this.localUri != null,
        localFilePath = this.localUri
    )
} 