package net.melisma.data.mapper

import net.melisma.core_data.model.Message
import net.melisma.core_db.entity.MessageEntity
import timber.log.Timber
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// net.melisma.core_data.model.Message is now the enriched domain model

fun Message.toEntity(accountId: String, folderId: String): MessageEntity {
    val receivedTs = try {
        OffsetDateTime.parse(this.receivedDateTime).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        Timber.w(
            e,
            "Failed to parse receivedDateTime: '${this.receivedDateTime}'. Defaulting to 0L for accountId: $accountId, folderId: $folderId, messageId: ${this.id}"
        )
        0L
    }

    val sentTs: Long? = this.sentDateTime?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            Timber.w(
                e,
                "Failed to parse sentDateTime: '$it'. Defaulting to null for accountId: $accountId, folderId: $folderId, messageId: ${this.id}"
            )
            null
        }
    }

    return MessageEntity(
        id = this.id,
        messageId = this.remoteId,
        accountId = accountId,
        folderId = folderId,
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
        folderId = this.folderId,
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