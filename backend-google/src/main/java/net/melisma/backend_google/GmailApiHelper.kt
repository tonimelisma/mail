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
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.repository.AccountRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GmailApiHelper @Inject constructor(
    @GoogleHttpClient private val httpClient: HttpClient,
    private val errorMapper: ErrorMapperService,
    private val accountRepository: AccountRepository
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

    override suspend fun getMailFolders(): Result<List<MailFolder>> {
        return try {
            Log.d(TAG, "Fetching Gmail labels from API...")
            val response: HttpResponse = httpClient.get("$BASE_URL/labels")
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Log.e(
                    TAG,
                    "Error fetching labels: ${response.status} - Error details in API response."
                )
                val httpEx =
                    io.ktor.client.plugins.ClientRequestException(response, responseBodyText)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                return Result.failure(Exception(mappedDetails.message))
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
            try {
                accountRepository.markAccountForReauthentication(
                    e.accountId,
                    Account.PROVIDER_TYPE_GOOGLE
                )
            } catch (markEx: Exception) {
                Log.e(TAG, "Failed to mark account ${e.accountId} for re-authentication", markEx)
            }
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching mail folders", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message))
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
        selectFields: List<String>, // This 'selectFields' is not used by Gmail API in this way for messages.
        // The equivalent is metadataHeaders in fetchMessageDetails.
        maxResults: Int
    ): Result<List<Message>> {
        return try {
            Log.d(TAG, "getMessagesForFolder: Fetching message IDs for folderId: $folderId")
            val messageIdsResponse = httpClient.get("$BASE_URL/messages") {
                parameter("maxResults", maxResults)
                parameter("labelIds", folderId)
                // Consider adding `q` parameter for filtering if needed in future, e.g., unread messages
            }
            if (!messageIdsResponse.status.isSuccess()) {
                val errorBody = messageIdsResponse.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message list for $folderId: ${messageIdsResponse.status} - Error details in API response."
                )
                val httpEx =
                    io.ktor.client.plugins.ClientRequestException(messageIdsResponse, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                return Result.failure(Exception(mappedDetails.message))
            }

            val messageList = messageIdsResponse.body<GmailMessageList>()
            if (messageList.messages.isEmpty()) {
                Log.d(TAG, "No messages found for folder (label): $folderId")
                return Result.success(emptyList())
            }
            Log.d(TAG, "Fetched ${messageList.messages.size} message IDs. Now fetching details.")

            val messages = supervisorScope {
                messageList.messages.map { messageIdentifier ->
                    async { fetchMessageDetails(messageIdentifier.id) }
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
            try {
                accountRepository.markAccountForReauthentication(
                    e.accountId,
                    Account.PROVIDER_TYPE_GOOGLE
                )
            } catch (markEx: Exception) {
                Log.e(TAG, "Failed to mark account ${e.accountId} for re-authentication", markEx)
            }
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching messages for folder $folderId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message))
        }
    }

    private suspend fun fetchMessageDetails(messageId: String): Message? {
        Log.d(TAG, "fetchMessageDetails: Fetching details for messageId: $messageId")
        return try {
            val response: HttpResponse = httpClient.get("$BASE_URL/messages/$messageId") {
                parameter("format", "FULL")
                // parameter("metadataHeaders", "Subject,From,Date,To,Cc,Bcc") // Added To,Cc,Bcc for completeness
            }

            if (!response.status.isSuccess()) {
                Log.w(
                    TAG,
                    "Error fetching details for message $messageId: ${response.status} - Error details in API response."
                )
                return null
            }

            val rawJsonResponse = response.bodyAsText()

            val gmailMessage = jsonParser.decodeFromString<GmailMessage>(rawJsonResponse)

            Log.d(
                TAG,
                "Fetched (parsed) GmailMessage ID: ${gmailMessage.id}, Payload null? = ${gmailMessage.payload == null}, Top-level Headers count: ${gmailMessage.payload?.headers?.size}"
            )
            gmailMessage.payload?.headers?.let { hs ->
                Log.d(
                    TAG,
                    "Actual Top-Level Headers: ${hs.joinToString { h -> "${h.name}:${h.value}" }}"
                )
            }
            if (gmailMessage.payload?.parts?.isNotEmpty() == true) {
                Log.d(
                    TAG,
                    "Message ID: ${gmailMessage.id} has ${gmailMessage.payload.parts?.size} parts. First part headers count: ${gmailMessage.payload.parts?.firstOrNull()?.headers?.size}"
                )
            }


            mapGmailMessageToMessage(gmailMessage)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Exception in fetchMessageDetails for ID: $messageId (Could be network or deserialization)",
                e
            )
            null
        }
    }

    private fun mapGmailMessageToMessage(gmailMessage: GmailMessage): Message {
        Log.d(TAG, "mapGmailMessageToMessage: Mapping GmailMessage ID: ${gmailMessage.id}")

        var effectiveHeaders = gmailMessage.payload?.headers ?: emptyList()

        // Check if top-level headers are sufficient, otherwise search in parts
        val needsSearchInParts =
            effectiveHeaders.none { it.name.equals("Subject", ignoreCase = true) } ||
                    effectiveHeaders.none { it.name.equals("From", ignoreCase = true) } ||
                    effectiveHeaders.none { it.name.equals("Date", ignoreCase = true) }

        if (needsSearchInParts && gmailMessage.payload?.parts?.isNotEmpty() == true) {
            Log.d(
                TAG,
                "Message ID ${gmailMessage.id}: Top-level headers missing key info or empty (Count: ${effectiveHeaders.size}). Attempting to find headers in parts."
            )

            fun findHeadersRecursively(parts: List<MessagePart>?): List<MessagePartHeader> {
                parts?.forEach { part ->
                    Log.d(
                        TAG,
                        "Message ID ${gmailMessage.id}: Checking partId: ${part.partId}, mimeType: ${part.mimeType}, headersCount: ${part.headers?.size}"
                    )
                    part.headers?.let { currentPartHeaders ->
                        // If this part has Subject and From, consider it good enough.
                        // More specific logic could be added to prefer text/html over text/plain, or vice-versa.
                        if (currentPartHeaders.any {
                                it.name.equals(
                                    "Subject",
                                    ignoreCase = true
                                )
                            } &&
                            currentPartHeaders.any { it.name.equals("From", ignoreCase = true) }) {
                            Log.d(
                                TAG,
                                "Message ID ${gmailMessage.id}: Found suitable headers in partId: ${part.partId} (mimeType: ${part.mimeType})"
                            )
                            return currentPartHeaders
                        }
                    }
                    // If this part is also a multipart and has sub-parts, recurse
                    if (part.parts?.isNotEmpty() == true) {
                        val headersFromSubPart = findHeadersRecursively(part.parts)
                        if (headersFromSubPart.isNotEmpty() &&
                            headersFromSubPart.any {
                                it.name.equals(
                                    "Subject",
                                    ignoreCase = true
                                )
                            } &&
                            headersFromSubPart.any { it.name.equals("From", ignoreCase = true) }
                        ) { // Check if useful headers found
                            Log.d(
                                TAG,
                                "Message ID ${gmailMessage.id}: Found suitable headers in sub-part of partId: ${part.partId}"
                            )
                            return headersFromSubPart
                        }
                    }
                }
                Log.d(
                    TAG,
                    "Message ID ${gmailMessage.id}: No suitable headers found after checking all sub-parts of this branch."
                )
                return emptyList()
            }

            val headersFromParts = findHeadersRecursively(gmailMessage.payload.parts)
            if (headersFromParts.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Message ID ${gmailMessage.id}: Using headers found in parts (Count: ${headersFromParts.size})."
                )
                effectiveHeaders = headersFromParts
            } else {
                Log.d(
                    TAG,
                    "Message ID ${gmailMessage.id}: No suitable headers found in parts either. Using top-level (Count: ${effectiveHeaders.size})."
                )
            }
        } else if (needsSearchInParts) {
            Log.d(
                TAG,
                "Message ID ${gmailMessage.id}: Top-level headers missing key info (Count: ${effectiveHeaders.size}), but no parts to search."
            )
        } else {
            Log.d(
                TAG,
                "Message ID ${gmailMessage.id}: Using top-level headers (Count: ${effectiveHeaders.size})."
            )
        }


        val subject = effectiveHeaders.findHeaderValue("Subject") ?: "(No Subject)"
        val from = effectiveHeaders.findHeaderValue("From") ?: ""
        val dateStr = effectiveHeaders.findHeaderValue("Date")

        // Fallback for date if not found, using internalDate as a last resort
        val date = dateStr?.let { parseEmailDate(it) }
            ?: gmailMessage.internalDate?.toLongOrNull()?.let { Date(it) }
            ?: Date() // Absolute fallback to now

        val senderName = extractSenderName(from)
        val senderAddress = extractSenderAddress(from)

        val outputDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val formattedDate = outputDateFormat.format(date)

        Log.d(
            TAG,
            "To App Message ID: ${gmailMessage.id}, Subject: '$subject', SenderName: '$senderName', SenderAddress: '$senderAddress', Date: '$formattedDate', Preview: '${gmailMessage.snippet ?: ""}'"
        )

        return Message(
            id = gmailMessage.id,
            threadId = gmailMessage.threadId,
            subject = subject,
            receivedDateTime = formattedDate,
            senderName = senderName,
            senderAddress = senderAddress,
            bodyPreview = gmailMessage.snippet ?: "",
            isRead = !gmailMessage.labelIds.contains("UNREAD") // Assuming UNREAD is the standard label
        )
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
    override suspend fun markMessageRead(messageId: String, isRead: Boolean): Result<Boolean> {
        Log.d(TAG, "markMessageRead: messageId='$messageId', isRead=$isRead")
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
                Log.e(
                    TAG,
                    "Error marking message read/unread for $messageId: ${response.status} - Error details in API response."
                )
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during markMessageRead (message: $messageId).",
                e
            )
            try {
                accountRepository.markAccountForReauthentication(
                    e.accountId,
                    Account.PROVIDER_TYPE_GOOGLE
                )
            } catch (markEx: Exception) {
                Log.e(TAG, "Failed to mark account ${e.accountId} for re-authentication", markEx)
            }
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in markMessageRead for $messageId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message))
        }
    }

    override suspend fun deleteMessage(messageId: String): Result<Boolean> {
        Log.d(TAG, "deleteMessage: messageId='$messageId'")
        return try {
            val endpoint = "$BASE_URL/messages/$messageId/trash" // Correct endpoint for trashing
            val response: HttpResponse = httpClient.post(endpoint) // No body needed for trash
            if (response.status.isSuccess()) Result.success(true)
            else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error deleting (trashing) message $messageId: ${response.status} - Error details in API response."
                )
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during deleteMessage (message: $messageId).",
                e
            )
            try {
                accountRepository.markAccountForReauthentication(
                    e.accountId,
                    Account.PROVIDER_TYPE_GOOGLE
                )
            } catch (markEx: Exception) {
                Log.e(TAG, "Failed to mark account ${e.accountId} for re-authentication", markEx)
            }
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteMessage for $messageId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message))
        }
    }

    override suspend fun moveMessage(messageId: String, targetFolderId: String): Result<Boolean> {
        Log.d(TAG, "moveMessage: messageId='$messageId', targetFolderId='$targetFolderId'")
        return try {
            // First, get the current labels of the message
            val messageDetailsResponse = httpClient.get("$BASE_URL/messages/$messageId") {
                parameter("format", "metadata")
                parameter(
                    "metadataHeaders",
                    " "
                ) // We only need labelIds, but metadataHeaders is required by API for metadata format.
                // Sending a space or a common header like 'Subject' should be fine.
            }

            if (!messageDetailsResponse.status.isSuccess()) {
                val errorBody = messageDetailsResponse.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message details for move operation ($messageId): ${messageDetailsResponse.status} - Error details in API response."
                )
                return Result.failure(Exception("Failed to get message details before move: ${errorBody}"))
            }
            val gmailMessage = messageDetailsResponse.body<GmailMessage>()
            val currentLabelIds = gmailMessage.labelIds.toMutableList()
            Log.d(TAG, "Current labels for message $messageId: $currentLabelIds")


            val addLabelIds = mutableListOf<String>()
            val removeLabelIds = mutableListOf<String>()

            if (targetFolderId.equals("ARCHIVE", ignoreCase = true)) {
                // Archiving means removing INBOX (if present) and not adding any specific folder label unless it's already there
                if (currentLabelIds.contains("INBOX")) {
                    removeLabelIds.add("INBOX")
                }
                Log.d(TAG, "Archiving message $messageId. Will remove INBOX if present.")
            } else {
                // Moving to a specific folder/label
                addLabelIds.add(targetFolderId)
                // If it was in INBOX and not being moved to INBOX, remove INBOX.
                // Also, remove other 'folder-like' labels if it's a true move, not just adding a label.
                // This logic might need refinement based on how Gmail handles multiple labels vs folders.
                // For simplicity, if moving to a new folder, we often want to remove it from the old one (e.g. INBOX).
                if (currentLabelIds.contains("INBOX") && !targetFolderId.equals(
                        "INBOX",
                        ignoreCase = true
                    )
                ) {
                    removeLabelIds.add("INBOX")
                }
                // Potentially remove other user labels if this is an exclusive move. For now, just INBOX.
            }


            // Ensure no redundant operations
            val finalAddLabels = addLabelIds.filter { it !in currentLabelIds }.distinct()
            val finalRemoveLabels = removeLabelIds.filter { it in currentLabelIds }.distinct()

            if (finalAddLabels.isEmpty() && finalRemoveLabels.isEmpty()) {
                Log.d(
                    TAG,
                    "No actual label changes required for message $messageId to $targetFolderId. Already in desired state."
                )
                return Result.success(true) // No change needed
            }

            Log.d(
                TAG,
                "Message $messageId: Adding labels: $finalAddLabels, Removing labels: $finalRemoveLabels"
            )


            val endpoint = "$BASE_URL/messages/$messageId/modify"
            val requestBodyJson = buildJsonObject {
                if (finalAddLabels.isNotEmpty()) {
                    putJsonObject("addLabelIds") {
                        finalAddLabels.forEachIndexed { i, id ->
                            put(
                                i.toString(),
                                id
                            )
                        } // Using index as key for JSON array elements
                    }
                }
                if (finalRemoveLabels.isNotEmpty()) {
                    putJsonObject("removeLabelIds") {
                        finalRemoveLabels.forEachIndexed { i, id -> put(i.toString(), id) }
                    }
                }
            }


            val response: HttpResponse = httpClient.post(endpoint) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                setBody(requestBodyJson.toString())
            }

            if (response.status.isSuccess()) {
                Log.i(
                    TAG,
                    "Successfully moved/modified labels for message $messageId to $targetFolderId"
                )
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error moving message $messageId (modifying labels): ${response.status} - Error details in API response."
                )
                val httpEx = io.ktor.client.plugins.ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(Exception(mappedDetails.message))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Log.w(
                TAG,
                "Google account ${e.accountId} needs re-authentication during moveMessage (message: $messageId).",
                e
            )
            try {
                accountRepository.markAccountForReauthentication(
                    e.accountId,
                    Account.PROVIDER_TYPE_GOOGLE
                )
            } catch (markEx: Exception) {
                Log.e(TAG, "Failed to mark account ${e.accountId} for re-authentication", markEx)
            }
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in moveMessage for $messageId to $targetFolderId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message))
        }
    }

    override suspend fun getMessagesForThread(
        threadId: String,
        folderId: String // Added folderId to match interface, though Gmail API doesn't use it for threads.get
    ): Result<List<Message>> {
        return try {
            Log.d(TAG, "Fetching messages for thread ID: $threadId (original folder: $folderId)")
            // The 'folderId' is less relevant for Gmail's threads.get API but kept for interface consistency.
            val response = httpClient.get("$BASE_URL/threads/$threadId") {
                // You might want to control the format (e.g., "full", "metadata") if needed
                // parameter("format", "full") // "full" is often default and includes payload
            }
            val gmailThread = response.body<GmailThread>()

            val messages = gmailThread.messages.map { mapGmailMessageToMessage(it) }
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
            try {
                accountRepository.markAccountForReauthentication(
                    e.accountId,
                    Account.PROVIDER_TYPE_GOOGLE
                )
            } catch (markEx: Exception) {
                Log.e(TAG, "Failed to mark account ${e.accountId} for re-authentication", markEx)
            }
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception("Re-authentication required: ${mappedDetails.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching messages for thread ID: $threadId", e)
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(Exception(mappedDetails.message))
        }
    }
}