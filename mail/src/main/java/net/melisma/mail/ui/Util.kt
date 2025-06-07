// Corrected: app/src/main/java/net/melisma/mail/ui/Util.kt
// Implementing Alternative 2: getIconForFolder is now Non-Composable

package net.melisma.mail.ui

// import androidx.compose.runtime.Composable // <-- REMOVED this import
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelImportant
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import net.melisma.core_data.model.WellKnownFolderType
import timber.log.Timber
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Helper to get appropriate icon for common folders using updated names.
 * This is now a regular function, not a @Composable function.
 */
// @Composable // <-- REMOVED this annotation
fun getIconForFolder(folderType: WellKnownFolderType): ImageVector {
    return when (folderType) {
        WellKnownFolderType.INBOX -> Icons.Filled.Inbox
        WellKnownFolderType.SENT_ITEMS -> Icons.AutoMirrored.Filled.Send
        WellKnownFolderType.DRAFTS -> Icons.Filled.Drafts
        WellKnownFolderType.TRASH -> Icons.Filled.Delete
        WellKnownFolderType.SPAM -> Icons.Filled.Report
        WellKnownFolderType.ARCHIVE -> Icons.Filled.Archive
        WellKnownFolderType.STARRED -> Icons.Filled.Star
        WellKnownFolderType.IMPORTANT -> Icons.AutoMirrored.Filled.LabelImportant
        WellKnownFolderType.USER_CREATED -> Icons.Filled.Folder
        WellKnownFolderType.OTHER -> Icons.Filled.FolderOpen
        WellKnownFolderType.HIDDEN -> Icons.Filled.VisibilityOff
    }
}

/**
 * Basic date formatting helper.
 * Takes an ISO 8601 date-time string and returns a user-friendly relative date string.
 */
fun formatMessageDate(dateTimeString: String?): String {
    // Return empty string for null or blank input
    if (dateTimeString.isNullOrBlank()) return ""
    return try {
        // Parse the ISO 8601 string to an OffsetDateTime
        val offsetDateTime = OffsetDateTime.parse(dateTimeString)
        // Get the current time in the same timezone offset as the message
        val now = OffsetDateTime.now(offsetDateTime.offset)

        // Compare dates and format accordingly
        return when {
            // If the date is today, show only the time (e.g., "10:30 AM")
            offsetDateTime.toLocalDate() == now.toLocalDate() -> {
                offsetDateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            }
            // If the date was yesterday, show "Yesterday"
            offsetDateTime.toLocalDate() == now.toLocalDate().minusDays(1) -> {
                "Yesterday"
            }
            // If the date is within the current year, show Month and Day (e.g., "Jan 15")
            offsetDateTime.year == now.year -> {
                // Using "MMM d" pattern for consistency (e.g., Jan 15, Dec 3)
                offsetDateTime.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
            }
            // Otherwise (older dates), show the short localized date (e.g., "1/15/23")
            else -> {
                offsetDateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
            }
        }
    } catch (e: Exception) {
        // Log parsing errors and return the original string as a fallback
        Timber.w(e, "Error parsing date: $dateTimeString")
        dateTimeString // Return original string if parsing fails
    }
}