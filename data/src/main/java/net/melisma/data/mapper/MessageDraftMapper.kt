package net.melisma.data.mapper

import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_db.entity.MessageEntity
import java.util.UUID

fun MessageDraft.toEntity(
    id: String = UUID.randomUUID().toString(),
    accountId: String,
    isRead: Boolean,
    syncStatus: EntitySyncStatus
): MessageEntity {
    val snippet = if (body.length > 256) body.substring(0, 256) else body
    return MessageEntity(
        id = id,
        messageId = null, // No remote ID yet for a draft
        accountId = accountId,
        threadId = null,
        subject = subject,
        snippet = snippet,
        body = body,
        senderName = null,
        senderAddress = null,
        recipientNames = to.mapNotNull { it.displayName } + cc.mapNotNull { it.displayName } + bcc.mapNotNull { it.displayName },
        recipientAddresses = to.map { it.emailAddress } + cc.map { it.emailAddress } + bcc.map { it.emailAddress },
        timestamp = System.currentTimeMillis(),
        sentTimestamp = null,
        isRead = isRead,
        isStarred = false,
        hasAttachments = attachments.isNotEmpty(),
        isLocallyDeleted = false,
        lastSyncError = null,
        isDraft = true,
        isOutbox = false,
        draftType = type.name,
        draftParentId = inReplyTo,
        sendAttempts = 0,
        lastSendError = null,
        scheduledSendTime = null,
        syncStatus = syncStatus,
        lastSyncAttemptTimestamp = null,
        lastSuccessfulSyncTimestamp = null,
        isLocalOnly = true,
        needsFullSync = false,
        lastAccessedTimestamp = null
    )
} 