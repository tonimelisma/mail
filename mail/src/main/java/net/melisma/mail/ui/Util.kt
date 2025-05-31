// Corrected: app/src/main/java/net/melisma/mail/ui/Util.kt
// Implementing Alternative 2: getIconForFolder is now Non-Composable

package net.melisma.mail.ui

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Report
// import androidx.compose.runtime.Composable // <-- REMOVED this import
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Helper to get appropriate icon for common folders using updated names.
 * This is now a regular function, not a @Composable function.
 */
// @Composable // <-- REMOVED this annotation
fun getIconForFolder(folderName: String): ImageVector { // Now a regular function
    return when (folderName.lowercase(Locale.ROOT)) {
        "inbox" -> Icons.Filled.Inbox
        "sent items" -> Icons.AutoMirrored.Filled.Send // Use AutoMirrored version
        "drafts" -> Icons.Filled.Drafts
        "deleted items", "trash" -> Icons.Filled.Delete
        "junk email", "spam" -> Icons.Filled.Report
        "archive", "all mail" -> Icons.Filled.Archive
        else -> Icons.Filled.Folder
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
        Log.w("FormatDate", "Error parsing date: $dateTimeString", e)
        dateTimeString // Return original string if parsing fails
    }
}