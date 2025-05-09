package net.melisma.backend_google

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.melisma.backend_google.di.GoogleHttpClient
import net.melisma.backend_google.model.GmailLabel
import net.melisma.backend_google.model.GmailLabelList
import net.melisma.backend_google.model.GmailMessage
import net.melisma.backend_google.model.GmailMessageList
import net.melisma.backend_google.model.MessagePartHeader
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for interacting with Gmail API.
 * Implements the MailApiService interface to provide a standardized API for mail operations.
 */
@Singleton
class GmailApiHelper @Inject constructor(
    @GoogleHttpClient private val httpClient: HttpClient,
    private val errorMapper: ErrorMapperService
) : MailApiService {
    private val TAG = "GmailApiHelper"
    private val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

    /**
     * Fetches all available labels from Gmail API and maps them to MailFolder objects.
     * Implements the MailApiService.getMailFolders interface method.
     *
     * @param accessToken OAuth access token for Gmail API
     * @return Result containing a list of MailFolder objects or an exception
     */
    override suspend fun getMailFolders(accessToken: String): Result<List<MailFolder>> {
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
     * Fetches messages for a specific folder (label in Gmail) from Gmail API.
     * Implements the MailApiService.getMessagesForFolder interface method.
     *
     * @param accessToken OAuth access token for Gmail API
     * @param folderId The ID of the folder (label) to fetch messages from
     * @param selectFields Optional list of fields to include in the response (not used in Gmail implementation)
     * @param maxResults Maximum number of messages to return
     * @return Result containing a list of Message objects or an exception
     */
    override suspend fun getMessagesForFolder(
        accessToken: String,
        folderId: String,
        selectFields: List<String>,
        maxResults: Int
    ): Result<List<Message>> {
        return try {
            // 1. Get message IDs for the specified label
            val messageIdsResponse = httpClient.get("$BASE_URL/messages") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                }
                parameter("maxResults", maxResults)
                // Add the folder ID (label ID in Gmail) as a parameter
                parameter("labelIds", folderId)
            }

            val messageList = messageIdsResponse.body<GmailMessageList>()

            if (messageList.messages.isEmpty()) {
                Log.d(TAG, "No messages found for folder (label): $folderId")
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

            Log.d(
                TAG,
                "Successfully fetched ${messages.size} messages for folder (label): $folderId"
            )
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
        return !(label.type == "system" && label.name in skipLabels)
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

    /**
     * Marks a message as read or unread.
     *
     * @param accessToken The authentication token to use for this request
     * @param messageId The ID of the message to update
     * @param isRead Whether the message should be marked as read (true) or unread (false)
     * @return Result indicating success or failure
     */
    override suspend fun markMessageRead(
        accessToken: String,
        messageId: String,
        isRead: Boolean
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Marking message $messageId as ${if (isRead) "read" else "unread"}")

            // In Gmail API, we modify the labels - adding or removing UNREAD label
            val endpoint = "$BASE_URL/messages/$messageId/modify"

            val requestBody = buildJsonObject {
                if (isRead) {
                    putJsonObject("removeLabelIds") {
                        put("0", "UNREAD")
                    }
                } else {
                    putJsonObject("addLabelIds") {
                        put("0", "UNREAD")
                    }
                }
            }

            val response: HttpResponse = httpClient.post(endpoint) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody(requestBody.toString())
            }

            if (response.status.isSuccess()) {
                Log.d(
                    TAG,
                    "Successfully marked message $messageId as ${if (isRead) "read" else "unread"}"
                )
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error marking message: ${response.status} - $errorBody")
                Result.failure(IOException("Error marking message: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Exception marking message $messageId as ${if (isRead) "read" else "unread"}",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Deletes a message (moves it to trash/deleted items folder).
     * In Gmail, this moves the message to the trash. The user can recover the message from trash
     * within the configured period (usually 30 days).
     *
     * @param accessToken The authentication token to use for this request
     * @param messageId The ID of the message to delete
     * @return Result indicating success or failure
     */
    override suspend fun deleteMessage(
        accessToken: String,
        messageId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Trashing message $messageId")
            val endpoint = "$BASE_URL/messages/$messageId/trash"

            val response: HttpResponse = httpClient.post(endpoint) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            }

            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully trashed message $messageId")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error trashing message: ${response.status} - $errorBody")
                Result.failure(IOException("Error trashing message: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception trashing message $messageId", e)
            Result.failure(e)
        }
    }

    /**
     * Moves a message to a different folder by modifying its labels.
     * In Gmail, folders are represented as labels. This operation removes the old label
     * and adds the new target label.
     *
     * @param accessToken The authentication token to use for this request
     * @param messageId The ID of the message to move
     * @param targetFolderId The ID of the destination folder (label)
     * @return Result indicating success or failure
     */
    override suspend fun moveMessage(
        accessToken: String,
        messageId: String,
        targetFolderId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Moving message $messageId to folder (label) $targetFolderId")

            // First, we need to get the current labels of the message
            val messageResponse = httpClient.get("$BASE_URL/messages/$messageId") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                }
                parameter("format", "minimal") // Only need minimal info to get labels
            }

            if (!messageResponse.status.isSuccess()) {
                val errorBody = messageResponse.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message for label modification: ${messageResponse.status} - $errorBody"
                )
                return Result.failure(IOException("Error fetching message: ${messageResponse.status}"))
            }

            val message = messageResponse.body<GmailMessage>()

            // Filter system labels we want to keep and user labels except the target one
            message.labelIds.filter {
                it.startsWith("CATEGORY_") || it in setOf("INBOX", "SENT", "IMPORTANT", "STARRED")
            }

            // We'll keep system labels and remove all other user labels
            val removeLabels = message.labelIds.filter {
                !it.startsWith("CATEGORY_") && it !in setOf(
                    "INBOX",
                    "SENT",
                    "IMPORTANT",
                    "STARRED",
                    targetFolderId
                )
            }

            // Create the modify request
            val endpoint = "$BASE_URL/messages/$messageId/modify"

            // Build the JSON with addLabelIds and removeLabelIds
            val requestBody = buildJsonObject {
                if (targetFolderId.isNotEmpty()) {
                    putJsonObject("addLabelIds") {
                        put("0", targetFolderId)
                    }
                }

                if (removeLabels.isNotEmpty()) {
                    putJsonObject("removeLabelIds") {
                        removeLabels.forEachIndexed { index, labelId ->
                            put(index.toString(), labelId)
                        }
                    }
                }
            }

            val response: HttpResponse = httpClient.post(endpoint) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody(requestBody.toString())
            }

            if (response.status.isSuccess()) {
                Log.d(
                    TAG,
                    "Successfully moved message $messageId to folder (label) $targetFolderId"
                )
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error moving message: ${response.status} - $errorBody")
                Result.failure(IOException("Error moving message: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception moving message $messageId to folder $targetFolderId", e)
            Result.failure(e)
        }
    }
}