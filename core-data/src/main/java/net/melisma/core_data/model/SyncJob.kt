package net.melisma.core_data.model

import java.io.Serializable

// Represents a discrete, prioritizable unit of work for the SyncController.
// The priority value follows a strict hierarchy where higher numbers are processed first.
sealed class SyncJob(open val accountId: String, val priority: Int) :
    Comparable<SyncJob>, Serializable {
    override fun compareTo(other: SyncJob) = other.priority.compareTo(priority)

    // Level 1: Golden Rule - User is actively waiting for this.
    data class FetchFullMessageBody(val messageId: String, override val accountId: String) :
        SyncJob(accountId, 95)

    data class FetchNextMessageListPage(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 90)

    data class ForceRefreshFolder(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 88)

    data class SearchOnline(
        val query: String,
        val folderId: String? = null,
        override val accountId: String
    ) : SyncJob(accountId, 85)

    // Level 2: Fulfilling User Intent - Uploading user-generated changes.
    data class UploadAction(
        override val accountId: String,
        val actionType: String,
        val entityId: String,
        val payload: Map<String, String?> = emptyMap()
    ) : SyncJob(accountId, 75)

    // Level 4: Background Freshness & Backfill - Opportunistic, battery-conscious syncing.
    data class FetchMessageHeaders(
        val folderId: String,
        val pageToken: String?,
        override val accountId: String
    ) : SyncJob(accountId, 50)

    data class SyncFolderList(override val accountId: String) :
        SyncJob(accountId, 40)

    data class EvictFromCache(override val accountId: String) :
        SyncJob(accountId, 10)

    // --- Legacy job names kept temporarily for backward compatibility ---

    // Maps to Level 1 FetchFullMessageBody
    data class DownloadMessageBody(val messageId: String, override val accountId: String) :
        SyncJob(accountId, 95)

    // Maps to Level 1 FetchFullMessageBody when requesting refresh of folder. Priority slightly lower.
    data class RefreshFolderContents(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 88)

    // Attachment download – treat with Level 1 urgency when user taps attachment.
    data class DownloadAttachment(
        val attachmentId: String,
        val messageId: String,
        override val accountId: String
    ) : SyncJob(accountId, 90)
} 