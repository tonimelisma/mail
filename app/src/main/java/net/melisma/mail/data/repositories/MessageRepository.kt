package net.melisma.mail.data.repositories

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import net.melisma.mail.Account
import net.melisma.mail.MailFolder
import net.melisma.mail.model.MessageDataState

/**
 * Interface defining the contract for managing mail messages within folders.
 */
interface MessageRepository {

    /**
     * A Flow emitting the current fetch state ([MessageDataState]) for messages
     * in the currently targeted folder/account.
     */
    val messageDataState: StateFlow<MessageDataState>

    /**
     * Sets the target account and folder for which messages should be fetched and observed.
     * Calling this will trigger an initial fetch if messages aren't already loaded/loading.
     * Pass nulls to clear the target (e.g., when no folder is selected).
     *
     * @param account The target [Account] or null.
     * @param folder The target [MailFolder] or null.
     */
    suspend fun setTargetFolder(account: Account?, folder: MailFolder?)

    /**
     * Triggers a refresh of messages for the currently targeted folder/account.
     * The results will be emitted through the [messageDataState] flow.
     *
     * @param activity The optional Activity context, required for interactive token flows if needed.
     */
    suspend fun refreshMessages(activity: Activity? = null)

    // Future methods could include:
    // suspend fun loadMoreMessages()
    // suspend fun searchMessages(query: String): Flow<List<Message>>
    // suspend fun updateMessageReadStatus(messageId: String, isRead: Boolean)
    // suspend fun deleteMessage(messageId: String)
    // suspend fun moveMessage(messageId: String, destinationFolderId: String)
}
