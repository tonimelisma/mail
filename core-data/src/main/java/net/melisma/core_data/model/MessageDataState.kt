package net.melisma.core_data.model

/**
 * Represents the state of fetching messages for a specific, currently selected folder.
 * This helps manage the UI for the message list view, indicating whether data is being loaded,
 * if an error occurred, if the fetch was successful (providing the messages), or if no folder
 * is selected yet (initial state).
 */
sealed class MessageDataState {
    /** Represents the state where no folder is selected or before any loading has begun. */
    data object Initial : MessageDataState()

    /** Indicates that messages for the selected folder are currently being loaded. */
    data object Loading : MessageDataState()

    /**
     * Indicates that messages were successfully loaded for the selected folder.
     * @param messages The list of [Message] objects retrieved.
     */
    data class Success(val messages: List<Message>) : MessageDataState()

    /**
     * Indicates that an error occurred while fetching messages for the selected folder.
     * @param error A user-friendly error message describing the failure.
     */
    data class Error(val error: String) : MessageDataState()
}
