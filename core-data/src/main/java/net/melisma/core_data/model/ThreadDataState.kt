package net.melisma.core_data.model

/**
 * Represents the various states for fetching and displaying a list of [MailThread]s.
 */
sealed interface ThreadDataState {
    /** The initial state before any thread data loading has begun. */
    object Initial : ThreadDataState

    /** Indicates that threads are currently being loaded. */
    object Loading : ThreadDataState

    /** Indicates that threads were successfully loaded. */
    data class Success(val threads: List<MailThread>) : ThreadDataState

    /** Indicates that an error occurred while loading threads. */
    data class Error(val error: String?) : ThreadDataState
} 