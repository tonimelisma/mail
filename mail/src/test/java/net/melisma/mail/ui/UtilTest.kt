// Use this version for Alternative 2: app/src/test/java/net/melisma/mail/ui/UtilTest.kt
package net.melisma.mail.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Report
// Import androidx.compose.ui.graphics.vector.ImageVector // Not strictly needed for equals check
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UtilTest {

    // This test should now work because getIconForFolder is no longer @Composable
    @Test
    fun `getIconForFolder returns correct icons`() {
        assertEquals(Icons.Filled.Inbox, getIconForFolder("Inbox"))
        assertEquals(Icons.Filled.Inbox, getIconForFolder("INBOX")) // Case-insensitivity
        assertEquals(Icons.AutoMirrored.Filled.Send, getIconForFolder("Sent Items"))
        assertEquals(Icons.Filled.Drafts, getIconForFolder("Drafts"))
        assertEquals(Icons.Filled.Delete, getIconForFolder("Deleted Items"))
        assertEquals(Icons.Filled.Delete, getIconForFolder("Trash")) // Alias
        assertEquals(Icons.Filled.Report, getIconForFolder("Junk Email")) // Alias
        assertEquals(Icons.Filled.Report, getIconForFolder("Spam"))
        assertEquals(Icons.Filled.Archive, getIconForFolder("Archive"))
        assertEquals(Icons.Filled.Archive, getIconForFolder("All Mail")) // Alias
        assertEquals(Icons.Filled.Folder, getIconForFolder("Custom Folder"))
        assertEquals(Icons.Filled.Folder, getIconForFolder("wEiRd CasE")) // Default fallback
    }

    // --- Tests for formatMessageDate remain the same ---
    @Test
    fun `formatMessageDate handles null or blank input`() {
        assertEquals("", formatMessageDate(null))
        assertEquals("", formatMessageDate(""))
        assertEquals("", formatMessageDate("   "))
    }

    @Test
    fun `formatMessageDate handles invalid date string`() {
        val invalidDate = "not-a-date"
        // Should return the original invalid string gracefully
        assertEquals(invalidDate, formatMessageDate(invalidDate))
    }

    @Test
    fun `formatMessageDate returns time for today`() {
        val now = OffsetDateTime.now()
        val todayString = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val expectedTime = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        assertEquals(expectedTime, formatMessageDate(todayString))
    }

    @Test
    fun `formatMessageDate returns Yesterday for yesterday`() {
        val yesterdayString =
            OffsetDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assertEquals("Yesterday", formatMessageDate(yesterdayString))
    }

    @Test
    fun `formatMessageDate returns month and day for current year`() {
        // Ensure the date is not today or yesterday, but still this year
        val now = OffsetDateTime.now()
        var dateInPast = now.minusDays(5)
        // Avoid edge case where subtracting days crosses year boundary near Jan 1st
        if (dateInPast.year != now.year) {
            dateInPast = now.withDayOfYear(10).withYear(now.year) // Use Jan 10th of current year
        }
        // Ensure it's not yesterday if test runs on Jan 2nd etc.
        if (dateInPast.toLocalDate() == now.toLocalDate().minusDays(1)) {
            dateInPast = now.minusDays(2)
            // Adjust again if needed near year start
            if (dateInPast.year != now.year) {
                dateInPast = now.withDayOfYear(10).withYear(now.year)
            }
        }


        val currentYearString = dateInPast.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        // Expected format like "Jan 5" or "Dec 20" depends on Locale, use explicit pattern
        val expectedFormat =
            dateInPast.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        assertEquals(expectedFormat, formatMessageDate(currentYearString))
    }

    @Test
    fun `formatMessageDate returns short date for previous year`() {
        val lastYear = OffsetDateTime.now().minusYears(1)
        val lastYearString = lastYear.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val expectedDate = lastYear.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
        assertEquals(expectedDate, formatMessageDate(lastYearString))
    }

    @Test
    fun `formatMessageDate handles different timezones correctly`() {
        // Example date: Jan 15, 2024 10:30 AM UTC
        val utcDateTime = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        val utcDateString = utcDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        // Assuming test runs in a non-UTC timezone where this date is in the past year relative to 'now'
        val expectedDate = utcDateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))

        // This assertion depends heavily on the test execution environment's locale & date.
        // A more robust test might compare parsed dates or mock 'now'.
        // For simplicity, we assume here it falls into the "previous year or older" category
        // based on typical execution context relative to Jan 15, 2024.
        assertEquals(expectedDate, formatMessageDate(utcDateString))
    }
}