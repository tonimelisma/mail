package net.melisma.core_data.repository

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import net.melisma.core_data.model.Account // Updated import
import net.melisma.core_data.model.MailFolder // Updated import
import net.melisma.core_data.model.MessageDataState // Updated import

/**
 * Interface defining the contract for managing mail messages within specific folders.
 * Implementations handle fetching message lists, potentially supporting pagination, search,
 * and actions like marking read/unread or deleting messages.
 */
interface MessageRepository {

    /**
     * A [StateFlow] emitting the current fetch state ([MessageDataState]) for messages
     * in the folder/account currently targeted by [setTargetFolder].
     * UI layers collect this to display the message list or relevant status indicators.
     */
    val messageDataState: StateFlow<MessageDataState>

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
     * Triggers a background refresh of the message list for the currently targeted folder/account.
     * Updates (loading, success, or error) will be emitted through the [messageDataState] flow.
     *
     * @param activity The optional [Activity] context, potentially required
     * for interactive authentication if tokens need to be refreshed.
     */
    suspend fun refreshMessages(activity: Activity? = null)

    // Potential future methods for message management:
    // suspend fun loadMoreMessages() // For pagination
    // suspend fun searchMessages(query: String): Flow<List<Message>> // For search functionality
    // suspend fun updateMessageReadStatus(messageId: String, isRead: Boolean): Result<Unit>
    // suspend fun deleteMessage(messageId: String): Result<Unit>
    // suspend fun moveMessage(messageId: String, destinationFolderId: String): Result<Unit>
}
