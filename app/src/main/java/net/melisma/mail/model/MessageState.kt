package net.melisma.mail.model

import net.melisma.mail.Message // Assuming Message data class is in net.melisma.mail

/**
 * Represents the state of fetching messages for a specific folder.
 */
sealed class MessageDataState {
    /** No folder selected or initial state before loading. */
    data object Initial : MessageDataState()

    /** Messages are currently being loaded. */
    data object Loading : MessageDataState()

    /** Messages were successfully loaded. */
    data class Success(val messages: List<Message>) : MessageDataState()

    /** An error occurred while fetching messages. */
    data class Error(val error: String) : MessageDataState()
}