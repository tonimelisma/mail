package net.melisma.core_data.model

import java.io.Serializable

// Represents a discrete, prioritizable unit of work for the SyncController.
// The priority value follows a strict hierarchy where higher numbers are processed first.
sealed class SyncJob(open val accountId: String, val priority: Int, open val workScore: Int) :
    Comparable<SyncJob>, Serializable {
    override fun compareTo(other: SyncJob) = other.priority.compareTo(priority)

    // Level 1: Golden Rule - User is actively waiting for this.
    data class FetchFullMessageBody(val messageId: String, override val accountId: String) :
        SyncJob(accountId, 95, 8)

    data class FetchNextMessageListPage(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 90, 5)

    data class ForceRefreshFolder(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 88, 5)

    data class SearchOnline(
        val query: String,
        val folderId: String? = null,
        override val accountId: String
    ) : SyncJob(accountId, 85, 3)

    // Level 2: Fulfilling User Intent - Uploading user-generated changes.
    data class UploadAction(
        override val accountId: String,
        val actionType: String? = null,
        val entityId: String? = null,
        val payload: Map<String, String?> = emptyMap()
    ) : SyncJob(accountId, 75, 2)

    // Level 4: Background Freshness & Backfill - Opportunistic, battery-conscious syncing.
    data class FetchMessageHeaders(
        val folderId: String,
        val pageToken: String?,
        override val accountId: String
    ) : SyncJob(accountId, 50, 1)

    data class CheckForNewMail(override val accountId: String) :
        SyncJob(accountId, 50, 1)

    data class SyncFolderList(override val accountId: String) :
        SyncJob(accountId, 40, 3)

    data class EvictFromCache(override val accountId: String) :
        SyncJob(accountId, 10, 10)

    // --- Legacy job names kept temporarily for backward compatibility ---

    // Maps to Level 1 FetchFullMessageBody
    data class DownloadMessageBody(val messageId: String, override val accountId: String) :
        SyncJob(accountId, 95, 8)

    // Maps to Level 1 FetchFullMessageBody when requesting refresh of folder. Priority slightly lower.
    data class RefreshFolderContents(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 88, 5)

    // Attachment download – treat with Level 1 urgency when user taps attachment.
    data class DownloadAttachment(
        val attachmentId: Long,
        val messageId: String,
        override val accountId: String
    ) : SyncJob(accountId, 90, 8)

    /**
     * Triggers a full bootstrap of *all* folders for the account. The SyncController will iterate
     * over every local folder (or call FolderRepository to pull the list first) and queue a
     * ForceRefreshFolder for each one. This is used immediately after an account is added or when
     * the user explicitly requests a full re-sync.
     */
    data class FullAccountBootstrap(override val accountId: String) : SyncJob(accountId, 100, 15)
} 