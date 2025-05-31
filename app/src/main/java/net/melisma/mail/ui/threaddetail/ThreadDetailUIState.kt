package net.melisma.mail.ui.threaddetail

sealed interface ThreadDetailUIState {
    object Loading : ThreadDetailUIState
    data class Success(
        val threadMessages: List<ThreadMessageItem>, // Changed from List<Message>
        val threadSubject: String?,
        val accountId: String, // Added to pass to MessageDetailScreen
        val threadId: String // Added for context if needed
    ) : ThreadDetailUIState

    data class Error(val errorMessage: String) : ThreadDetailUIState
} 