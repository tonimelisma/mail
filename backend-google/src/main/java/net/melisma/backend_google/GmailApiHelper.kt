package net.melisma.backend_google

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
import kotlinx.serialization.json.Json
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GmailApiHelper @Inject constructor(
    @GoogleHttpClient private val httpClient: HttpClient,
    private val errorMapper: ErrorMapperService
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
        private const val GMAIL_ID_CHAT = "CHAT"
        private const val GMAIL_NAME_ALL_MAIL = "[GMAIL]/ALL MAIL"

        private val HIDE_BY_NAME_LIST_UPPERCASE = listOf(
            "NOTES",
            "CONVERSATION HISTORY",
            // "UNREAD" is tricky as a folder name. Gmail uses UNREAD as a label on messages.
            // If a label named "UNREAD" truly exists and needs hiding, it's here.
            // Otherwise, this entry might not be needed or could cause issues if a legitimate folder is named "Unread".
            "UNREAD"
        )

        private const val DISPLAY_NAME_INBOX = "Inbox"
        private const val DISPLAY_NAME_SENT_ITEMS = "Sent Items"
        private const val DISPLAY_NAME_DRAFTS = "Drafts"
        private const val DISPLAY_NAME_ARCHIVE = "Archive"
        private const val DISPLAY_NAME_TRASH = "Trash"
        private const val DISPLAY_NAME_SPAM = "Spam"
        private const val DISPLAY_NAME_IMPORTANT = "Important"
        private const val DISPLAY_NAME_STARRED = "Starred"
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    override suspend fun getMailFolders(): Result<List<MailFolder>> {
        return try {
            Log.d(TAG, "Fetching Gmail labels from API...")
            val response: HttpResponse = httpClient.get("$BASE_URL/labels")
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Log.e(TAG, "Error fetching labels: ${response.status} - $responseBodyText")
                val httpException =
                    IOException("Gmail API Error ${response.status.value} fetching labels: $responseBodyText")
                val errorMessageString = errorMapper.mapNetworkOrApiException(httpException)
                return Result.failure(Exception(errorMessageString))
            }

            Log.v(TAG, "Raw Gmail labels response: $responseBodyText")
            val labelList = jsonParser.decodeFromString<GmailLabelList>(responseBodyText)
            Log.d(TAG, "Received ${labelList.labels.size} labels from API. Processing...")

            val mailFolders = labelList.labels
                .mapNotNull { mapLabelToMailFolder(it) }
                .sortedBy { it.displayName }

            Log.i(
                TAG,
                "Successfully mapped and filtered to ${mailFolders.size} visible MailFolders."
            )
            Result.success(mailFolders)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getMailFolders: ${e.javaClass.simpleName} - ${e.message}", e)
            val errorMessageString = errorMapper.mapNetworkOrApiException(e)
            Result.failure(Exception(errorMessageString))
        }
    }

    private fun mapLabelToMailFolder(label: GmailLabel): MailFolder? {
        val labelIdUpper = label.id.uppercase()
        val labelNameUpper = label.name.uppercase()

        Log.v(
            TAG,
            "Processing Label: ID='${label.id}', Name='${label.name}', RawType='${label.type}', LabelListVis='${label.labelListVisibility}', MessageListVis='${label.messageListVisibility}'"
        )

        // Initialize with a default that will be overridden or lead to filtering
        var type: WellKnownFolderType = WellKnownFolderType.USER_CREATED
        var displayName: String = label.name
        var determinedByTypeOrName = false

        // 1. Prioritize mapping essential system folders by their standard IDs
        when (labelIdUpper) {
            GMAIL_LABEL_ID_INBOX -> {
                type = WellKnownFolderType.INBOX; displayName =
                    DISPLAY_NAME_INBOX; determinedByTypeOrName = true
            }

            GMAIL_LABEL_ID_SENT -> {
                type = WellKnownFolderType.SENT_ITEMS; displayName =
                    DISPLAY_NAME_SENT_ITEMS; determinedByTypeOrName = true
            }

            GMAIL_LABEL_ID_DRAFT -> {
                type = WellKnownFolderType.DRAFTS; displayName =
                    DISPLAY_NAME_DRAFTS; determinedByTypeOrName = true
            }

            GMAIL_LABEL_ID_TRASH -> {
                type = WellKnownFolderType.TRASH; displayName =
                    DISPLAY_NAME_TRASH; determinedByTypeOrName = true
            }

            GMAIL_LABEL_ID_SPAM -> {
                type = WellKnownFolderType.SPAM; displayName =
                    DISPLAY_NAME_SPAM; determinedByTypeOrName = true
            }

            GMAIL_LABEL_ID_IMPORTANT -> {
                type = WellKnownFolderType.IMPORTANT; displayName =
                    DISPLAY_NAME_IMPORTANT; determinedByTypeOrName = true
            }

            GMAIL_LABEL_ID_STARRED -> {
                type = WellKnownFolderType.STARRED; displayName =
                    DISPLAY_NAME_STARRED; determinedByTypeOrName = true
            }

            GMAIL_ID_CHAT -> {
                type = WellKnownFolderType.HIDDEN; determinedByTypeOrName = true
            }

            "UNREAD" -> { // If "UNREAD" is an ID (less common)
                type = WellKnownFolderType.HIDDEN; determinedByTypeOrName = true
                Log.w(TAG, "Label ID is 'UNREAD'. Setting HIDDEN. Original Name: '${label.name}'")
            }
        }

        // 2. If not mapped by ID, try mapping by common names or our explicit hide list
        if (!determinedByTypeOrName) {
            when (labelNameUpper) {
                GMAIL_NAME_ALL_MAIL -> {
                    type = WellKnownFolderType.ARCHIVE
                    displayName = DISPLAY_NAME_ARCHIVE
                    determinedByTypeOrName = true
                }

                in HIDE_BY_NAME_LIST_UPPERCASE -> {
                    type = WellKnownFolderType.HIDDEN
                    determinedByTypeOrName = true
                    Log.d(
                        TAG,
                        "Label name '${label.name}' is in HIDE_BY_NAME_LIST. Setting HIDDEN."
                    )
                }
            }
        }

        // 3. If it's one of our essential types, we *do not* respect Gmail's visibility hints for hiding.
        //    They must be shown.
        val isEssentialSystemFolder = type in listOf(
            WellKnownFolderType.INBOX, WellKnownFolderType.SENT_ITEMS, WellKnownFolderType.DRAFTS,
            WellKnownFolderType.TRASH, WellKnownFolderType.SPAM, WellKnownFolderType.ARCHIVE,
            WellKnownFolderType.IMPORTANT, WellKnownFolderType.STARRED
        )

        if (!isEssentialSystemFolder && !determinedByTypeOrName) {
            // For non-essential labels not yet classified, NOW check Gmail's visibility hints
            if (label.labelListVisibility == "labelHide" || label.messageListVisibility == "hide") {
                Log.i(
                    TAG,
                    "Label '${label.name}' (ID: ${label.id}) is being hidden by Gmail's labelListVisibility/messageListVisibility settings ('${label.labelListVisibility}'/'${label.messageListVisibility}') as it's not an overridden essential folder."
                )
                return null
            }
            // Classify remaining visible labels
            type =
                if (label.type == "system") WellKnownFolderType.OTHER else WellKnownFolderType.USER_CREATED
        } else if (!isEssentialSystemFolder && type != WellKnownFolderType.HIDDEN) {
            // This case is for labels that might have been matched by name (e.g. GMAIL_NAME_ALL_MAIL) but aren't "essential system by ID"
            // and weren't already set to HIDDEN. We still apply general visibility rules if they weren't critical.
            // However, GMAIL_NAME_ALL_MAIL (Archive) is pretty critical.
            // This logic block might need refinement if specific named labels also need to bypass Gmail's hide flags.
            // For now, only ID-matched essential labels bypass the visibility hint.
            // If GMAIL_NAME_ALL_MAIL was identified by name and Gmail suggests hiding it, it would be hidden by the below.
            if (label.labelListVisibility == "labelHide" || label.messageListVisibility == "hide") {
                // Exception: if it's ARCHIVE (identified by name), we might still want to show it.
                if (type == WellKnownFolderType.ARCHIVE) {
                    Log.w(
                        TAG,
                        "Label '${label.name}' mapped to ARCHIVE but Gmail suggests hiding. Showing it anyway."
                    )
                } else {
                    Log.i(
                        TAG,
                        "Label '${label.name}' (ID: ${label.id}) hidden by Gmail's visibility hint (was type $type)."
                    )
                    return null
                }
            }
        }


        // Final check: if type ended up as HIDDEN from any rule, return null.
        if (type == WellKnownFolderType.HIDDEN) {
            Log.i(
                TAG,
                "Label '${label.name}' (ID: ${label.id}) resulted in HIDDEN type. Filtering out."
            )
            return null
        }

        Log.i(
            TAG,
            "Successfully mapped Label: ID='${label.id}', Name='${label.name}' to DisplayName='$displayName', AppType='$type'"
        )
        return MailFolder(
            id = label.id,
            displayName = displayName,
            totalItemCount = label.messagesTotal ?: 0,
            unreadItemCount = label.messagesUnread ?: 0,
            type = type
        )
    }

    override suspend fun getMessagesForFolder(
        folderId: String,
        selectFields: List<String>,
        maxResults: Int
    ): Result<List<Message>> {
        return try {
            val messageIdsResponse = httpClient.get("$BASE_URL/messages") {
                parameter("maxResults", maxResults)
                parameter("labelIds", folderId)
            }
            if (!messageIdsResponse.status.isSuccess()) {
                val errorBody = messageIdsResponse.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message list for $folderId: ${messageIdsResponse.status} - $errorBody"
                )
                val httpException =
                    IOException("HTTP Error ${messageIdsResponse.status.value} fetching messages for $folderId: $errorBody")
                val errorMessageString = errorMapper.mapNetworkOrApiException(httpException)
                return Result.failure(Exception(errorMessageString))
            }

            val messageList = messageIdsResponse.body<GmailMessageList>()
            if (messageList.messages.isEmpty()) {
                Log.d(TAG, "No messages found for folder (label): $folderId")
                return Result.success(emptyList())
            }

            val messages = supervisorScope {
                messageList.messages.map { messageIdentifier ->
                    async { fetchMessageDetails(messageIdentifier.id) }
                }.awaitAll().filterNotNull()
            }
            Log.d(
                TAG,
                "Successfully fetched ${messages.size} messages for folder (label): $folderId"
            )
            Result.success(messages)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getMessagesForFolder for $folderId", e)
            val errorMessageString = errorMapper.mapNetworkOrApiException(e)
            Result.failure(Exception(errorMessageString))
        }
    }

    private suspend fun fetchMessageDetails(messageId: String): Message? {
        return try {
            val response = httpClient.get("$BASE_URL/messages/$messageId") {
                parameter("format", "metadata")
                parameter("metadataHeaders", "Subject,From,Date")
            }
            if (!response.status.isSuccess()) {
                Log.w(
                    TAG,
                    "Error fetching details for message $messageId: ${response.status} - ${response.bodyAsText()}"
                )
                return null
            }
            val gmailMessage = response.body<GmailMessage>()
            mapGmailMessageToMessage(gmailMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching message details for ID: $messageId", e)
            null
        }
    }

    private fun mapGmailMessageToMessage(gmailMessage: GmailMessage): Message {
        val headers = gmailMessage.payload?.headers ?: emptyList()
        val subject = headers.findHeaderValue("Subject") ?: "(No Subject)"
        val from = headers.findHeaderValue("From") ?: ""
        val dateStr = headers.findHeaderValue("Date")
        val date = dateStr?.let { parseEmailDate(it) } ?: Date()
        val senderName = extractSenderName(from)
        val senderAddress = extractSenderAddress(from)
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
            from.trim()
        }
    }

    private fun List<MessagePartHeader>.findHeaderValue(name: String): String? {
        return this.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }

    private fun parseEmailDate(dateStr: String): Date? {
        val formatters = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        )
        for (formatter in formatters) {
            try {
                return formatter.parse(dateStr.trim())
            } catch (e: Exception) { /* Try next */
            }
        }
        Log.w(TAG, "Could not parse date string: '$dateStr'")
        return Date()
    }

    override suspend fun markMessageRead(messageId: String, isRead: Boolean): Result<Boolean> {
        return try {
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
            if (response.status.isSuccess()) Result.success(true)
            else {
                val errorBody = response.bodyAsText()
                val httpException =
                    IOException("HTTP Error ${response.status.value} marking message read/unread: $errorBody")
                Result.failure(Exception(errorMapper.mapNetworkOrApiException(httpException)))
            }
        } catch (e: Exception) {
            Result.failure(Exception(errorMapper.mapNetworkOrApiException(e)))
        }
    }

    override suspend fun deleteMessage(messageId: String): Result<Boolean> {
        return try {
            val endpoint = "$BASE_URL/messages/$messageId/trash"
            val response: HttpResponse = httpClient.post(endpoint)
            if (response.status.isSuccess()) Result.success(true)
            else {
                val errorBody = response.bodyAsText()
                val httpException =
                    IOException("HTTP Error ${response.status.value} deleting message: $errorBody")
                Result.failure(Exception(errorMapper.mapNetworkOrApiException(httpException)))
            }
        } catch (e: Exception) {
            Result.failure(Exception(errorMapper.mapNetworkOrApiException(e)))
        }
    }

    override suspend fun moveMessage(messageId: String, targetFolderId: String): Result<Boolean> {
        return try {
            val messageResponse =
                httpClient.get("$BASE_URL/messages/$messageId") { parameter("format", "minimal") }
            if (!messageResponse.status.isSuccess()) {
                val errorBody = messageResponse.bodyAsText()
                val httpException =
                    IOException("HTTP Error ${messageResponse.status.value} (fetching message for move): $errorBody")
                return Result.failure(Exception(errorMapper.mapNetworkOrApiException(httpException)))
            }
            val message = messageResponse.body<GmailMessage>()
            val addLabelIds =
                if (targetFolderId.isNotEmpty() && targetFolderId != "ARCHIVE") listOf(
                    targetFolderId
                ) else emptyList()

            val removeLabelIds = message.labelIds.filter { currentLabelId ->
                val isUserLabelOrInbox = !currentLabelId.startsWith("CATEGORY_") &&
                        currentLabelId !in listOf(
                    GMAIL_LABEL_ID_SENT,
                    GMAIL_LABEL_ID_DRAFT,
                    GMAIL_LABEL_ID_SPAM,
                    GMAIL_LABEL_ID_TRASH,
                    GMAIL_LABEL_ID_IMPORTANT,
                    GMAIL_LABEL_ID_STARRED
                )
                (isUserLabelOrInbox && currentLabelId != targetFolderId) ||
                        (currentLabelId == GMAIL_LABEL_ID_INBOX && (targetFolderId == "ARCHIVE" || (targetFolderId.isNotEmpty() && targetFolderId != GMAIL_LABEL_ID_INBOX)))
            }.distinct()

            if (addLabelIds.isEmpty() && removeLabelIds.isEmpty()) {
                Log.d(
                    TAG,
                    "No label changes needed for message $messageId moving to $targetFolderId"
                )
                return Result.success(true)
            }

            val endpoint = "$BASE_URL/messages/$messageId/modify"
            val requestBody = buildJsonObject {
                if (addLabelIds.isNotEmpty()) {
                    putJsonObject("addLabelIds") {
                        addLabelIds.forEachIndexed { i, id ->
                            put(
                                i.toString(),
                                id
                            )
                        }
                    }
                }
                if (removeLabelIds.isNotEmpty()) {
                    putJsonObject("removeLabelIds") {
                        removeLabelIds.forEachIndexed { i, id ->
                            put(
                                i.toString(),
                                id
                            )
                        }
                    }
                }
            }
            val response: HttpResponse = httpClient.post(endpoint) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                setBody(requestBody.toString())
            }
            if (response.status.isSuccess()) Result.success(true)
            else {
                val errorBody = response.bodyAsText()
                val httpException =
                    IOException("HTTP Error ${response.status.value} (modifying labels for move): $errorBody")
                Result.failure(Exception(errorMapper.mapNetworkOrApiException(httpException)))
            }
        } catch (e: Exception) {
            Result.failure(Exception(errorMapper.mapNetworkOrApiException(e)))
        }
    }
}