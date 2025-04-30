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
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Helper to get appropriate icon for common folders using updated names
@Composable
fun getIconForFolder(folderName: String): ImageVector {
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

// Basic date formatting helper
fun formatMessageDate(dateTimeString: String?): String {
    if (dateTimeString.isNullOrBlank()) return ""
    return try {
        val offsetDateTime = OffsetDateTime.parse(dateTimeString)
        val now = OffsetDateTime.now(offsetDateTime.offset)

        return when {
            offsetDateTime.toLocalDate() == now.toLocalDate() -> {
                offsetDateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            }
            offsetDateTime.toLocalDate() == now.toLocalDate().minusDays(1) -> {
                "Yesterday"
            }
            offsetDateTime.year == now.year -> {
                offsetDateTime.format(DateTimeFormatter.ofPattern("MMM d"))
            }
            else -> {
                offsetDateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
            }
        }
    } catch (e: Exception) {
        Log.w("FormatDate", "Error parsing date: $dateTimeString", e)
        dateTimeString
    }
}