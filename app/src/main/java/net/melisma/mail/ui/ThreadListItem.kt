package net.melisma.mail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.melisma.core_data.model.MailThread
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun ThreadListItem(mailThread: MailThread, onClick: () -> Unit) {
    val lastMessageDateFormatted = mailThread.lastMessageDateTime?.let { date ->
        // Using the existing formatMessageDate logic if it's adaptable,
        // or a simplified version for thread list.
        // For simplicity, let's use a basic formatter here.
        // Ensure your formatMessageDate in Util.kt can handle java.util.Date or adapt.
        // This is a placeholder, ideally reuse or adapt Util.formatMessageDate
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault() // Consider UTC or local as appropriate
        sdf.format(date)
    } ?: "Unknown Date"

    val fontWeight = if (mailThread.unreadMessageCount > 0) FontWeight.Bold else FontWeight.Normal

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mailThread.participantsSummary ?: "Unknown Participants",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = fontWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false) // Prevent taking too much space
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = lastMessageDateFormatted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = fontWeight,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = mailThread.subject ?: "(No Subject)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = mailThread.snippet ?: "",
            style = MaterialTheme.typography.bodySmall, // Slightly smaller for snippet
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (mailThread.totalMessageCount > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "(${mailThread.totalMessageCount} messages${if (mailThread.unreadMessageCount > 0) ", ${mailThread.unreadMessageCount} unread" else ""})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
} 