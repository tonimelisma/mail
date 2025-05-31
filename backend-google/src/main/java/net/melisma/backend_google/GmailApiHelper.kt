package net.melisma.backend_google

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import net.melisma.backend_google.auth.GoogleNeedsReauthenticationException
import net.melisma.backend_google.di.GoogleHttpClient
import net.melisma.backend_google.model.GmailLabel
import net.melisma.backend_google.model.GmailLabelList
import net.melisma.backend_google.model.GmailMessage
import net.melisma.backend_google.model.GmailMessageList
import net.melisma.backend_google.model.GmailThread
import net.melisma.backend_google.model.MessagePart
import net.melisma.backend_google.model.MessagePartHeader
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.WellKnownFolderType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GmailApiHelper @Inject constructor(
    @GoogleHttpClient private val httpClient: HttpClient,
    private val errorMapper: ErrorMapperService
) : MailApiService {
    private val TAG = "GmailApiHelper" // Consistent TAG
    private val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

    companion object {
        // ... (companion object content as before)
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

    // Ensure Json parser is configured to ignore unknown keys
    private val jsonParser = Json { ignoreUnknownKeys = true }

    override suspend fun getMailFolders(
        activity: android.app.Activity?,
        accountId: String
    ): Result<List<MailFolder>> {
        return try {
            Log.d(TAG, "Fetching Gmail labels for accountId: $accountId from API...")
            val response: HttpResponse = httpClient.get("$BASE_URL/labels") {
                accept(ContentType.Application.Json)
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Log.e(TAG, "Error fetching labels: ${response.status} - Body: $responseBodyText")
                val httpEx =
                    io.ktor.client.plugins.ClientRequestException(response, responseBodyText)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                return Result.failure(Exception(mappedDetails.message, httpEx))
            }

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
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during getMailFolders.",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching mail folders", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    private fun mapLabelToMailFolder(label: GmailLabel): MailFolder? {
        // ... (mapLabelToMailFolder implementation as before, ensure TAG is used for logs)
        val labelIdUpper = label.id.uppercase()
        val labelNameUpper = label.name.uppercase()

        Log.v(
            TAG,
            "Processing Label: ID='${label.id}', Name='${label.name}', RawType='${label.type}', LabelListVis='${label.labelListVisibility}', MessageListVis='${label.messageListVisibility}'"
        )

        var type: WellKnownFolderType = WellKnownFolderType.USER_CREATED
        var displayName: String = label.name
        var determinedByTypeOrName = false

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
            "UNREAD" -> {
                type = WellKnownFolderType.HIDDEN; determinedByTypeOrName = true
                Log.w(TAG, "Label ID is 'UNREAD'. Setting HIDDEN. Original Name: '${label.name}'")
            }
        }

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

        val isEssentialSystemFolder = type in listOf(
            WellKnownFolderType.INBOX, WellKnownFolderType.SENT_ITEMS, WellKnownFolderType.DRAFTS,
            WellKnownFolderType.TRASH, WellKnownFolderType.SPAM, WellKnownFolderType.ARCHIVE,
            WellKnownFolderType.IMPORTANT, WellKnownFolderType.STARRED
        )

        if (!isEssentialSystemFolder && !determinedByTypeOrName) {
            if (label.labelListVisibility == "labelHide" || label.messageListVisibility == "hide") {
                Log.i(
                    TAG,
                    "Label '${label.name}' (ID: ${label.id}) is being hidden by Gmail's labelListVisibility/messageListVisibility settings ('${label.labelListVisibility}'/'${label.messageListVisibility}') as it's not an overridden essential folder."
                )
                return null
            }
            type =
                if (label.type == "system") WellKnownFolderType.OTHER else WellKnownFolderType.USER_CREATED
        } else if (!isEssentialSystemFolder && type != WellKnownFolderType.HIDDEN) {
            if (label.labelListVisibility == "labelHide" || label.messageListVisibility == "hide") {
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
        selectFields: List<String>, // Removed default. Gmail uses 'format' and 'metadataHeaders', not direct 'selectFields' for message list.
        maxResults: Int // Removed default
    ): Result<List<Message>> {
        return try {
            Log.d(
                TAG,
                "getMessagesForFolder: Fetching message IDs for folderId: $folderId, maxResults: $maxResults. `selectFields` param is noted but not directly used by Gmail list API in this way."
            )
            val messageIdsResponse = httpClient.get("$BASE_URL/messages") {
                accept(ContentType.Application.Json)
                parameter("maxResults", maxResults)
                parameter("labelIds", folderId)
                // Fields for list are minimal (id, threadId). Full details fetched per message.
            }
            if (!messageIdsResponse.status.isSuccess()) {
                val errorBody = messageIdsResponse.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message list for $folderId: ${messageIdsResponse.status} - $errorBody"
                )
                val httpEx =
                    io.ktor.client.plugins.ClientRequestException(messageIdsResponse, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                return Result.failure(Exception(mappedDetails.message, httpEx))
            }

            val messageList = messageIdsResponse.body<GmailMessageList>()
            if (messageList.messages.isNullOrEmpty()) { // Check for null as well
                Log.d(TAG, "No messages found for folder (label): $folderId")
                return Result.success(emptyList())
            }
            Log.d(TAG, "Fetched ${messageList.messages.size} message IDs. Now fetching details.")

            // Use supervisorScope for fetching details concurrently
            val messages = supervisorScope {
                messageList.messages.map { messageIdentifier ->
                    async {
                        internalFetchMessageDetails(
                            messageIdentifier.id,
                            selectFields
                        )
                    } // Pass selectFields for potential use
                }.awaitAll().filterNotNull()
            }
            Log.d(
                TAG,
                "Successfully processed details for ${messages.size} messages for folder (label): $folderId"
            )
            Result.success(messages)
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during getMessagesForFolder (folder: $folderId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching messages for folder $folderId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    // Renamed to avoid conflict if a public getMessageDetails(Flow) is added.
    // `selectFields` param added for consistency, though Gmail API uses metadataHeaders for finer control
    private suspend fun internalFetchMessageDetails(
        messageId: String,
        selectFields: List<String> = emptyList()
    ): Message? {
        Log.d(
            TAG,
            "internalFetchMessageDetails: Fetching details for messageId: $messageId. SelectFields (metadata hint): $selectFields"
        )
        val gmailMessage = fetchRawGmailMessage(messageId)
        return gmailMessage?.let { mapGmailMessageToMessage(it) }
    }

    private suspend fun fetchRawGmailMessage(messageId: String): GmailMessage? {
        Log.d(TAG, "fetchRawGmailMessage: Fetching raw GmailMessage for id: $messageId")
        return try {
            val response: HttpResponse = httpClient.get("$BASE_URL/messages/$messageId") {
                accept(ContentType.Application.Json)
                parameter("format", "FULL") // FULL provides payload, headers, body, snippet, etc.
            }

            if (!response.status.isSuccess()) {
                Log.w(
                    TAG,
                    "Error fetching raw gmail message $messageId: ${response.status} - ${response.bodyAsText()}"
                )
                return null
            }
            response.body<GmailMessage>()
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during fetchRawGmailMessage (message: $messageId).",
                e
            )
            throw e // Rethrow to be handled by the caller, or map to a specific error if preferred
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchRawGmailMessage for ID: $messageId", e)
            null
        }
    }

    // Helper function to parse From header into name and address
    private fun parseSender(header: String?): Pair<String?, String?> {
        if (header == null) return Pair(null, null)
        val email = extractSenderAddress(header)
        val name = extractSenderName(header)
        return Pair(if (name.isNotBlank()) name else null, if (email.isNotBlank()) email else null)
    }

    private fun decodeBase64(data: String): String? {
        return try {
            String(Base64.decode(data, Base64.URL_SAFE))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Base64 decoding failed for body part", e)
            null
        }
    }

    // New helper to find best body part (content and type) from a list of MessageParts
    private fun findBestBodyPart(partsToSearch: List<MessagePart>): Pair<String?, String?> {
        var htmlBody: String? = null
        var textBody: String? = null
        var htmlContentType: String? = null
        var textContentType: String? = null

        fun searchPartsRecursive(currentParts: List<MessagePart>?) {
            currentParts?.forEach { part ->
                if (part.mimeType?.startsWith(
                        "text/html",
                        ignoreCase = true
                    ) == true && part.body?.data != null
                ) {
                    if (htmlBody == null) { // Take the first HTML part found
                        htmlBody = part.body.data?.let { decodeBase64(it) }
                        htmlContentType = "text/html"
                    }
                } else if (part.mimeType?.startsWith(
                        "text/plain",
                        ignoreCase = true
                    ) == true && part.body?.data != null
                ) {
                    if (textBody == null) { // Take the first text part found
                        textBody = part.body.data?.let { decodeBase64(it) }
                        textContentType = "text/plain"
                    }
                }

                // If this part is multipart and we haven't found preferred types yet, recurse
                if (part.mimeType?.startsWith(
                        "multipart/",
                        ignoreCase = true
                    ) == true && !part.parts.isNullOrEmpty()
                ) {
                    if (htmlBody == null || textBody == null) { // Only recurse if we still need one of the types
                        searchPartsRecursive(part.parts)
                    }
                }
                // Stop early if both found
                if (htmlBody != null && textBody != null) return@forEach
            }
        }

        searchPartsRecursive(partsToSearch)

        return if (htmlBody != null) {
            Pair(htmlBody, htmlContentType)
        } else if (textBody != null) {
            Pair(textBody, textContentType)
        } else {
            Pair(null, null) // No suitable text/html or text/plain part found
        }
    }

    private fun mapGmailMessageToMessage(
        gmailMessage: GmailMessage,
        isForBodyContent: Boolean = false
    ): Message? {
        Log.v(
            TAG,
            "mapGmailMessageToMessage for Gmail Message ID: ${gmailMessage.id}, isForBodyContent: $isForBodyContent"
        )
        try {
            val headers = gmailMessage.payload?.headers ?: emptyList()
            val subject = headers.findHeaderValue("Subject")
            val fromHeader = headers.findHeaderValue("From") ?: "Unknown Sender"
            val senderName = extractSenderName(fromHeader)
            val senderAddress = extractSenderAddress(fromHeader)

            val dateHeader = headers.findHeaderValue("Date")
            val parsedSentDate = dateHeader?.let { parseEmailDate(it) }

            val parsedReceivedDate =
                gmailMessage.internalDate?.toLongOrNull()?.let { Date(it) } ?: parsedSentDate
                ?: Date()

            val outputDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            outputDateFormat.timeZone = TimeZone.getTimeZone("UTC")

            val sentDateTimeString = parsedSentDate?.let { outputDateFormat.format(it) }
            val receivedDateTimeString = outputDateFormat.format(parsedReceivedDate)

            val threadId = gmailMessage.threadId
            val isRead = gmailMessage.labelIds?.contains("UNREAD") != true

            var messageBody: String? = null
            var messageBodyContentType: String? = "text/plain" // Default

            if (isForBodyContent && gmailMessage.payload != null) {
                val payload = gmailMessage.payload!! // Ensured not null by check
                var foundBody: String? = null
                var foundContentType: String? = null

                // Check if the main payload itself is the content (non-multipart)
                if (payload.parts.isNullOrEmpty() && payload.body?.data != null) {
                    if (payload.mimeType?.startsWith("text/html", ignoreCase = true) == true) {
                        foundBody = payload.body.data?.let { decodeBase64(it) }
                        foundContentType = "text/html"
                    } else if (payload.mimeType?.startsWith(
                            "text/plain",
                            ignoreCase = true
                        ) == true
                    ) {
                        foundBody = payload.body.data?.let { decodeBase64(it) }
                        foundContentType = "text/plain"
                    }
                }

                // If not found in main payload or it was multipart, search parts
                if (foundBody == null && !payload.parts.isNullOrEmpty()) {
                    val (bodyStr, contentTypeStr) = findBestBodyPart(payload.parts!!) // Pass the list of MessagePart
                    foundBody = bodyStr
                    foundContentType = contentTypeStr
                }

                messageBody = foundBody
                messageBodyContentType =
                    foundContentType ?: "text/plain" // Default if somehow still null
            }

            return Message(
                id = gmailMessage.id
                    ?: throw IllegalStateException("Message ID cannot be null from Gmail message: $gmailMessage"),
                threadId = threadId,
                receivedDateTime = receivedDateTimeString,
                sentDateTime = sentDateTimeString,
                subject = subject,
                senderName = senderName,
                senderAddress = senderAddress,
                bodyPreview = if (isForBodyContent && !messageBody.isNullOrEmpty()) null else gmailMessage.snippet?.replace(
                    "\\u003e",
                    ">"
                )?.replace("\\u003c", "<")
                    ?.replace("&#39;", "'")?.trim(),
                isRead = isRead,
                body = messageBody,
                bodyContentType = messageBodyContentType,
                isStarred = gmailMessage.labelIds?.contains(GMAIL_LABEL_ID_STARRED) == true,
                // hasAttachments - This needs more sophisticated logic, check payload for parts with filename/attachmentId
                hasAttachments = gmailMessage.payload?.parts?.any { !it.filename.isNullOrBlank() && !it.body?.attachmentId.isNullOrBlank() } == true // Basic check
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error mapping GmailMessage to Message: ${e.message}", e)
            return null
        }
    }

    private fun extractSenderAddress(fromHeader: String): String {
        // ... (implementation as before)
        return if (fromHeader.contains("<") && fromHeader.contains(">")) {
            fromHeader.substringAfter("<").substringBefore(">").trim()
        } else {
            fromHeader.trim()
        }
    }

    private fun extractSenderName(fromHeader: String): String {
        // ... (implementation as before)
        return if (fromHeader.contains("<") && fromHeader.contains(">")) {
            fromHeader.substringBefore("<").trim().removeSurrounding("\"")
        } else {
            // If no <>, assume the whole string is the name or address, prioritize address if it looks like one
            val trimmed = fromHeader.trim()
            if (trimmed.contains("@")) "" else trimmed // Crude: if it has @, assume it's address only, no separate name
        }
    }

    private fun List<MessagePartHeader>.findHeaderValue(name: String): String? {
        return this.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }

    private fun parseEmailDate(dateStr: String): Date? {
        // ... (implementation as before, ensure TAG is used for logs if errors)
        val formatters = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US), // Common format
            SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US),   // Without explicit timezone
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),    // ISO_8601
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                Locale.US
            )     // ISO_8601 with explicit offset
            // Potentially add more formats if you encounter them
        )
        for (formatter in formatters) {
            try {
                return formatter.parse(dateStr.trim())
            } catch (e: Exception) { /* Try next */
            }
        }
        Log.w(TAG, "Could not parse date string: '$dateStr' with known formats.")
        return null // Return null if all parsing attempts fail
    }


    // --- Other MailApiService methods (markMessageRead, deleteMessage, moveMessage) ---
    // Ensure they use the TAG for logging as well if you add logs there.
    override suspend fun markMessageRead(
        messageId: String,
        isRead: Boolean
    ): Result<Unit> {
        val action = if (isRead) "removeLabelIds" else "addLabelIds"
        val labels = listOf("UNREAD") // Gmail uses UNREAD label for unread status
        return modifyMessageLabels(messageId, action, labels)
    }

    override suspend fun starMessage(messageId: String, isStarred: Boolean): Result<Unit> {
        val action = if (isStarred) "addLabelIds" else "removeLabelIds"
        val labels = listOf(GMAIL_LABEL_ID_STARRED)
        return modifyMessageLabels(messageId, action, labels)
    }

    override suspend fun getMessageContent(messageId: String): Result<Message> {
        Log.d(TAG, "getMessageContent: Fetching full content for messageId $messageId")
        return try {
            val gmailMessage =
                fetchRawGmailMessage(messageId) // fetchRawGmailMessage gets format=FULL
                    ?: return Result.failure(Exception("Failed to fetch raw message for $messageId"))

            // Map, specifically requesting full body processing
            val message = mapGmailMessageToMessage(gmailMessage, isForBodyContent = true)
                ?: return Result.failure(Exception("Failed to map Gmail message $messageId to domain model for body content"))

            Log.i(
                TAG,
                "Successfully fetched and mapped content for message $messageId. Content type: ${message.bodyContentType}"
            )
            Result.success(message)

        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during getMessageContent (message: $messageId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getMessageContent for $messageId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    private suspend fun modifyMessageLabels(
        messageId: String,
        action: String,
        labels: List<String>
    ): Result<Unit> {
        Log.d(
            TAG,
            "modifyMessageLabels: messageId='$messageId', action='$action', labels=${
                labels.joinToString(", ")
            }"
        )
        return try {
            val requestBody = buildJsonObject {
                putJsonArray(action) { labels.forEach { add(it) } }
            }
            val response: HttpResponse = httpClient.post("$BASE_URL/messages/$messageId/modify") {
                // Removed explicit Content-Type header. Relying on ContentNegotiation.
                accept(ContentType.Application.Json)
                setBody(requestBody) // Send JsonObject directly
            }
            if (response.status.isSuccess()) {
                Result.success(Unit) // Changed to Unit
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error modifying message labels for $messageId: ${response.status} - $errorBody"
                )
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message, httpEx))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during modifyMessageLabels (message: $messageId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in modifyMessageLabels for $messageId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> { // Changed to Result<Unit>
        Log.d(TAG, "deleteMessage: messageId='$messageId'")
        return try {
            // Gmail API for trashing a message is a POST request with no body.
            val response: HttpResponse = httpClient.post("$BASE_URL/messages/$messageId/trash") {
                accept(ContentType.Application.Json)
                // No setBody needed
            }
            if (response.status.isSuccess()) {
                Result.success(Unit) // Changed to Unit
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error deleting (trashing) message $messageId: ${response.status} - $errorBody"
                )
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message, httpEx))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during deleteMessage (message: $messageId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteMessage for $messageId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    override suspend fun moveMessage(
        messageId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> { // Changed to Result<Unit>
        Log.d(
            TAG,
            "moveMessage: messageId='$messageId', from '$currentFolderId' to '$destinationFolderId'"
        )
        return try {
            val addLabelIds = mutableListOf<String>()
            val removeLabelIds = mutableListOf<String>()

            // ARCHIVE is a conceptual folder in UI, not a real label ID to add.
            // It means removing INBOX and potentially other current folder-like labels.
            if (destinationFolderId.equals("ARCHIVE", ignoreCase = true)) {
                removeLabelIds.add(GMAIL_LABEL_ID_INBOX) // Always attempt to remove INBOX for archive.
                if (!currentFolderId.isNullOrBlank() &&
                    !currentFolderId.equals(GMAIL_LABEL_ID_INBOX, ignoreCase = true) &&
                    !isSystemLabel(currentFolderId) &&
                    !currentFolderId.equals("ARCHIVE", ignoreCase = true)
                ) { // Check against "ARCHIVE" string for current folder
                    removeLabelIds.add(currentFolderId)
                    Log.d(
                        TAG,
                        "Archiving: also removing current user label '$currentFolderId' for message $messageId"
                    )
                }
                Log.d(
                    TAG,
                    "Archiving message $messageId. Will remove INBOX and potentially current user label '$currentFolderId'."
                )
            } else {
                addLabelIds.add(destinationFolderId) // Add the target folder's label

                if (!currentFolderId.isNullOrBlank() &&
                    currentFolderId != destinationFolderId &&
                    !currentFolderId.equals(
                        "ARCHIVE",
                        ignoreCase = true
                    ) && // Don't remove if current is conceptual ARCHIVE
                    (currentFolderId.equals(
                        GMAIL_LABEL_ID_INBOX,
                        ignoreCase = true
                    ) || !isSystemLabel(currentFolderId))
                ) {
                    removeLabelIds.add(currentFolderId)
                    Log.d(
                        TAG,
                        "Moving from '$currentFolderId' to '$destinationFolderId'. Removing source label."
                    )
                }
            }

            // Deduplicate, as a labelId might be in both add and remove if logic gets complex (e.g. currentFolderId == destinationFolderId initially)
            val finalAddLabels = addLabelIds.distinct()
            val finalRemoveLabels = removeLabelIds.distinct()
                .filterNot { it in finalAddLabels } // ensure a label isn't both added and removed

            if (finalAddLabels.isEmpty() && finalRemoveLabels.isEmpty()) {
                Log.i(
                    TAG,
                    "Move operation for $messageId resulted in no label changes. Skipping API call."
                )
                return Result.success(Unit)
            }

            val requestBody = buildJsonObject {
                if (finalAddLabels.isNotEmpty()) {
                    putJsonArray("addLabelIds") { finalAddLabels.forEach { add(it) } }
                }
                if (finalRemoveLabels.isNotEmpty()) {
                    putJsonArray("removeLabelIds") { finalRemoveLabels.forEach { add(it) } }
                }
            }
            val response: HttpResponse = httpClient.post("$BASE_URL/messages/$messageId/modify") {
                accept(ContentType.Application.Json)
                setBody(requestBody)
            }

            if (response.status.isSuccess()) {
                Log.i(
                    TAG,
                    "Successfully moved/modified labels for message $messageId to '$destinationFolderId'"
                )
                Result.success(Unit) // Changed to Unit
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error moving message $messageId (modifying labels): ${response.status} - $errorBody"
                )
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message, httpEx))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during moveMessage (message: $messageId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in moveMessage for $messageId to $destinationFolderId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    private fun isSystemLabel(labelId: String): Boolean {
        return labelId.uppercase() in listOf(
            GMAIL_LABEL_ID_INBOX,
            GMAIL_LABEL_ID_SENT,
            GMAIL_LABEL_ID_DRAFT,
            GMAIL_LABEL_ID_TRASH,
            GMAIL_LABEL_ID_SPAM,
            GMAIL_LABEL_ID_IMPORTANT,
            GMAIL_LABEL_ID_STARRED,
            // GMAIL_LABEL_ID_ARCHIVE is not a real label, so it's not listed here.
            // "UNREAD" is a state, not a folder label to be removed during a move.
        )
    }

    override suspend fun getMessagesForThread(
        threadId: String,
        folderId: String, // Added folderId (context for client, Gmail API for threads.get doesn't use it)
        selectFields: List<String>, // Added selectFields
        maxResults: Int // Added maxResults
    ): Result<List<Message>> {
        return try {
            Log.d(
                TAG,
                "Fetching messages for thread ID: $threadId (original folder: $folderId, maxResults: $maxResults). selectFields noted: $selectFields"
            )
            // Gmail threads.get fetches all messages in a thread. maxResults isn't directly applicable here.
            // selectFields can inform what details to fetch per message if we refine internalFetchMessageDetails
            val response = httpClient.get("$BASE_URL/threads/$threadId") {
                accept(ContentType.Application.Json)
                // format=FULL is usually default for threads.get and includes messages with their payloads
                // If specific fields were needed, one might fetch metadata first, then individual messages.
                // parameter("format", "FULL") // Could be explicit
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error fetching thread $threadId: ${response.status} - $errorBody")
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                return Result.failure(Exception(mappedDetails.message, httpEx))
            }
            
            val gmailThread = response.body<GmailThread>()

            // The messages within GmailThread are already somewhat populated.
            // We could pass selectFields to mapGmailMessageToMessage if it's adapted to use them for richer mapping.
            val messages =
                gmailThread.messages?.mapNotNull { mapGmailMessageToMessage(it) } ?: emptyList()

            Log.d(
                TAG,
                "Successfully fetched and mapped ${messages.size} messages for thread ID: $threadId"
            )
            Result.success(messages)
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during getMessagesForThread (thread: $threadId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching messages for thread ID: $threadId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    // Added missing getMessageDetails
    override suspend fun getMessageDetails(messageId: String): Flow<Message?> = flow {
        Log.d(TAG, "getMessageDetails: Flow for messageId: $messageId")
        // Using internalFetchMessageDetails which already handles exceptions and returns Message?
        emit(internalFetchMessageDetails(messageId))
    }

    // Added missing markThreadRead
    override suspend fun markThreadRead(threadId: String, isRead: Boolean): Result<Unit> {
        Log.d(TAG, "markThreadRead for thread $threadId to isRead=$isRead")
        // Gmail API does not have a direct "mark thread as read" endpoint.
        // This requires fetching all messages in the thread and marking them individually.
        try {
            val threadMessagesResult = getMessagesForThread(
                threadId = threadId,
                folderId = "",
                selectFields = listOf("id"),
                maxResults = 1000
            ) // folderId not critical, get all messages
            if (threadMessagesResult.isFailure) {
                Log.e(
                    TAG,
                    "Failed to get messages for thread $threadId to mark read. Error: ${threadMessagesResult.exceptionOrNull()?.message}"
                )
                return Result.failure(
                    threadMessagesResult.exceptionOrNull()
                        ?: Exception("Failed to get messages for thread $threadId")
                )
            }

            val messages = threadMessagesResult.getOrThrow() // This is List<Message> from core-data
            if (messages.isEmpty()) {
                Log.w(TAG, "No messages found in thread $threadId to mark as read=$isRead.")
                return Result.success(Unit)
            }

            var allSuccessful = true

            for (message in messages) { // message is of type net.melisma.core_data.model.Message
                // Check if action is even needed for this message based on its current isRead status
                val messageIsCurrentlyRead = message.isRead
                val actionRequired = (isRead != messageIsCurrentlyRead)

                if (actionRequired) {
                    val markResult = markMessageRead(
                        message.id,
                        isRead
                    ) // This API call internally handles Gmail labels
                    if (markResult.isFailure) {
                        allSuccessful = false
                        Log.e(
                            TAG,
                            "Failed to mark message ${message.id} in thread $threadId. Error: ${markResult.exceptionOrNull()?.message}"
                        )
                        // Optionally break or collect all errors
                    }
                } else {
                    Log.d(
                        TAG,
                        "Message ${message.id} in thread $threadId already in desired read state (isRead=$isRead). Skipping."
                    )
                }
            }

            return if (allSuccessful) {
                Log.d(
                    TAG,
                    "Successfully processed all messages in thread $threadId for isRead=$isRead"
                )
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark one or more messages in thread $threadId"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception in markThreadRead for $threadId", e)
            return Result.failure(
                errorMapper.mapExceptionToErrorDetails(e).let { Exception(it.message, e) })
        }
    }

    // Added missing deleteThread
    override suspend fun deleteThread(threadId: String): Result<Unit> {
        Log.d(TAG, "deleteThread for thread $threadId")
        // Gmail API: delete thread is POST /gmail/v1/users/me/threads/{threadId}/trash
        // This is simpler than Outlook's per-message deletion for threads.
        return try {
            val response: HttpResponse = httpClient.post("$BASE_URL/threads/$threadId/trash") {
                accept(ContentType.Application.Json)
                // No body needed
            }
            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully trashed thread $threadId")
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error trashing thread $threadId: ${response.status} - $errorBody")
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message, httpEx))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during deleteThread (thread: $threadId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteThread for $threadId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }

    // Added missing moveThread
    override suspend fun moveThread(
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        Log.d(
            TAG,
            "moveThread for thread $threadId from '$currentFolderId' to '$destinationFolderId'"
        )
        // Gmail API: modify labels for the entire thread.
        // POST /gmail/v1/users/me/threads/{threadId}/modify
        // Body: { "addLabelIds": ["labelIdToAdd"], "removeLabelIds": ["labelIdToRemove"] }
        return try {
            val addLabelIds = mutableListOf<String>()
            val removeLabelIds = mutableListOf<String>()

            if (destinationFolderId.equals("ARCHIVE", ignoreCase = true)) {
                if (currentFolderId.equals(
                        GMAIL_LABEL_ID_INBOX,
                        ignoreCase = true
                    ) || currentFolderId.isBlank()
                ) {
                    removeLabelIds.add(GMAIL_LABEL_ID_INBOX)
                }
                // Potentially remove currentFolderId if it's a user label being "archived"
                if (currentFolderId.isNotBlank() && !listOf(
                        GMAIL_LABEL_ID_INBOX,
                        GMAIL_LABEL_ID_SENT,
                        GMAIL_LABEL_ID_DRAFT,
                        GMAIL_LABEL_ID_SPAM,
                        GMAIL_LABEL_ID_TRASH
                    ).contains(currentFolderId.uppercase())
                ) {
                    // removeLabelIds.add(currentFolderId) // Example: if archiving from a user label, remove it
                }
            } else {
                addLabelIds.add(destinationFolderId)
                if (currentFolderId.equals(
                        GMAIL_LABEL_ID_INBOX,
                        ignoreCase = true
                    ) && !destinationFolderId.equals(GMAIL_LABEL_ID_INBOX, ignoreCase = true)
                ) {
                    removeLabelIds.add(GMAIL_LABEL_ID_INBOX)
                }
                // If moving from UserLabelA to UserLabelB, and UserLabelA is currentFolderId
                if (currentFolderId.isNotBlank() &&
                    !currentFolderId.equals(GMAIL_LABEL_ID_INBOX, ignoreCase = true) &&
                    !currentFolderId.equals(destinationFolderId, ignoreCase = true) &&
                    !destinationFolderId.equals("ARCHIVE", ignoreCase = true)
                ) {
                    // removeLabelIds.add(currentFolderId) // This would make it a "true" move from a user label
                }
            }

            // No need to fetch current labels for the thread for this API, as modify adds/removes regardless.
            // However, filtering out redundant operations based on known currentFolderId can be smart.
            // For simplicity, the API handles no-ops gracefully.

            if (addLabelIds.isEmpty() && removeLabelIds.isEmpty()) {
                Log.d(
                    TAG,
                    "No actual label changes required for thread $threadId to '$destinationFolderId' (from '$currentFolderId'). No-op."
                )
                return Result.success(Unit)
            }

            Log.d(
                TAG,
                "Thread $threadId: Modifying labels - Add: $addLabelIds, Remove: $removeLabelIds"
            )

            val requestBody = buildJsonObject {
                if (addLabelIds.isNotEmpty()) {
                    putJsonArray("addLabelIds") { addLabelIds.forEach { add(it) } }
                }
                if (removeLabelIds.isNotEmpty()) {
                    putJsonArray("removeLabelIds") { removeLabelIds.forEach { add(it) } }
                }
            }

            val response: HttpResponse = httpClient.post("$BASE_URL/threads/$threadId/modify") {
                accept(ContentType.Application.Json)
                setBody(requestBody)
            }

            if (response.status.isSuccess()) {
                Log.d(
                    TAG,
                    "Successfully modified labels for thread $threadId (moved to '$destinationFolderId')"
                )
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error modifying labels for thread $threadId: ${response.status} - $errorBody"
                )
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message, httpEx))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during moveThread (thread: $threadId).",
                e
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in moveThread for $threadId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message, e))
        }
    }
}