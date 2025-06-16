package net.melisma.data.mapper

import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_db.entity.MessageEntity
import java.util.UUID

fun MessageDraft.toEntity(
    id: String,
    accountId: String,
    senderName: String?,
    senderAddress: String?,
    isRead: Boolean,
    syncStatus: EntitySyncStatus
): MessageEntity {
    val snippet = if (body.length > 256) body.substring(0, 256) else body
    return MessageEntity(
        id = id,
        messageId = existingMessageId,
        threadId = null,
        accountId = accountId,
        subject = subject,
        snippet = snippet,
        body = body,
        senderName = senderName,
        senderAddress = senderAddress,
        recipientNames = to.mapNotNull { it.displayName },
        recipientAddresses = to.map { it.emailAddress },
        isRead = isRead,
        isStarred = false,
        isDraft = true,
        isOutbox = true,
        timestamp = System.currentTimeMillis(),
        sentTimestamp = null,
        lastSuccessfulSyncTimestamp = null,
        syncStatus = syncStatus,
        lastAccessedTimestamp = System.currentTimeMillis()
    )
} 