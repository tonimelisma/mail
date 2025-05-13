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
import net.melisma.core_data.model.WellKnownFolderType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GmailApiHelper @Inject constructor(
    @GoogleHttpClient private val httpClient: HttpClient,
    private val errorMapper: ErrorMapperService // Assuming GoogleErrorMapper is injected via this interface
) : MailApiService {
    private val TAG = "GmailApiHelper"
    private val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

    companion object {
        private const val GMAIL_LABEL_ID_INBOX = "INBOX"
        private const val GMAIL_LABEL_ID_SENT = "SENT"
        private const val GMAIL_LABEL_ID_DRAFT = "DRAFT"
        private const val GMAIL_LABEL_ID_TRASH = "TRASH"
        private const val GMAIL_LABEL_ID_SPAM = "SPAM"
        private const val GMAIL_LABEL_ID_IMPORTANT = "IMPORTANT"
        private const val GMAIL_LABEL_ID_STARRED = "STARRED"
        private const val GMAIL_NAME_ALL_MAIL = "[GMAIL]/ALL MAIL"
        private const val GMAIL_NAME_NOTES = "NOTES"
        private const val GMAIL_NAME_CONVERSATION_HISTORY = "CONVERSATION HISTORY"
        private const val GMAIL_ID_CHAT = "CHAT" // Often a system label for Hangouts/Chat
        private const val GMAIL_NAME_UNREAD = "UNREAD" // If it appears as a standalone label name


        private const val DISPLAY_NAME_INBOX = "Inbox"
        private const val DISPLAY_NAME_SENT_ITEMS = "Sent Items"
        private const val DISPLAY_NAME_DRAFTS = "Drafts"
        private const val DISPLAY_NAME_ARCHIVE = "Archive"
        private const val DISPLAY_NAME_TRASH = "Trash"
        private const val DISPLAY_NAME_SPAM = "Spam"
        private const val DISPLAY_NAME_IMPORTANT = "Important"
        private const val DISPLAY_NAME_STARRED = "Starred"
    }

    override suspend fun getMailFolders(): Result<List<MailFolder>> {
        return try {
            val response = httpClient.get("$BASE_URL/labels")
            val labelList = response.body<GmailLabelList>()

            val mailFolders = labelList.labels
                .mapNotNull { mapLabelToMailFolder(it) } // mapNotNull will filter out those that map to null

            Log.d(TAG, "Successfully fetched and processed ${mailFolders.size} visible labels")
            Result.success(mailFolders.sortedBy { it.displayName }) // Sort for consistent UI
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Gmail labels", e)
            Result.failure(errorMapper.mapNetworkOrApiException(e))
        }
    }

    private fun mapLabelToMailFolder(label: GmailLabel): MailFolder? {
        val labelIdUpper = label.id.uppercase()
        val labelNameUpper = label.name.uppercase()

        val (displayName: String, type: WellKnownFolderType) = when (labelIdUpper) {
            GMAIL_LABEL_ID_INBOX -> Pair(DISPLAY_NAME_INBOX, WellKnownFolderType.INBOX)
            GMAIL_LABEL_ID_SENT -> Pair(DISPLAY_NAME_SENT_ITEMS, WellKnownFolderType.SENT_ITEMS)
            GMAIL_LABEL_ID_DRAFT -> Pair(DISPLAY_NAME_DRAFTS, WellKnownFolderType.DRAFTS)
            GMAIL_LABEL_ID_TRASH -> Pair(DISPLAY_NAME_TRASH, WellKnownFolderType.TRASH)
            GMAIL_LABEL_ID_SPAM -> Pair(DISPLAY_NAME_SPAM, WellKnownFolderType.SPAM)
            GMAIL_LABEL_ID_IMPORTANT -> Pair(DISPLAY_NAME_IMPORTANT, WellKnownFolderType.IMPORTANT)
            GMAIL_LABEL_ID_STARRED -> Pair(DISPLAY_NAME_STARRED, WellKnownFolderType.STARRED)
            GMAIL_ID_CHAT -> Pair(
                label.name,
                WellKnownFolderType.HIDDEN
            ) // Explicitly hide CHAT by ID
            GMAIL_NAME_UNREAD -> { // If "UNREAD" is an ID (less likely) or a specific name to hide
                Log.w(
                    TAG,
                    "Label with ID/Name 'UNREAD' encountered. Marking as HIDDEN. Actual Name: ${label.name}, ID: ${label.id}"
                )
                Pair(label.name, WellKnownFolderType.HIDDEN)
            }
            // Fallback to check by name for things like "[Gmail]/All Mail" or user-created "Notes"
            else -> when (labelNameUpper) {
                GMAIL_NAME_ALL_MAIL -> Pair(DISPLAY_NAME_ARCHIVE, WellKnownFolderType.ARCHIVE)
                GMAIL_NAME_NOTES -> Pair(label.name, WellKnownFolderType.HIDDEN)
                GMAIL_NAME_CONVERSATION_HISTORY -> Pair(label.name, WellKnownFolderType.HIDDEN)
                // If CHAT or UNREAD were not caught by ID, catch by name
                GMAIL_ID_CHAT -> Pair(label.name, WellKnownFolderType.HIDDEN)
                GMAIL_NAME_UNREAD -> {
                    Log.w(
                        TAG,
                        "Label with name 'UNREAD' encountered. Marking as HIDDEN. ID: ${label.id}"
                    )
                    Pair(label.name, WellKnownFolderType.HIDDEN)
                }

                else -> {
                    // For other labels, respect Gmail's visibility hints ONLY IF they are not critical system labels already handled.
                    // Critical system labels (Inbox, Sent, Drafts, Trash, Spam, Archive) should generally not be hidden by this.
                    if (label.labelListVisibility == "labelHide" || label.messageListVisibility == "hide") {
                        Log.d(
                            TAG,
                            "Label '${label.name}' (ID: ${label.id}) is hidden by Gmail settings and not a critical override."
                        )
                        return null // Explicitly hidden by Gmail settings and not an essential folder
                    }
                    // If it's a system label not yet mapped, classify as OTHER. Otherwise, USER_CREATED.
                    if (label.type == "system") {
                        Pair(label.name, WellKnownFolderType.OTHER)
                    } else {
                        Pair(label.name, WellKnownFolderType.USER_CREATED)
                    }
                }
            }
        }

        // Final check: if after all mapping, it's HIDDEN, return null.
        if (type == WellKnownFolderType.HIDDEN) {
            Log.d(
                TAG,
                "Label '${label.name}' (ID: ${label.id}) ultimately mapped to HIDDEN type. Will be filtered out."
            )
            return null
        }

        // Log all successfully mapped folders that are not hidden
        Log.d(
            TAG,
            "Mapping label: ID='${label.id}', Name='${label.name}', Type='${label.type}', GmailVis='${label.labelListVisibility}/${label.messageListVisibility}' -> DisplayName='$displayName', AppType='$type'"
        )


        return MailFolder(
            id = label.id,
            displayName = displayName,
            totalItemCount = label.messagesTotal ?: 0,
            unreadItemCount = label.messagesUnread ?: 0,
            type = type
        )
    }

    // ... (rest of the GmailApiHelper.kt file remains the same: getMessagesForFolder, fetchMessageDetails, mapGmailMessageToMessage, etc.)
    override suspend fun getMessagesForFolder(
        folderId: String,
        selectFields: List<String>,
        maxResults: Int
    ): Result<List<Message>> {
        return try {
            // 1. Get message IDs for the specified label
            val messageIdsResponse = httpClient.get("$BASE_URL/messages") {
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
                        fetchMessageDetails(messageIdentifier.id)
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
            Result.failure(errorMapper.mapNetworkOrApiException(e))
        }
    }

    private suspend fun fetchMessageDetails(messageId: String): Message? {
        return try {
            val response = httpClient.get("$BASE_URL/messages/$messageId") {
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
            null // Return null if an error occurs for an individual message
        }
    }

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

    private fun extractSenderAddress(from: String): String {
        return if (from.contains("<") && from.contains(">")) {
            from.substringAfter("<").substringBefore(">").trim()
        } else {
            from.trim()
        }
    }

    private fun extractSenderName(from: String): String {
        return if (from.contains("<") && from.contains(">")) {
            from.substringBefore("<").trim().removeSurrounding("\"")
        } else {
            // If there's no explicit name part, decide what to return.
            // Could be the email itself or an empty string if address is preferred separately.
            // For now, returning the trimmed input which might be just the email address.
            from.trim()
        }
    }

    private fun List<MessagePartHeader>.findHeaderValue(name: String): String? {
        return this.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }

    private fun parseEmailDate(dateStr: String): Date? {
        val formatters = listOf(
            SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss Z",
                Locale.US
            ),      // Standard RFC 822/2822
            SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.US),          // Without day name
            SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss",
                Locale.US
            ),       // Without explicit timezone (assumes local or needs context)
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)         // ISO 8601 (UTC)
            // Add more formats if you encounter them
        )

        for (formatter in formatters) {
            try {
                return formatter.parse(dateStr)
            } catch (e: Exception) {
                // Try next formatter
            }
        }
        Log.w(TAG, "Could not parse date: $dateStr, returning current date as fallback.")
        return Date() // Fallback
    }

    private fun decodeMessageBody(data: String?): String {
        if (data.isNullOrEmpty()) return ""
        return try {
            val decodedBytes = Base64.decode(
                data.replace('-', '+').replace('_', '/'), // URL-safe Base64 to standard Base64
                Base64.DEFAULT
            )
            String(decodedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding message body", e)
            ""
        }
    }

    override suspend fun markMessageRead(
        messageId: String,
        isRead: Boolean
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Marking message $messageId as ${if (isRead) "read" else "unread"}")
            val endpoint = "$BASE_URL/messages/$messageId/modify"
            val requestBody = buildJsonObject {
                if (isRead) {
                    putJsonObject("removeLabelIds") { put("0", "UNREAD") }
                } else {
                    putJsonObject("addLabelIds") { put("0", "UNREAD") }
                }
            }
            val response: HttpResponse = httpClient.post(endpoint) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
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
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Exception marking message $messageId as ${if (isRead) "read" else "unread"}",
                e
            )
            Result.failure(errorMapper.mapNetworkOrApiException(e))
        }
    }

    override suspend fun deleteMessage(
        messageId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Trashing message $messageId")
            val endpoint = "$BASE_URL/messages/$messageId/trash"
            val response: HttpResponse = httpClient.post(endpoint) // Gmail uses POST for trash
            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully trashed message $messageId")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error trashing message: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception trashing message $messageId", e)
            Result.failure(errorMapper.mapNetworkOrApiException(e))
        }
    }

    override suspend fun moveMessage(
        messageId: String,
        targetFolderId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Moving message $messageId to folder (label) $targetFolderId")
            val messageResponse = httpClient.get("$BASE_URL/messages/$messageId") {
                parameter("format", "minimal") // We only need current labelIds
            }
            if (!messageResponse.status.isSuccess()) {
                val errorBody = messageResponse.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message for label modification: ${messageResponse.status} - $errorBody"
                )
                return Result.failure(
                    errorMapper.mapHttpError(
                        messageResponse.status.value,
                        errorBody
                    )
                )
            }
            val message = messageResponse.body<GmailMessage>()

            val addLabelIds = mutableListOf<String>()
            if (targetFolderId.isNotEmpty()) {
                addLabelIds.add(targetFolderId)
            }

            // Remove all other user-defined labels and INBOX if not the target
            // Keep system labels like IMPORTANT, STARRED, CATEGORY_* unless explicitly removed by moving to Trash/Spam
            val removeLabelIds = message.labelIds.filter { currentLabelId ->
                val isUserLabel = !currentLabelId.startsWith("CATEGORY_") &&
                        !currentLabelId.startsWith("CHAT") &&
                        currentLabelId !in listOf(
                    GMAIL_LABEL_ID_INBOX,
                    GMAIL_LABEL_ID_SENT,
                    GMAIL_LABEL_ID_DRAFT,
                    GMAIL_LABEL_ID_SPAM,
                    GMAIL_LABEL_ID_TRASH,
                    GMAIL_LABEL_ID_IMPORTANT,
                    GMAIL_LABEL_ID_STARRED,
                    GMAIL_NAME_UNREAD
                )

                (isUserLabel && currentLabelId != targetFolderId) || (currentLabelId == GMAIL_LABEL_ID_INBOX && targetFolderId != GMAIL_LABEL_ID_INBOX)
            }.toMutableList()

            // If moving to Archive (which isn't a real label to add, but means removing INBOX)
            if (targetFolderId == GMAIL_NAME_ALL_MAIL || targetFolderId == "ARCHIVE") { // Assuming "ARCHIVE" is our internal representation for the action
                if (message.labelIds.contains(GMAIL_LABEL_ID_INBOX) && !removeLabelIds.contains(
                        GMAIL_LABEL_ID_INBOX
                    )
                ) {
                    removeLabelIds.add(GMAIL_LABEL_ID_INBOX)
                }
                addLabelIds.remove(targetFolderId) // Don't try to add "ARCHIVE" as a label
            }


            val endpoint = "$BASE_URL/messages/$messageId/modify"
            val requestBody = buildJsonObject {
                if (addLabelIds.isNotEmpty()) {
                    putJsonObject("addLabelIds") {
                        addLabelIds.forEachIndexed { index, labelId ->
                            put(
                                index.toString(),
                                labelId
                            )
                        }
                    }
                }
                if (removeLabelIds.isNotEmpty()) {
                    putJsonObject("removeLabelIds") {
                        removeLabelIds.forEachIndexed { index, labelId ->
                            put(
                                index.toString(),
                                labelId
                            )
                        }
                    }
                }
            }

            if (addLabelIds.isEmpty() && removeLabelIds.isEmpty()) {
                Log.d(
                    TAG,
                    "No label changes needed for message $messageId to folder $targetFolderId."
                )
                return Result.success(true)
            }

            val response: HttpResponse = httpClient.post(endpoint) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                setBody(requestBody.toString())
            }

            if (response.status.isSuccess()) {
                Log.d(
                    TAG,
                    "Successfully modified labels for message $messageId (moved to $targetFolderId)"
                )
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error moving message: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception moving message $messageId to folder $targetFolderId", e)
            Result.failure(errorMapper.mapNetworkOrApiException(e))
        }
    }
}