package net.melisma.mail.ui

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Helper to get appropriate icon for common folders using updated names
@Composable
fun getIconForFolder(folderName: String): ImageVector {
    // Use lowercase comparison for robustness
    return when (folderName.lowercase(Locale.ROOT)) {
        "inbox" -> Icons.Filled.Inbox // Use standard Inbox icon
        "drafts" -> Icons.Filled.Drafts
        "sent items" -> Icons.Filled.Send
        "spam", "junk email" -> Icons.Filled.Report // Match "Spam" or "Junk Email"
        "trash", "deleted items" -> Icons.Filled.Delete // Match "Trash" or "Deleted Items"
        "archive", "all mail" -> Icons.Filled.Archive // Match "Archive" or "All Mail"
        else -> Icons.Filled.Folder // Default folder icon
    }
}

// Basic date formatting helper
fun formatMessageDate(dateTimeString: String?): String {
    if (dateTimeString.isNullOrBlank()) return ""
    return try {
        val offsetDateTime = OffsetDateTime.parse(dateTimeString)
        val now = OffsetDateTime.now(offsetDateTime.offset) // Compare in same timezone

        return when {
            offsetDateTime.toLocalDate() == now.toLocalDate() -> {
                // Today: Show time
                offsetDateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            }

            offsetDateTime.toLocalDate() == now.toLocalDate().minusDays(1) -> {
                // Yesterday
                "Yesterday"
            }

            offsetDateTime.year == now.year -> {
                // This year: Show Month Day (e.g., Apr 29)
                offsetDateTime.format(DateTimeFormatter.ofPattern("MMM d"))
            }

            else -> {
                // Older: Show short date (e.g., 4/29/2024)
                offsetDateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
            }
        }
    } catch (e: Exception) {
        Log.w("FormatDate", "Error parsing date: $dateTimeString", e)
        dateTimeString // Fallback to original string if parsing fails
    }
}