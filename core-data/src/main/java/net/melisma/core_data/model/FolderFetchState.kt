package net.melisma.core_data.model

/**
 * Represents the possible states for fetching mail folders for a specific account.
 * This sealed class helps manage UI updates based on the folder fetching status,
 * allowing for clear display of loading, success (with data), or error states.
 */
sealed class FolderFetchState {
    /** Indicates that folders are currently being loaded from the backend source for an account. */
    data object Loading : FolderFetchState()

    /**
     * Indicates that folders were successfully loaded for an account.
     * @param folders The list of [MailFolder] objects retrieved.
     */
    data class Success(val folders: List<MailFolder>) : FolderFetchState()

    /**
     * Indicates that an error occurred while fetching folders for an account.
     * @param error A user-friendly error message describing the failure.
     */
    data class Error(val error: String) : FolderFetchState()
}
