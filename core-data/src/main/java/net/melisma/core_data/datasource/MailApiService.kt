package net.melisma.core_data.datasource

import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message

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
     * @return Result containing the list of mail folders or an error
     */
    suspend fun getMailFolders(): Result<List<MailFolder>>

    /**
     * Fetches messages for a specific folder ID.
     *
     * @param folderId The ID of the folder to fetch messages from
     * @param selectFields Optional list of fields to include in the response
     * @param maxResults Maximum number of messages to return (pagination limit)
     * @return Result containing the list of messages or an error
     */
    suspend fun getMessagesForFolder(
        folderId: String,
        selectFields: List<String> = emptyList(),
        maxResults: Int = 25
    ): Result<List<Message>>

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
    ): Result<Boolean>

    /**
     * Deletes a message (moves it to trash/deleted items folder).
     *
     * @param messageId The ID of the message to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteMessage(
        messageId: String
    ): Result<Boolean>

    /**
     * Moves a message to a different folder.
     *
     * @param messageId The ID of the message to move
     * @param targetFolderId The ID of the destination folder
     * @return Result indicating success or failure
     */
    suspend fun moveMessage(
        messageId: String,
        targetFolderId: String
    ): Result<Boolean>

    // Future methods to consider:
    // - getMessageContent(messageId) - For fetching full message content
    // - sendMessage(message) - For sending new messages
    // - getAttachments(messageId) - For fetching attachments
    // - createDraft(message) - For creating draft messages
    // - syncFolders(syncToken) - For delta syncing with sync token
}