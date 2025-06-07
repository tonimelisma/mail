package net.melisma.mail.ui.messagedetail

import net.melisma.core_data.model.Message

/**
 * Represents the possible states for the Message Detail screen.
 */
sealed interface MessageDetailUIState {
    /**
     * The message details are currently being loaded.
     */
    object Loading : MessageDetailUIState

    /**
     * An error occurred while trying to load the message details.
     * @param errorMessage A descriptive error message.
     */
    data class Error(val errorMessage: String) : MessageDetailUIState

    /**
     * The message details were successfully loaded.
     * @param message The loaded [Message] object containing all details.
     */
    data class Success(val message: Message) : MessageDetailUIState
} 