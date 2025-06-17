package net.melisma.core_data.model

import java.io.Serializable

// Represents a discrete, prioritizable unit of work for the SyncController.
// The priority value follows a strict hierarchy where higher numbers are processed first.
sealed class SyncJob(
    open val accountId: String,
    val priority: Int,
    open val workScore: Int,
    /** True if this job is a background, opportunistic download that should be paused by gatekeepers. */
    open val isProactiveDownload: Boolean = false
) : Comparable<SyncJob>, Serializable {
    override fun compareTo(other: SyncJob) = other.priority.compareTo(priority)

    // --- Job Definitions ---

    // Priority 100+: Critical System Actions
    data class FullAccountBootstrap(override val accountId: String) : SyncJob(accountId, 100, 15)

    // Priority 90-99: User is actively waiting for this content
    data class FetchFullMessageBody(val messageId: String, override val accountId: String) : SyncJob(accountId, 95, 8)
    data class DownloadAttachment(val messageId: String, val attachmentId: Long, override val accountId: String) : SyncJob(accountId, 90, 8)

    // Priority 80-89: User is waiting for a list of items to refresh
    data class ForceRefreshFolder(val folderId: String, override val accountId: String) : SyncJob(accountId, 88, 5)
    data class SearchOnline(val query: String, val folderId: String?, override val accountId: String) : SyncJob(accountId, 85, 3)

    // Priority 70-79: Uploading user-generated changes
    data class UploadAction(
        override val accountId: String,
        val actionType: String? = null,
        val entityId: String? = null,
        val payload: Map<String, String?> = emptyMap()
    ) : SyncJob(accountId, 75, 2)

    // Priority 60-69: Structural sync (e.g., folder lists)
    data class SyncFolderList(override val accountId: String) : SyncJob(accountId, 60, 3)

    // Priority 50-59: Lightweight background freshness checks
    data class CheckForNewMail(override val accountId: String) : SyncJob(accountId, 50, 1)

    // Priority 20-49: Proactive, opportunistic background downloads (can be vetoed by gatekeepers)
    data class HeaderBackfill(
        val folderId: String,
        val pageToken: String?,
        override val accountId: String
    ) : SyncJob(accountId, 40, 1, isProactiveDownload = true)

    data class BulkFetchBodies(
        override val accountId: String
    ) : SyncJob(accountId, 30, 5, isProactiveDownload = true)

    data class BulkFetchAttachments(
        override val accountId: String
    ) : SyncJob(accountId, 20, 10, isProactiveDownload = true)

    // Priority 10-19: System maintenance
    object EvictFromCache : SyncJob("system", 10, 10)

    // --- Legacy / Compatibility Job Definitions ---
    // These are kept to avoid breaking existing code that calls them.
    // They will be mapped to the new jobs in the SyncController.
    // In a real project, we would eventually refactor all callers and remove these.

    @Deprecated("Use HeaderBackfill instead", ReplaceWith("HeaderBackfill(folderId, pageToken, accountId)"))
    data class FetchMessageHeaders(
        val folderId: String,
        val pageToken: String?,
        override val accountId: String
    ) : SyncJob(accountId, 40, 1, isProactiveDownload = true)

    @Deprecated("Use FetchFullMessageBody instead", ReplaceWith("FetchFullMessageBody(messageId, accountId)"))
    data class DownloadMessageBody(val messageId: String, override val accountId: String) :
        SyncJob(accountId, 95, 8)

    @Deprecated("Use ForceRefreshFolder instead", ReplaceWith("ForceRefreshFolder(folderId, accountId)"))
    data class RefreshFolderContents(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 88, 5)

    @Deprecated("Superseded by HeaderBackfill and its internal paging", ReplaceWith("HeaderBackfill(folderId, null, accountId)"))
    data class FetchNextMessageListPage(val folderId: String, override val accountId: String) :
        SyncJob(accountId, 88, 5)
} 