package net.melisma.core_data.datasource

import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.DeltaSyncResult
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.PagedMessagesResponse

/**
 * Interface defining the common mail operations across different email providers.
 * Implementations will be provider-specific (MS Graph API, Gmail API, etc.).
 *
 * This abstraction allows the data layer to work with different email backends
 * without being concerned with the specific API details of each provider.
 *
 * Note: Authentication token management is the responsibility of each implementation.
 * Implementations should handle obtaining and refreshing tokens internally.
 */
interface MailApiService {

    /**
     * Fetches mail folders (or labels in Gmail) for the authenticated user.
     *
     * @param activity The optional Activity context, which might be needed for auth flows by some providers.
     * @param accountId The ID of the account for which to fetch folders.
     * @return Result containing the list of mail folders or an error
     */
    suspend fun getMailFolders(
        activity: android.app.Activity?,
        accountId: String
    ): Result<List<MailFolder>>

    /**
     * Fetches messages for a specific folder ID.
     *
     * @param folderId The ID of the folder to fetch messages from
     * @param activity The optional Activity context, which might be needed for auth flows by some providers.
     * @param maxResults Maximum number of messages to return (pagination limit)
     * @param pageToken Token for next page
     * @return Result containing the list of messages or an error
     */
    suspend fun getMessagesForFolder(
        folderId: String,
        activity: android.app.Activity? = null,
        maxResults: Int? = null,
        pageToken: String? = null
    ): Result<PagedMessagesResponse>

    /**
     * Fetches messages for a specific thread/conversation.
     *
     * @param threadId The ID of the thread (for Gmail) or conversation (for Outlook).
     * @param folderId The ID of the folder in which the thread is primarily located or being viewed.
     *                 This can help scope the search for providers like MS Graph.
     * @param selectFields Optional list of fields to include in the response
     * @param maxResults Maximum number of messages to return (pagination limit)
     * @return Result containing the list of [Message] objects in the thread/conversation or an error.
     */
    suspend fun getMessagesForThread(
        threadId: String,
        folderId: String,
        selectFields: List<String> = emptyList(),
        maxResults: Int = 100
    ): Result<List<Message>>

    /**
     * Fetches message details for a specific message.
     *
     * @param messageId The ID of the message to fetch details for
     * @return Flow containing the message details or null if the message is not found
     */
    suspend fun getMessageDetails(messageId: String): Flow<Message?>

    /**
     * Marks a message as read or unread.
     *
     * @param messageId The ID of the message to update
     * @param isRead Whether the message should be marked as read (true) or unread (false)
     * @return Result indicating success or failure
     */
    suspend fun markMessageRead(
        messageId: String,
        isRead: Boolean
    ): Result<Unit>

    /**
     * Stars or unstars a message.
     *
     * @param messageId The ID of the message to update
     * @param isStarred Whether the message should be starred (true) or unstarred (false)
     * @return Result indicating success or failure
     */
    suspend fun starMessage(
        messageId: String,
        isStarred: Boolean
    ): Result<Unit>

    /**
     * Deletes a message (moves it to trash/deleted items folder).
     *
     * @param messageId The ID of the message to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteMessage(
        messageId: String
    ): Result<Unit>

    /**
     * Moves a message to a different folder.
     *
     * @param messageId The ID of the message to move
     * @param currentFolderId The ID of the current folder
     * @param destinationFolderId The ID of the destination folder
     * @return Result indicating success or failure
     */
    suspend fun moveMessage(
        messageId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit>

    /**
     * Marks all messages in a thread as read or unread.
     *
     * @param threadId The ID of the thread to mark messages in
     * @param isRead Whether the messages should be marked as read (true) or unread (false)
     * @return Result indicating success or failure
     */
    suspend fun markThreadRead(
        threadId: String,
        isRead: Boolean
    ): Result<Unit>

    /**
     * Deletes all messages in a thread.
     *
     * @param threadId The ID of the thread to delete messages from
     * @return Result indicating success or failure
     */
    suspend fun deleteThread(
        threadId: String
    ): Result<Unit>

    /**
     * Moves all messages in a thread to a different folder.
     *
     * @param threadId The ID of the thread to move messages from
     * @param currentFolderId The ID of the current folder
     * @param destinationFolderId The ID of the destination folder
     * @return Result indicating success or failure
     */
    suspend fun moveThread(
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit>

    /**
     * Fetches the full content (body) of a specific message.
     *
     * @param messageId The ID of the message to fetch content for.
     * @return Result containing the Message object with its body populated, or an error.
     *         The Message object might only contain id, body, and contentType, or be more complete
     *         depending on the API and what was requested/returned.
     */
    suspend fun getMessageContent(messageId: String): Result<Message>

    /**
     * Fetches attachments for a specific message.
     *
     * @param messageId The ID of the message to fetch attachments for
     * @return Result containing the list of attachments or an error
     */
    suspend fun getMessageAttachments(messageId: String): Result<List<net.melisma.core_data.model.Attachment>>

    /**
     * Downloads the binary data for a specific attachment.
     *
     * @param messageId The ID of the message containing the attachment
     * @param attachmentId The ID of the attachment to download
     * @return Result containing the attachment binary data or an error
     */
    suspend fun downloadAttachment(messageId: String, attachmentId: String): Result<ByteArray>

    /**
     * Creates a new draft message.
     *
     * @param draft The draft message details to create
     * @return Result containing the created message with server-assigned ID or an error
     */
    suspend fun createDraftMessage(draft: net.melisma.core_data.model.MessageDraft): Result<Message>

    /**
     * Updates an existing draft message.
     *
     * @param messageId The ID of the draft message to update
     * @param draft The updated draft message details
     * @return Result containing the updated message or an error
     */
    suspend fun updateDraftMessage(
        messageId: String,
        draft: net.melisma.core_data.model.MessageDraft
    ): Result<Message>

    /**
     * Sends a draft message.
     *
     * @param draft The draft message to send
     * @return Result containing the sent message ID or an error
     */
    suspend fun sendMessage(draft: net.melisma.core_data.model.MessageDraft): Result<String>

    /**
     * Searches messages based on a query string.
     *
     * @param query The search query
     * @param folderId Optional folder ID to limit search scope
     * @param maxResults Maximum number of results to return
     * @return Result containing the list of matching messages or an error
     */
    suspend fun searchMessages(
        query: String,
        folderId: String? = null,
        maxResults: Int = 50
    ): Result<List<Message>>

    /**
     * Performs a delta synchronization of mail folders for the given account.
     *
     * @param accountId The ID of the account for which to sync folders.
     * @param syncToken The token from the previous sync operation. Null for an initial full sync.
     * @return Result containing a DeltaSyncResult with new/updated folders, deleted folder IDs, and the next sync token.
     */
    suspend fun syncFolders(
        accountId: String,
        syncToken: String?
    ): Result<DeltaSyncResult<MailFolder>>

    /**
     * Performs a delta synchronization of messages for a specific folder.
     *
     * @param folderId The remote ID of the folder to sync messages for.
     * @param syncToken The token from the previous sync operation. Null for an initial full sync.
     * @param maxResults Optional maximum number of messages to return per page during the sync.
     * @return Result containing a DeltaSyncResult with new/updated messages, deleted message IDs, and the next sync token.
     */
    suspend fun syncMessagesForFolder(
        folderId: String,
        syncToken: String?,
        maxResults: Int? = null
    ): Result<DeltaSyncResult<Message>>

    // Future methods to consider:
    // - syncFolders(syncToken) - For delta syncing with sync token
}