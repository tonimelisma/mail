package net.melisma.core_data.repository

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.ThreadDataState

interface ThreadRepository {
    val threadDataState: StateFlow<ThreadDataState>

    /**
     * Sets the target account and folder for which threads should be fetched.
     * Triggers an initial fetch.
     */
    suspend fun setTargetFolderForThreads(
        account: Account?,
        folder: MailFolder?,
        activityForRefresh: Activity? = null // For potential auth needs during refresh
    )

    /**
     * Refreshes the threads for the currently set target folder and account.
     */
    suspend fun refreshThreads(activity: Activity? = null)

    suspend fun markThreadRead(account: Account, threadId: String, isRead: Boolean): Result<Unit>
    suspend fun deleteThread(account: Account, threadId: String): Result<Unit>
    suspend fun moveThread(
        account: Account,
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit>
} 