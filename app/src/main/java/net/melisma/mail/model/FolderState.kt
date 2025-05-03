package net.melisma.mail.model

// Import the MailFolder data class (assuming it's in net.melisma.mail package)
// If MailFolder is defined elsewhere (e.g., GraphApiHelper.kt), ensure the import path is correct.
// *** ENSURE THIS IMPORT IS CORRECT FOR YOUR PROJECT STRUCTURE ***
import net.melisma.mail.MailFolder

/**
 * Represents the possible states for fetching folders for a specific account.
 * This sealed class helps manage UI updates based on the fetching status.
 */
sealed class FolderFetchState {
    /** Indicates that folders are currently being loaded from the source. */
    data object Loading : FolderFetchState()

    /** Indicates that folders were successfully loaded. Contains the list of folders. */
    data class Success(val folders: List<MailFolder>) : FolderFetchState()

    /** Indicates that an error occurred while fetching folders. Contains the error message. */
    data class Error(val error: String) : FolderFetchState()
}
