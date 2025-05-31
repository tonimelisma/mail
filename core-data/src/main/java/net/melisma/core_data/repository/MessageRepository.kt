package net.melisma.core_data.repository

import android.app.Activity
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.Attachment
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.MessageSyncState

/**
 * Interface defining the contract for managing mail messages within specific folders.
 * Implementations handle fetching message lists, potentially supporting pagination, search,
 * and actions like marking read/unread or deleting messages.
 */
interface MessageRepository {

    /**
     * A [StateFlow] emitting the current synchronization state ([MessageSyncState])
     * for messages in the folder/account currently being synced or recently synced.
     */
    val messageSyncState: StateFlow<MessageSyncState>

    /**
     * Observes messages for a given account and folder directly from the local database.
     * The UI should use this to display the list of messages.
     *
     * @param accountId The ID of the account.
     * @param folderId The ID of the folder.
     * @return A Flow emitting the list of [Message] objects.
     */
    fun observeMessagesForFolder(accountId: String, folderId: String): Flow<List<Message>>

    /**
     * Returns a Flow of PagingData for messages in the specified folder, suitable for use with Paging 3.
     *
     * @param accountId The ID of the account.
     * @param folderId The ID of the folder.
     * @param pagingConfig The PagingConfig to configure Paging 3.
     * @return A Flow emitting PagingData<Message>.
     */
    fun getMessagesPager(
        accountId: String,
        folderId: String,
        pagingConfig: PagingConfig
    ): Flow<PagingData<Message>>

    /**
     * Sets the target account and folder for which messages should be fetched and observed via [messageDataState].
     * Calling this typically cancels any previous fetch and triggers an initial fetch for the new target.
     * Passing null for either parameter should clear the target and reset [messageDataState] to [MessageDataState.Initial].
     *
     * @param account The target [Account] whose folder is being viewed, or null to clear.
     * @param folder The target [MailFolder] being viewed, or null to clear.
     */
    suspend fun setTargetFolder(account: Account?, folder: MailFolder?)

    /**
     * Triggers a background synchronization of messages for the currently targeted folder/account.
     * Updates to sync status will be emitted via [messageSyncState].
     * Data updates will be reflected in flows from [observeMessagesForFolder].
     *
     * @param activity Optional [Activity] context.
     */
    suspend fun refreshMessages(activity: Activity? = null)

    /**
     * Explicitly triggers a synchronization of messages for the given account and folder.
     * Updates to sync status will be emitted via [messageSyncState].
     * Data updates will be reflected in flows from [observeMessagesForFolder].
     *
     * @param accountId The ID of the account.
     * @param folderId The ID of the folder.
     * @param activity Optional [Activity] context.
     */
    suspend fun syncMessagesForFolder(
        accountId: String,
        folderId: String,
        activity: Activity? = null
    )

    // Potential future methods for message management:
    // suspend fun loadMoreMessages() // For pagination
    // suspend fun searchMessages(query: String): Flow<List<Message>> // For search functionality
    suspend fun markMessageRead(account: Account, messageId: String, isRead: Boolean): Result<Unit>
    suspend fun starMessage(account: Account, messageId: String, isStarred: Boolean): Result<Unit>
    suspend fun deleteMessage(account: Account, messageId: String): Result<Unit>
    suspend fun moveMessage(
        account: Account,
        messageId: String,
        newFolderId: String
    ): Result<Unit>

    /**
     * Fetches the details for a specific message.
     *
     * @param messageId The ID of the message to fetch.
     * @param accountId The ID of the account to which the message belongs.
     * @return A Flow emitting the Message object, or null if not found or an error occurs.
     */
    suspend fun getMessageDetails(
        messageId: String,
        accountId: String
    ): Flow<Message?>

    // New methods for draft and message sending
    suspend fun createDraftMessage(accountId: String, draftDetails: MessageDraft): Result<Message>
    suspend fun updateDraftMessage(
        accountId: String,
        messageId: String,
        draftDetails: MessageDraft
    ): Result<Message>

    suspend fun sendMessage(draft: MessageDraft, account: Account): Result<String>

    // New method for searching messages
    fun searchMessages(
        accountId: String,
        query: String,
        folderId: String? = null
    ): Flow<List<Message>>

    // New methods for attachments
    suspend fun getMessageAttachments(accountId: String, messageId: String): Flow<List<Attachment>>
    suspend fun downloadAttachment(
        accountId: String,
        messageId: String,
        attachment: Attachment
    ): Flow<String?>
}
