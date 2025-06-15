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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import net.melisma.core_data.model.WellKnownFolderType
import timber.log.Timber
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

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

/**
 * Generates a stable, visually pleasing color from a list based on the account ID's hash code.
 * This ensures that each account is always represented by the same color in the UI.
 *
 * @param accountId The unique ID of the account.
 * @return A [Color] to be used for UI elements related to this account.
 */
fun getAccountColor(accountId: String): Color {
    val colors = listOf(
        Color(0xFFF44336), Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF00BCD4),
        Color(0xFF009688), Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFCDDC39),
        Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFF795548)
    )
    // Use the absolute value of the hash code to prevent negative indices
    val index = abs(accountId.hashCode()) % colors.size
    return colors[index]
}