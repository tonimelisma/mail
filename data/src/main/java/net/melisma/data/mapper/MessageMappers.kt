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
        0L // Use 0L as a marker for "unknown/unparseable date"
    }

    val sentTs: Long? = this.sentDateTime?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            Timber.w(
                e,
                "Failed to parse sentDateTime: '$it'. Defaulting to null for accountId: $accountId, folderId: $folderId, messageId: ${this.id}"
            )
            null // If sentDateTime is present but unparseable, store null
        }
    }

    return MessageEntity(
        messageId = this.id,
        accountId = accountId,
        folderId = folderId,
        threadId = this.threadId,
        subject = this.subject,
        snippet = this.bodyPreview,
        senderName = this.senderName,
        senderAddress = this.senderAddress,
        recipientNames = this.recipientNames,       // Now directly from Message model
        recipientAddresses = this.recipientAddresses, // Now directly from Message model
        timestamp = receivedTs, // this.timestamp is also available if Message.fromApi was used
        sentTimestamp = sentTs,                     // Added
        isRead = this.isRead,
        isStarred = this.isStarred,                 // Now directly from Message model
        hasAttachments = this.hasAttachments        // Now directly from Message model
    )
}

fun MessageEntity.toDomainModel(): Message {
    val receivedDateTimeStr = try {
        OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(this.timestamp), ZoneOffset.UTC)
            .toString()
    } catch (e: Exception) {
        "" // Fallback for invalid timestamp
    }
    val sentDateTimeStr: String? = this.sentTimestamp?.let {
        try {
            OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneOffset.UTC).toString()
        } catch (e: Exception) {
            null
        }
    }

    return Message(
        id = this.messageId,
        threadId = this.threadId,
        receivedDateTime = receivedDateTimeStr,
        sentDateTime = sentDateTimeStr, // Mapped from sentTimestamp
        subject = this.subject,
        senderName = this.senderName,
        senderAddress = this.senderAddress,
        bodyPreview = this.snippet,
        isRead = this.isRead,
        body = null, // Full body is not part of this basic message model/entity
        recipientNames = this.recipientNames,
        recipientAddresses = this.recipientAddresses,
        isStarred = this.isStarred,
        hasAttachments = this.hasAttachments,
        timestamp = this.timestamp // Pass through the Long timestamp
    )
} 