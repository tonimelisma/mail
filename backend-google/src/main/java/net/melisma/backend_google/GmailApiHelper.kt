package net.melisma.backend_google

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import net.melisma.backend_google.di.GoogleHttpClient
import net.melisma.backend_google.model.GmailLabel
import net.melisma.backend_google.model.GmailLabelList
import net.melisma.backend_google.model.GmailMessage
import net.melisma.backend_google.model.GmailMessageIdentifier
import net.melisma.backend_google.model.GmailMessageList
import net.melisma.backend_google.model.MessagePartHeader
import net.melisma.core_common.errors.ErrorMapperService
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for interacting with Gmail API.
 */
@Singleton
class GmailApiHelper @Inject constructor(
    @GoogleHttpClient private val httpClient: HttpClient,
    private val errorMapper: ErrorMapperService
) {
    private val TAG = "GmailApiHelper"
    private val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

    /**
     * Fetches all available labels from Gmail API and maps them to MailFolder objects.
     *
     * @param accessToken OAuth access token for Gmail API
     * @return Result containing a list of MailFolder objects or an exception
     */
    suspend fun getLabels(accessToken: String): Result<List<MailFolder>> {
        return try {
            val response = httpClient.get("$BASE_URL/labels") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            }

            val labelList = response.body<GmailLabelList>()
            val mailFolders = labelList.labels
                .filter { isVisibleLabel(it) }
                .map { mapLabelToMailFolder(it) }

            Log.d(TAG, "Successfully fetched ${mailFolders.size} labels")
            Result.success(mailFolders)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Gmail labels", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches messages for a specific label from Gmail API.
     *
     * @param accessToken OAuth access token for Gmail API
     * @param labelIds List of label IDs to filter messages by
     * @param maxResults Maximum number of messages to return
     * @return Result containing a list of Message objects or an exception
     */
    suspend fun getMessagesForLabel(
        accessToken: String,
        labelIds: List<String>,
        maxResults: Int = 20
    ): Result<List<Message>> {
        return try {
            // 1. Get message IDs for the specified label
            val messageIdsResponse = httpClient.get("$BASE_URL/messages") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                }
                parameter("maxResults", maxResults)
                // Add all label IDs as separate parameters
                labelIds.forEach {
                    parameter("labelIds", it)
                }
            }

            val messageList = messageIdsResponse.body<GmailMessageList>()

            if (messageList.messages.isEmpty()) {
                Log.d(TAG, "No messages found for labels: $labelIds")
                return Result.success(emptyList())
            }

            // 2. Fetch message details in parallel
            val messages = supervisorScope {
                messageList.messages.map { messageIdentifier ->
                    async {
                        fetchMessageDetails(accessToken, messageIdentifier.id)
                    }
                }.awaitAll().filterNotNull()
            }

            Log.d(TAG, "Successfully fetched ${messages.size} messages for labels: $labelIds")
            Result.success(messages)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Gmail messages", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches details for a specific message ID.
     *
     * @param accessToken OAuth access token for Gmail API
     * @param messageId Message ID to fetch
     * @return Mapped Message object or null if an error occurred
     */
    private suspend fun fetchMessageDetails(accessToken: String, messageId: String): Message? {
        return try {
            val response = httpClient.get("$BASE_URL/messages/$messageId") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                }
                // Request only metadata to optimize payload size
                parameter("format", "metadata")
                // Request specific headers to reduce payload size
                parameter("metadataHeaders", "Subject")
                parameter("metadataHeaders", "From")
                parameter("metadataHeaders", "Date")
            }

            val gmailMessage = response.body<GmailMessage>()
            mapGmailMessageToMessage(gmailMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching message details for ID: $messageId", e)
            null
        }
    }

    /**
     * Checks if a label should be shown in the UI.
     */
    private fun isVisibleLabel(label: GmailLabel): Boolean {
        // Skip labels that are explicitly hidden
        if (label.labelListVisibility == "labelHide" ||
            label.messageListVisibility == "hide"
        ) {
            return false
        }

        // Skip certain system labels that aren't useful in the UI
        val skipLabels = listOf("CHAT")
        if (label.type == "system" && label.name in skipLabels) {
            return false
        }

        return true
    }

    /**
     * Maps a Gmail Label to the app's MailFolder model.
     */
    private fun mapLabelToMailFolder(label: GmailLabel): MailFolder {
        // Map some common system labels to standardized names
        val displayName = when (label.name) {
            "INBOX" -> "Inbox"
            "SENT" -> "Sent"
            "DRAFT" -> "Drafts"
            "TRASH" -> "Trash"
            "SPAM" -> "Spam"
            "STARRED" -> "Starred"
            "IMPORTANT" -> "Important"
            else -> label.name
        }

        return MailFolder(
            id = label.id,
            displayName = displayName,
            totalItemCount = label.messagesTotal ?: 0,
            unreadItemCount = label.messagesUnread ?: 0
        )
    }

    /**
     * Maps a Gmail Message to the app's Message model.
     */
    private fun mapGmailMessageToMessage(gmailMessage: GmailMessage): Message {
        // Extract headers
        val headers = gmailMessage.payload?.headers ?: emptyList()
        val subject = headers.findHeaderValue("Subject") ?: "(No Subject)"
        val from = headers.findHeaderValue("From") ?: ""
        val date = headers.findHeaderValue("Date")?.let { parseEmailDate(it) } ?: Date()

        // Extract sender name and address
        val senderName = extractSenderName(from)
        val senderAddress = extractSenderAddress(from)

        // Format date for the message model
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val formattedDate = dateFormat.format(date)

        return Message(
            id = gmailMessage.id,
            subject = subject,
            receivedDateTime = formattedDate,
            senderName = senderName,
            senderAddress = senderAddress,
            bodyPreview = gmailMessage.snippet ?: "",
            isRead = !gmailMessage.labelIds.contains("UNREAD")
        )
    }

    /**
     * Extracts the sender's email address from a "From" header.
     */
    private fun extractSenderAddress(from: String): String {
        // Format can be: "Name <email@example.com>" or just "email@example.com"
        return if (from.contains("<") && from.contains(">")) {
            from.substringAfter("<").substringBefore(">").trim()
        } else {
            from.trim()
        }
    }

    /**
     * Extracts the sender's name from a "From" header.
     */
    private fun extractSenderName(from: String): String {
        // Format can be: "Name <email@example.com>" or just "email@example.com"
        return if (from.contains("<") && from.contains(">")) {
            from.substringBefore("<").trim().removeSurrounding("\"")
        } else {
            from.trim()
        }
    }

    // (removed hasAttachments function since it's not needed for current Message model)

    /**
     * Helper extension to find a header value by name.
     */
    private fun List<MessagePartHeader>.findHeaderValue(name: String): String? {
        return this.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }

    /**
     * Parses a date string from an email header.
     */
    private fun parseEmailDate(dateStr: String): Date? {
        // Email date formats can be complex, try multiple patterns
        val formatters = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US)
        )

        for (formatter in formatters) {
            try {
                return formatter.parse(dateStr)
            } catch (e: Exception) {
                // Try next formatter
            }
        }

        // If all formatters fail, return current date
        Log.w(TAG, "Could not parse date: $dateStr")
        return Date()
    }

    /**
     * Decodes base64 encoded message body data.
     */
    private fun decodeMessageBody(data: String?): String {
        if (data.isNullOrEmpty()) return ""

        return try {
            val decodedBytes = Base64.decode(
                // URL-safe Base64 might have replaced some characters
                data.replace('-', '+').replace('_', '/'),
                Base64.DEFAULT
            )
            String(decodedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding message body", e)
            ""
        }
    }
}