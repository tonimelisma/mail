package net.melisma.backend_google

import android.annotation.SuppressLint
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleNeedsReauthenticationException
import net.melisma.backend_google.di.GoogleHttpClient
import net.melisma.backend_google.model.GmailAttachmentResponse
import net.melisma.backend_google.model.GmailDraftResponse
import net.melisma.backend_google.model.GmailLabel
import net.melisma.backend_google.model.GmailLabelList
import net.melisma.backend_google.model.GmailMessage
import net.melisma.backend_google.model.GmailMessageList
import net.melisma.backend_google.model.GmailThread
import net.melisma.backend_google.model.MessagePart
import net.melisma.backend_google.model.MessagePartHeader
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.DeltaSyncResult
import net.melisma.core_data.model.EmailAddress
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.PagedMessagesResponse
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.model.fromApi
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

// Data classes for Gmail Profile and History API
@kotlinx.serialization.Serializable
data class GmailProfile(
    val emailAddress: String,
    val messagesTotal: Int,
    val threadsTotal: Int,
    val historyId: String
)

@kotlinx.serialization.Serializable
data class GmailHistoryResponse(
    val history: List<GmailHistoryEntry>? = null,
    @SerialName("nextPageToken") val nextPageToken: String? = null,
    val historyId: String // The history ID of the EOD for this batch of history.
)

@kotlinx.serialization.Serializable
data class GmailHistoryEntry(
    val id: String, // History record ID
    // "messages": Messages that were affected by this history record.
    // The GmailMessage here might be sparsely populated (e.g. id, threadId, labelIds only)
    // unless format=FULL was used in history.list (default is not FULL).
    val messages: List<GmailMessage>? = null,
    val messagesAdded: List<GmailMessageContainer>? = null,
    val messagesDeleted: List<GmailMessageContainer>? = null,
    val labelsAdded: List<GmailLabelEvent>? = null,
    val labelsRemoved: List<GmailLabelEvent>? = null
)

// Container for messages in history events (messagesAdded, messagesDeleted).
// The GmailMessage inside typically only has id, threadId, and labelIds from history API.
@kotlinx.serialization.Serializable
data class GmailMessageContainer(
    val message: GmailMessage
)

// For labelsAdded/labelsRemoved events in history.
@kotlinx.serialization.Serializable
data class GmailLabelEvent(
    // The message object here usually only contains id and threadId.
    val message: GmailMessageIdOnly,
    val labelIds: List<String>
)

// Represents a message with only an ID and threadId, as often found in history label events.
@kotlinx.serialization.Serializable
data class GmailMessageIdOnly(
    val id: String,
    val threadId: String? = null
)

@Singleton
class GmailApiHelper @Inject constructor(
    @GoogleHttpClient private val httpClient: HttpClient,
    private val errorMapper: ErrorMapperService,
    private val ioDispatcher: CoroutineDispatcher,
    private val authManager: GoogleAuthManager
) : MailApiService {
    private val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

    companion object {
        private const val DEFAULT_MAX_RESULTS = 20L
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

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private suspend fun getCurrentAccountId(): String {
        return authManager.getNullableActiveAccountId()
            ?: throw IllegalStateException("Active account ID not found")
    }

    private suspend fun getCurrentGmailHistoryId(): String = withContext(ioDispatcher) {
        Timber.d("Fetching current Gmail history ID...")
        try {
            val response: HttpResponse = httpClient.get("$BASE_URL/profile") {
                accept(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) {
                val profile = jsonParser.decodeFromString<GmailProfile>(response.bodyAsText())
                Timber.d("Successfully fetched profile. History ID: ${profile.historyId}")
                profile.historyId
            } else {
                val errorBody = response.bodyAsText()
                Timber.e("Error fetching Gmail profile: ${response.status} - Body: $errorBody")
                // Consider a more specific exception or default/fallback behavior if appropriate
                throw ApiServiceException(
                    errorMapper.mapExceptionToErrorDetails(
                        ClientRequestException(response, errorBody)
                    )
                )
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(e, "Google account needs re-authentication during getCurrentGmailHistoryId.")
            throw e // Re-throw to be handled by the caller
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching Gmail profile history ID")
            throw ApiServiceException(errorMapper.mapExceptionToErrorDetails(e))
        }
    }

    private fun formatEmailAddressesRfc2822(addresses: List<EmailAddress>): String {
        return addresses.joinToString(", ") {
            val displayNameString = it.displayName
            if (displayNameString != null && displayNameString.isNotBlank()) {
                "\"${displayNameString.replace("\"", "\\\"")}\" <${it.emailAddress}>"
            } else {
                it.emailAddress
            }
        }
    }

    private fun formatEmailAddressesForJsonValue(addresses: List<EmailAddress>): String {
        return addresses.joinToString(", ") { it.emailAddress }
    }

    override suspend fun getMailFolders(
        activity: android.app.Activity?,
        accountId: String
    ): Result<List<MailFolder>> = withContext(ioDispatcher) {
        try {
            Timber.d("Fetching Gmail labels for accountId: $accountId from API...")
            val response: HttpResponse = httpClient.get("$BASE_URL/labels") {
                accept(ContentType.Application.Json)
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Timber.e("Error fetching labels: ${response.status} - Body: $responseBodyText")
                val httpEx =
                    ClientRequestException(response, responseBodyText)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            } else {
                val labelList = jsonParser.decodeFromString<GmailLabelList>(responseBodyText)
                Timber.d("Received ${labelList.labels.size} labels from API. Processing...")

                val mailFolders = labelList.labels
                    .mapNotNull { mapLabelToMailFolder(it) }
                    .sortedBy { it.displayName }

                Timber.i(
                    "Successfully mapped and filtered to ${mailFolders.size} visible MailFolders."
                )
                Result.success(mailFolders)
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during getMailFolders."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching mail folders")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    private fun mapLabelToMailFolder(label: GmailLabel): MailFolder? {
        val labelIdUpper = label.id.uppercase()
        val labelNameUpper = label.name.uppercase()

        Timber.v(
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
            "UNREAD" -> {
                type = WellKnownFolderType.HIDDEN; determinedByTypeOrName = true
                Timber.w("Label ID is 'UNREAD'. Setting HIDDEN. Original Name: '${label.name}'")
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
                    Timber.d(
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
                Timber.i(
                    "Label '${label.name}' (ID: ${label.id}) is being hidden by Gmail's labelListVisibility/messageListVisibility settings ('${label.labelListVisibility}'/'${label.messageListVisibility}') as it's not an overridden essential folder."
                )
                return null
            }
            type =
                if (label.type == "system") WellKnownFolderType.OTHER else WellKnownFolderType.USER_CREATED
        } else if (!isEssentialSystemFolder && type != WellKnownFolderType.HIDDEN) {
            if (label.labelListVisibility == "labelHide" || label.messageListVisibility == "hide") {
                if (type == WellKnownFolderType.ARCHIVE) {
                    Timber.w(
                        "Label '${label.name}' mapped to ARCHIVE but Gmail suggests hiding. Showing it anyway."
                    )
                } else {
                    Timber.i(
                        "Label '${label.name}' (ID: ${label.id}) hidden by Gmail's visibility hint (was type $type)."
                    )
                    return null
                }
            }
        }

        if (type == WellKnownFolderType.HIDDEN) {
            Timber.i(
                "Label '${label.name}' (ID: ${label.id}) resulted in HIDDEN type. Filtering out."
            )
            return null
        }

        Timber.i(
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

    private suspend fun internalFetchMessageDetails(
        messageId: String,
        selectFields: List<String> = emptyList()
    ): Message? = withContext(ioDispatcher) {
        Timber.d(
            "internalFetchMessageDetails: Fetching details for messageId: $messageId. SelectFields (metadata hint): $selectFields"
        )
        val gmailMessage = fetchRawGmailMessage(messageId)
        gmailMessage?.let {
            val accountId = getCurrentAccountId()
            // Determine folderId: Use INBOX if present, else first non-special label, else INBOX fallback
            val determinedFolderId =
                it.labelIds?.find { labelId -> labelId == GMAIL_LABEL_ID_INBOX }
                    ?: it.labelIds?.firstOrNull { labelId ->
                        !listOf(
                            GMAIL_LABEL_ID_SENT,
                            GMAIL_ID_CHAT,
                            GMAIL_LABEL_ID_DRAFT,
                            GMAIL_LABEL_ID_TRASH,
                            GMAIL_LABEL_ID_SPAM,
                            GMAIL_LABEL_ID_STARRED,
                            GMAIL_LABEL_ID_IMPORTANT
                        ).contains(labelId.uppercase())
                    }
                    ?: GMAIL_LABEL_ID_INBOX // Default fallback
            mapGmailMessageToMessage(
                it,
                accountId,
                determinedFolderId,
                isForBodyContent = false
            ) // isForBodyContent might depend on selectFields
        }
    }

    private suspend fun fetchRawGmailMessage(messageId: String): GmailMessage? =
        withContext(ioDispatcher) {
        Timber.d("fetchRawGmailMessage: Fetching raw GmailMessage for id: $messageId")
            try {
            val response: HttpResponse = httpClient.get("$BASE_URL/messages/$messageId") {
                accept(ContentType.Application.Json)
                parameter("format", "FULL")
            }

            if (!response.status.isSuccess()) {
                Timber.w(
                    "Error fetching raw gmail message $messageId: ${response.status} - ${response.bodyAsText()}"
                )
                null
            } else {
                response.body<GmailMessage>()
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during fetchRawGmailMessage (message: $messageId)."
            )
                throw e
        } catch (e: Exception) {
            Timber.e(e, "Exception in fetchRawGmailMessage for ID: $messageId")
            null
        }
    }

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
            Timber.e(e, "Base64 decoding failed for body part")
            null
        }
    }

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
                    if (htmlBody == null) {
                        htmlBody = part.body.data?.let { decodeBase64(it) }
                        htmlContentType = "text/html"
                    }
                } else if (part.mimeType?.startsWith(
                        "text/plain",
                        ignoreCase = true
                    ) == true && part.body?.data != null
                ) {
                    if (textBody == null) {
                        textBody = part.body.data?.let { decodeBase64(it) }
                        textContentType = "text/plain"
                    }
                }

                if (part.mimeType?.startsWith(
                        "multipart/",
                        ignoreCase = true
                    ) == true && !part.parts.isNullOrEmpty()
                ) {
                    if (htmlBody == null || textBody == null) {
                        searchPartsRecursive(part.parts)
                    }
                }
                if (htmlBody != null && textBody != null) return@forEach
            }
        }

        searchPartsRecursive(partsToSearch)

        return if (htmlBody != null) {
            Pair(htmlBody, htmlContentType)
        } else if (textBody != null) {
            Pair(textBody, textContentType)
        } else {
            Pair(null, null)
        }
    }

    private suspend fun mapGmailMessageToMessage(
        gmailMessage: GmailMessage,
        accountId: String,
        folderIdToAssign: String,
        isForBodyContent: Boolean = false
    ): Message? = withContext(ioDispatcher) {
        Timber.v(
            "mapGmailMessageToMessage for Gmail Message ID: ${gmailMessage.id}, accountId: $accountId, folderId: $folderIdToAssign, isForBodyContent: $isForBodyContent"
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
            var messageBodyContentType: String? = "text/plain"

            if (isForBodyContent && gmailMessage.payload != null) {
                val payload = gmailMessage.payload!!
                var foundBody: String? = null
                var foundContentType: String? = null

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

                if (foundBody == null && !payload.parts.isNullOrEmpty()) {
                    val (bodyStr, contentTypeStr) = findBestBodyPart(payload.parts!!)
                    foundBody = bodyStr
                    foundContentType = contentTypeStr
                }

                messageBody = foundBody
                messageBodyContentType =
                    foundContentType ?: "text/plain"
            }

            val currentAttachments = extractAttachmentsFromMessage(gmailMessage.id!!, gmailMessage)

            Message.fromApi(
                id = gmailMessage.id
                    ?: throw IllegalStateException("Message ID cannot be null from Gmail message: $gmailMessage"),
                accountId = accountId,
                folderId = folderIdToAssign,
                threadId = threadId,
                receivedDateTime = receivedDateTimeString,
                sentDateTime = sentDateTimeString,
                subject = subject,
                senderName = senderName,
                senderAddress = senderAddress,
                bodyPreview = if (isForBodyContent && !messageBody.isNullOrEmpty()) null else gmailMessage.snippet?.replace(
                    "\\u003e",
                    ">"
                )?.replace("\\u003c", "<")?.replace("&#39;", "'")?.trim(),
                isRead = isRead,
                body = messageBody,
                bodyContentType = messageBodyContentType,
                recipientNames = extractRecipientNames(gmailMessage),
                recipientAddresses = extractRecipientAddresses(gmailMessage),
                isStarred = gmailMessage.labelIds?.contains(GMAIL_LABEL_ID_STARRED) == true,
                hasAttachments = gmailMessage.payload?.parts?.any { !it.filename.isNullOrBlank() && !it.body?.attachmentId.isNullOrBlank() } == true,
                attachments = currentAttachments
            )
        } catch (e: Exception) {
            Timber.e(e, "Error mapping GmailMessage to Message: ${e.message}")
            null
        }
    }

    private fun extractSenderAddress(fromHeader: String): String {
        return if (fromHeader.contains("<") && fromHeader.contains(">")) {
            fromHeader.substringAfter("<").substringBefore(">").trim()
        } else {
            fromHeader.trim()
        }
    }

    private fun extractSenderName(fromHeader: String): String {
        return if (fromHeader.contains("<") && fromHeader.contains(">")) {
            fromHeader.substringBefore("<").trim().removeSurrounding("\"")
        } else {
            val trimmed = fromHeader.trim()
            if (trimmed.contains("@")) "" else trimmed
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
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                Locale.US
            )
        )
        for (formatter in formatters) {
            try {
                return formatter.parse(dateStr.trim())
            } catch (e: Exception) { /* Try next */
            }
        }
        Timber.w("Could not parse date string: '$dateStr' with known formats.")
        return null
    }

    override suspend fun markMessageRead(
        messageId: String,
        isRead: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        val action = if (isRead) "removeLabelIds" else "addLabelIds"
        val labels = listOf("UNREAD")
        modifyMessageLabels(messageId, action, labels)
    }

    override suspend fun starMessage(messageId: String, isStarred: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
        val action = if (isStarred) "addLabelIds" else "removeLabelIds"
        val labels = listOf(GMAIL_LABEL_ID_STARRED)
            modifyMessageLabels(messageId, action, labels)
    }

    override suspend fun getMessageContent(messageId: String): Result<Message> =
        withContext(ioDispatcher) {
        Timber.d("getMessageContent: Fetching full content for messageId $messageId")
            try {
                val accountId = getCurrentAccountId()
            val gmailMessage =
                fetchRawGmailMessage(messageId)
                    ?: return@withContext Result.failure(Exception("Failed to fetch raw message for $messageId"))

                // Determine folderId: Use INBOX if present, else first non-special label, else INBOX fallback
                val determinedFolderId =
                    gmailMessage.labelIds?.find { labelId -> labelId == GMAIL_LABEL_ID_INBOX }
                        ?: gmailMessage.labelIds?.firstOrNull { labelId ->
                            !listOf(
                                GMAIL_LABEL_ID_SENT,
                                GMAIL_ID_CHAT,
                                GMAIL_LABEL_ID_DRAFT,
                                GMAIL_LABEL_ID_TRASH,
                                GMAIL_LABEL_ID_SPAM,
                                GMAIL_LABEL_ID_STARRED,
                                GMAIL_LABEL_ID_IMPORTANT
                            ).contains(labelId.uppercase())
                        }
                        ?: GMAIL_LABEL_ID_INBOX // Default fallback

                val message = mapGmailMessageToMessage(
                    gmailMessage,
                    accountId,
                    determinedFolderId,
                    isForBodyContent = true
                )
                ?: return@withContext Result.failure(Exception("Failed to map Gmail message $messageId to domain model for body content"))

            Timber.i(
                "Successfully fetched and mapped content for message $messageId. Content type: ${message.bodyContentType}"
            )
            Result.success(message)

        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during getMessageContent (message: $messageId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in getMessageContent for $messageId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        }
    }

    private suspend fun modifyMessageLabels(
        messageId: String,
        action: String,
        labels: List<String>
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d(
            "modifyMessageLabels: messageId='$messageId', action='$action', labels=${
                labels.joinToString(", ")
            }"
        )
        try {
            val requestBody = buildJsonObject {
                putJsonArray(action) { labels.forEach { add(it) } }
            }
            val response: HttpResponse = httpClient.post("$BASE_URL/messages/$messageId/modify") {
                accept(ContentType.Application.Json)
                setBody(requestBody)
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Timber.e(
                    "Error modifying message labels for $messageId: ${response.status} - $errorBody"
                )
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during modifyMessageLabels (message: $messageId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in modifyMessageLabels for $messageId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
        withContext(ioDispatcher) {
        Timber.d("deleteMessage: messageId='$messageId'")
            try {
            val response: HttpResponse = httpClient.post("$BASE_URL/messages/$messageId/trash") {
                accept(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Timber.e(
                    "Error deleting (trashing) message $messageId: ${response.status} - $errorBody"
                )
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during deleteMessage (message: $messageId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in deleteMessage for $messageId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        }
    }

    override suspend fun moveMessage(
        messageId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d(
            "moveMessage: messageId='$messageId', from '$currentFolderId' to '$destinationFolderId'"
        )
        try {
            val addLabelIds = mutableListOf<String>()
            val removeLabelIds = mutableListOf<String>()

            if (destinationFolderId.equals("ARCHIVE", ignoreCase = true)) {
                removeLabelIds.add(GMAIL_LABEL_ID_INBOX)
                if (!currentFolderId.isNullOrBlank() &&
                    !currentFolderId.equals(GMAIL_LABEL_ID_INBOX, ignoreCase = true) &&
                    !isSystemLabel(currentFolderId) &&
                    !currentFolderId.equals("ARCHIVE", ignoreCase = true)
                ) {
                    removeLabelIds.add(currentFolderId)
                    Timber.d(
                        "Archiving: also removing current user label '$currentFolderId' for message $messageId"
                    )
                }
                Timber.d(
                    "Archiving message $messageId. Will remove INBOX and potentially current user label '$currentFolderId'."
                )
            } else {
                addLabelIds.add(destinationFolderId)

                if (!currentFolderId.isNullOrBlank() &&
                    currentFolderId != destinationFolderId &&
                    !currentFolderId.equals(
                        "ARCHIVE",
                        ignoreCase = true
                    ) &&
                    (currentFolderId.equals(
                        GMAIL_LABEL_ID_INBOX,
                        ignoreCase = true
                    ) || !isSystemLabel(currentFolderId))
                ) {
                    removeLabelIds.add(currentFolderId)
                    Timber.d(
                        "Moving from '$currentFolderId' to '$destinationFolderId'. Removing source label."
                    )
                }
            }

            val finalAddLabels = addLabelIds.distinct()
            val finalRemoveLabels = removeLabelIds.distinct()
                .filterNot { it in finalAddLabels }

            if (finalAddLabels.isEmpty() && finalRemoveLabels.isEmpty()) {
                Timber.i(
                    "Move operation for $messageId resulted in no label changes. Skipping API call."
                )
                return@withContext Result.success(Unit)
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
                Timber.i(
                    "Successfully moved/modified labels for message $messageId to '$destinationFolderId'"
                )
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Timber.e(
                    "Error moving message $messageId (modifying labels): ${response.status} - $errorBody"
                )
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during moveMessage (message: $messageId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in moveMessage for $messageId to $destinationFolderId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
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
        )
    }

    override suspend fun getMessagesForThread(
        threadId: String,
        folderId: String,
        selectFields: List<String>,
        maxResults: Int
    ): Result<List<Message>> = withContext(ioDispatcher) {
        try {
            Timber.d(
                "Fetching messages for thread ID: $threadId (original folder: $folderId, maxResults: $maxResults). selectFields noted: $selectFields"
            )
            val accountId = getCurrentAccountId()
            val response = httpClient.get("$BASE_URL/threads/$threadId") {
                accept(ContentType.Application.Json)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Timber.e("Error fetching thread $threadId: ${response.status} - $errorBody")
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            } else {
                val gmailThread = response.body<GmailThread>()

                val messages =
                    gmailThread.messages?.mapNotNull {
                        mapGmailMessageToMessage(
                            it,
                            accountId,
                            folderId
                        )
                    } ?: emptyList()

                Timber.d(
                    "Successfully fetched and mapped ${messages.size} messages for thread ID: $threadId"
                )
                Result.success(messages)
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during getMessagesForThread (thread: $threadId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Error fetching messages for thread ID: $threadId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    override suspend fun getMessageDetails(messageId: String): Flow<Message?> = flow {
        Timber.d("getMessageDetails: Flow for messageId: $messageId")
        emit(internalFetchMessageDetails(messageId))
    }

    override suspend fun markThreadRead(threadId: String, isRead: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
        Timber.d("markThreadRead for thread $threadId to isRead=$isRead")
        try {
            val threadMessagesResult = getMessagesForThread(
                threadId = threadId,
                folderId = "",
                selectFields = listOf("id"),
                maxResults = 1000
            )
            if (threadMessagesResult.isFailure) {
                Timber.e(
                    "Failed to get messages for thread $threadId to mark read. Error: ${threadMessagesResult.exceptionOrNull()?.message}"
                )
                return@withContext Result.failure(
                    threadMessagesResult.exceptionOrNull()
                        ?: Exception("Failed to get messages for thread $threadId")
                )
            }

            val messages = threadMessagesResult.getOrThrow()
            if (messages.isEmpty()) {
                Timber.w("No messages found in thread $threadId to mark as read=$isRead.")
                return@withContext Result.success(Unit)
            }

            var allSuccessful = true

            for (message in messages) {
                val messageIsCurrentlyRead = message.isRead
                val actionRequired = (isRead != messageIsCurrentlyRead)

                if (actionRequired) {
                    val markResult = markMessageRead(
                        message.id,
                        isRead
                    )
                    if (markResult.isFailure) {
                        allSuccessful = false
                        Timber.e(
                            "Failed to mark message ${message.id} in thread $threadId. Error: ${markResult.exceptionOrNull()?.message}"
                        )
                    }
                } else {
                    Timber.d(
                        "Message ${message.id} in thread $threadId already in desired read state (isRead=$isRead). Skipping."
                    )
                }
            }

            if (allSuccessful) {
                Timber.d(
                    "Successfully processed all messages in thread $threadId for isRead=$isRead"
                )
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark one or more messages in thread $threadId"))
            }

        } catch (e: Exception) {
            Timber.e(e, "Exception in markThreadRead for $threadId")
            Result.failure(
                errorMapper.mapExceptionToErrorDetails(e).let { Exception(it.message, e) })
        }
    }

    override suspend fun deleteThread(threadId: String): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("deleteThread for thread $threadId")
        try {
            val response: HttpResponse = httpClient.post("$BASE_URL/threads/$threadId/trash") {
                accept(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) {
                Timber.d("Successfully trashed thread $threadId")
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Timber.e("Error trashing thread $threadId: ${response.status} - $errorBody")
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during deleteThread (thread: $threadId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in deleteThread for $threadId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    override suspend fun moveThread(
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        Timber.d(
            "moveThread for thread $threadId from '$currentFolderId' to '$destinationFolderId'"
        )
        try {
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
                if (currentFolderId.isNotBlank() && !listOf(
                        GMAIL_LABEL_ID_INBOX,
                        GMAIL_LABEL_ID_SENT,
                        GMAIL_LABEL_ID_DRAFT,
                        GMAIL_LABEL_ID_SPAM,
                        GMAIL_LABEL_ID_TRASH
                    ).contains(currentFolderId.uppercase())
                ) {
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
                if (currentFolderId.isNotBlank() &&
                    !currentFolderId.equals(GMAIL_LABEL_ID_INBOX, ignoreCase = true) &&
                    !currentFolderId.equals(destinationFolderId, ignoreCase = true) &&
                    !destinationFolderId.equals("ARCHIVE", ignoreCase = true)
                ) {
                }
            }

            if (addLabelIds.isEmpty() && removeLabelIds.isEmpty()) {
                Timber.d(
                    "No actual label changes required for thread $threadId to '$destinationFolderId' (from '$currentFolderId'). No-op."
                )
                return@withContext Result.success(Unit)
            }

            Timber.d(
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
                Timber.d(
                    "Successfully modified labels for thread $threadId (moved to '$destinationFolderId')"
                )
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Timber.e(
                    "Error modifying labels for thread $threadId: ${response.status} - $errorBody"
                )
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during moveThread (thread: $threadId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in moveThread for $threadId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    override suspend fun getMessageAttachments(messageId: String): Result<List<net.melisma.core_data.model.Attachment>> =
        withContext(ioDispatcher) {
        Timber.d("getMessageAttachments: messageId='$messageId'")
            try {
            val response = httpClient.get("$BASE_URL/messages/$messageId") {
                accept(ContentType.Application.Json)
                parameter("format", "full")
            }

            if (response.status.isSuccess()) {
                val message = response.body<GmailMessage>()
                val attachments = extractAttachmentsFromMessage(message.id!!, message)
                Result.success(attachments)
            } else {
                val errorBody = response.bodyAsText().take(500)
                Timber.e(
                    "Failed to fetch message attachments for $messageId: ${response.status} - $errorBody"
                )
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during getMessageAttachments (message: $messageId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in getMessageAttachments for $messageId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        }
    }

    override suspend fun downloadAttachment(
        messageId: String,
        attachmentId: String
    ): Result<ByteArray> = withContext(ioDispatcher) {
        Timber.d("downloadAttachment: messageId='$messageId', attachmentId='$attachmentId'")
        try {
            val response =
                httpClient.get("$BASE_URL/messages/$messageId/attachments/$attachmentId") {
                    accept(ContentType.Application.Json)
                }

            if (response.status.isSuccess()) {
                val attachmentResponse = response.body<GmailAttachmentResponse>()
                val data = Base64.decode(
                    attachmentResponse.data.replace("-", "+").replace("_", "/"),
                    Base64.DEFAULT
                )
                Result.success(data)
            } else {
                val errorBody = response.bodyAsText().take(500)
                Timber.e(
                    "Failed to download attachment $attachmentId for message $messageId: ${response.status} - $errorBody"
                )
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during downloadAttachment (message: $messageId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in downloadAttachment for $messageId/$attachmentId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    @SuppressLint("SuspiciousIndentation")
    override suspend fun createDraftMessage(draft: net.melisma.core_data.model.MessageDraft): Result<Message> =
        withContext(ioDispatcher) {
        Timber.d("createDraftMessage: subject='${draft.subject}'")
            try {
                val accountId = getCurrentAccountId()
            val draftPayload = buildGmailDraftPayload(draft)
            val response = httpClient.post("$BASE_URL/drafts") {
                accept(ContentType.Application.Json)
                setBody(draftPayload)
            }

            if (response.status.isSuccess()) {
                val gmailDraft = response.body<GmailDraftResponse>()
                val message = convertGmailMessageToMessage(
                    gmailDraft.message,
                    accountId,
                    GMAIL_LABEL_ID_DRAFT
                )
                Result.success(message)
            } else {
                val errorBody = response.bodyAsText().take(500)
                Timber.e("Failed to create draft: ${response.status} - $errorBody")
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during createDraftMessage."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in createDraftMessage")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        }
    }

    @SuppressLint("SuspiciousIndentation")
    override suspend fun updateDraftMessage(
        messageId: String,
        draft: net.melisma.core_data.model.MessageDraft
    ): Result<Message> = withContext(ioDispatcher) {
        Timber.d("updateDraftMessage: messageId='$messageId', subject='${draft.subject}'")
        try {
            val accountId = getCurrentAccountId()
            val draftPayload = buildGmailDraftPayload(draft)
            val response = httpClient.patch("$BASE_URL/drafts/$messageId") {
                accept(ContentType.Application.Json)
                setBody(draftPayload)
            }

            if (response.status.isSuccess()) {
                val gmailDraft = response.body<GmailDraftResponse>()
                val message = convertGmailMessageToMessage(
                    gmailDraft.message,
                    accountId,
                    GMAIL_LABEL_ID_DRAFT
                )
                Result.success(message)
            } else {
                val errorBody = response.bodyAsText().take(500)
                Timber.e("Failed to update draft $messageId: ${response.status} - $errorBody")
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during updateDraftMessage (draft: $messageId)."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in updateDraftMessage for $messageId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    @SuppressLint("SuspiciousIndentation")
    override suspend fun sendMessage(draft: net.melisma.core_data.model.MessageDraft): Result<String> =
        withContext(ioDispatcher) {
        Timber.d("sendMessage: subject='${draft.subject}'")
            try {
                getCurrentAccountId()
            val messagePayload = buildGmailSendPayload(draft)
            val response = httpClient.post("$BASE_URL/messages/send") {
                accept(ContentType.Application.Json)
                setBody(messagePayload)
            }

            if (response.status.isSuccess()) {
                val sentMessage = response.body<GmailMessage>()
                Result.success(sentMessage.id ?: "")
            } else {
                val errorBody = response.bodyAsText().take(500)
                Timber.e("Failed to send message: ${response.status} - $errorBody")
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during sendMessage."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in sendMessage")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
        }
    }

    override suspend fun searchMessages(
        query: String,
        folderId: String?,
        maxResults: Int
    ): Result<List<Message>> = withContext(ioDispatcher) {
        Timber.d("searchMessages: query='$query', folderId='$folderId', maxResults=$maxResults")
        try {
            val accountId = getCurrentAccountId()
            val searchQuery = if (folderId != null) "in:$folderId $query" else query
            val response = httpClient.get("$BASE_URL/messages") {
                accept(ContentType.Application.Json)
                parameter("q", searchQuery)
                parameter("maxResults", maxResults)
            }

            if (response.status.isSuccess()) {
                val messageList = response.body<GmailMessageList>()
                val messages = messageList.messages?.mapNotNull { gmailMsg ->
                    gmailMsg.id?.let { messageId ->
                        runCatching {
                            val detailResponse = httpClient.get("$BASE_URL/messages/$messageId") {
                                accept(ContentType.Application.Json)
                                parameter("format", "metadata")
                            }
                            if (detailResponse.status.isSuccess()) {
                                val fullMessage = detailResponse.body<GmailMessage>()
                                val determinedFolderId = folderId
                                    ?: (fullMessage.labelIds?.find { it == GMAIL_LABEL_ID_INBOX }
                                        ?: fullMessage.labelIds?.firstOrNull { labelId ->
                                            !listOf(
                                                GMAIL_LABEL_ID_SENT,
                                                GMAIL_ID_CHAT,
                                                GMAIL_LABEL_ID_DRAFT,
                                                GMAIL_LABEL_ID_TRASH,
                                                GMAIL_LABEL_ID_SPAM,
                                                GMAIL_LABEL_ID_STARRED,
                                                GMAIL_LABEL_ID_IMPORTANT
                                            ).contains(labelId.uppercase())
                                        }
                                        ?: GMAIL_LABEL_ID_INBOX)
                                convertGmailMessageToMessage(
                                    fullMessage,
                                    accountId,
                                    determinedFolderId
                                )
                            } else null
                        }.getOrNull()
                    }
                } ?: emptyList()
                Result.success(messages)
            } else {
                val errorBody = response.bodyAsText().take(500)
                Timber.e("Failed to search messages: ${response.status} - $errorBody")
                val httpEx = ClientRequestException(response, errorBody)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                Result.failure(ApiServiceException(mappedDetails))
            }
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during searchMessages."
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: Exception) {
            Timber.e(e, "Exception in searchMessages")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    private fun extractAttachmentsFromMessage(
        parentMessageId: String,
        message: GmailMessage
    ): List<net.melisma.core_data.model.Attachment> {
        val attachments = mutableListOf<net.melisma.core_data.model.Attachment>()

        fun extractFromPart(part: MessagePart) {
            part.body?.attachmentId?.let { attachmentId ->
                attachments.add(
                    net.melisma.core_data.model.Attachment(
                        id = attachmentId,
                        messageId = parentMessageId,
                        fileName = part.filename ?: "attachment-${attachmentId}",
                        contentType = part.mimeType ?: "application/octet-stream",
                        size = part.body.size?.toLong() ?: 0L,
                        isInline = part.headers?.find {
                            it.name.equals(
                                "Content-Disposition",
                                ignoreCase = true
                            )
                        }?.value?.contains(
                            "inline", ignoreCase = true
                        ) == true,
                        contentId = part.headers?.find {
                            it.name.equals(
                                "Content-ID",
                                ignoreCase = true
                            )
                        }?.value?.removeSurrounding("<", ">"),
                        localUri = null,
                        downloadStatus = if (part.body.data != null && part.body.data.isNotEmpty()) "DOWNLOADED" else "NOT_DOWNLOADED",
                        lastSyncError = null
                    )
                )
            }
            part.parts?.forEach { extractFromPart(it) }
        }

        message.payload?.let { payload ->
            val rootPart = MessagePart(
                partId = null,
                mimeType = payload.mimeType,
                filename = null,
                headers = payload.headers,
                body = payload.body,
                parts = payload.parts
            )
            extractFromPart(rootPart)
        }
        return attachments
    }

    private fun buildGmailDraftPayload(draft: net.melisma.core_data.model.MessageDraft): Map<String, Any> {
        val headers = mutableListOf<Map<String, String>>()
        headers.add(mapOf("name" to "To", "value" to formatEmailAddressesForJsonValue(draft.to)))
        if (draft.cc.isNotEmpty()) {
            headers.add(
                mapOf(
                    "name" to "Cc",
                    "value" to formatEmailAddressesForJsonValue(draft.cc)
                )
            )
        }
        if (draft.bcc.isNotEmpty()) {
            headers.add(
                mapOf(
                    "name" to "Bcc",
                    "value" to formatEmailAddressesForJsonValue(draft.bcc)
                )
            )
        }
        draft.subject.let { headers.add(mapOf("name" to "Subject", "value" to it)) }

        // Determine content type, default to text/plain if body is simple, text/html if complex.
        // For simplicity, let's assume body is HTML if it contains '<' and '>', otherwise plain.
        // A more robust solution would be to have MessageDraft specify bodyContentType.
        val bodyContentType =
            if (draft.body.contains("<") && draft.body.contains(">")) "text/html" else "text/plain"

        // Basic structure for a single part message. Attachments would require multipart.
        // Gmail API for drafts is a bit tricky with attachments directly in this structure.
        // Often, attachments are uploaded separately or message is sent as raw.
        // For now, focusing on body.
        val messagePayload = mutableMapOf<String, Any>(
            "payload" to mapOf(
                "mimeType" to bodyContentType,
                "headers" to headers,
                "body" to mapOf(
                    "data" to Base64.encodeToString(
                        draft.body.toByteArray(Charsets.UTF_8),
                        Base64.URL_SAFE or Base64.NO_WRAP
                    )
                )
            )
        )
        // If draft.existingMessageId is present, Gmail expects 'id' for the draft and 'message.id' for the message being updated.
        // However, the current MessageDraft.existingMessageId is the *message* id, not draft id.
        // The API path /drafts/{draftId} uses the draftId.
        // If this is for updating a message *within* a draft, the structure is { "message": { "raw": "...", "id": "messageId" } }
        // The current call to client.patch already has draftId in URL.
        // The payload here is for the "message" object within the draft.

        return mapOf("message" to messagePayload)
    }

    private fun buildGmailSendPayload(draft: net.melisma.core_data.model.MessageDraft): Map<String, Any> {
        val rawMessage = buildRfc2822Message(draft)
        return mapOf(
            "raw" to Base64.encodeToString(
                rawMessage.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP
            )
        )
    }

    private fun buildRfc2822Message(draft: net.melisma.core_data.model.MessageDraft): String {
        val message = StringBuilder()
        // Standard headers
        message.append(
            "Date: ${
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(
                    Date()
                )
            }\r\n"
        ) // Add Date header
        message.append("MIME-Version: 1.0\r\n") // Standard MIME header

        message.append("To: ${formatEmailAddressesRfc2822(draft.to)}\r\n")
        if (draft.cc.isNotEmpty()) message.append("Cc: ${formatEmailAddressesRfc2822(draft.cc)}\r\n")
        if (draft.bcc.isNotEmpty()) message.append("Bcc: ${formatEmailAddressesRfc2822(draft.bcc)}\r\n")
        // From header will be set by Gmail based on authenticated user.
        // draft.from is not part of MessageDraft, which is correct.
        message.append("Subject: ${draft.subject}\r\n") // Ensure subject is not null, MessageDraft defaults to ""

        // For simplicity, assuming body is HTML. A more robust solution would check draft.bodyContentType
        // This simplified version doesn't handle attachments via raw RFC2822.
        // That would require a multipart/mixed structure.
        message.append("Content-Type: text/html; charset=utf-8\r\n")
        message.append("Content-Transfer-Encoding: base64\r\n") // Gmail often expects body to be base64 encoded for raw.
        message.append("\r\n")
        message.append(
            Base64.encodeToString(
                draft.body.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        ) // Encode body to Base64
        return message.toString()
    }

    private fun convertGmailMessageToMessage(
        gmailMessage: GmailMessage,
        accountId: String,
        folderIdToAssign: String
    ): Message {
        val timestamp = gmailMessage.internalDate?.toLongOrNull() ?: System.currentTimeMillis()
        val receivedDate = java.time.Instant.ofEpochMilli(timestamp).toString()
        val sentDate = java.time.Instant.ofEpochMilli(timestamp).toString()

        val currentAttachments = extractAttachmentsFromMessage(gmailMessage.id!!, gmailMessage)

        return Message.fromApi(
            id = gmailMessage.id ?: throw IllegalStateException("Gmail message ID is null"),
            accountId = accountId,
            folderId = folderIdToAssign,
            threadId = gmailMessage.threadId,
            receivedDateTime = receivedDate,
            sentDateTime = sentDate,
            subject = gmailMessage.payload?.headers?.find { it.name == "Subject" }?.value,
            senderName = extractSenderName(gmailMessage),
            senderAddress = extractSenderAddress(gmailMessage),
            bodyPreview = gmailMessage.snippet,
            isRead = gmailMessage.labelIds?.contains("UNREAD") != true,
            body = null,
            bodyContentType = null,
            recipientNames = extractRecipientNames(gmailMessage),
            recipientAddresses = extractRecipientAddresses(gmailMessage),
            isStarred = gmailMessage.labelIds?.contains(GMAIL_LABEL_ID_STARRED) == true,
            hasAttachments = gmailMessage.payload?.parts?.any { !it.filename.isNullOrBlank() && !it.body?.attachmentId.isNullOrBlank() } == true,
            attachments = currentAttachments
        )
    }

    private fun extractSenderName(message: GmailMessage): String? {
        return message.payload?.headers?.find { it.name == "From" }?.value?.let { from ->
            if (from.contains("<")) from.substringBefore("<").trim()
                .removeSurrounding("\"") else from
        }
    }

    private fun extractSenderAddress(message: GmailMessage): String? {
        return message.payload?.headers?.find { it.name == "From" }?.value?.let { from ->
            if (from.contains("<")) from.substringAfter("<").substringBefore(">") else from
        }
    }

    private fun extractRecipientNames(message: GmailMessage): List<String> {
        return message.payload?.headers?.find { it.name == "To" }?.value?.split(",")
            ?.map { recipient ->
                recipient.trim().let { trimmed ->
                    if (trimmed.contains("<")) trimmed.substringBefore("<").trim()
                        .removeSurrounding("\"") else trimmed
                }
            } ?: emptyList()
    }

    private fun extractRecipientAddresses(message: GmailMessage): List<String> {
        return message.payload?.headers?.find { it.name == "To" }?.value?.split(",")
            ?.map { recipient ->
                recipient.trim().let { trimmed ->
                    if (trimmed.contains("<")) trimmed.substringAfter("<")
                        .substringBefore(">") else trimmed
                }
            } ?: emptyList()
    }

    override suspend fun getMessagesForFolder(
        folderId: String,
        activity: android.app.Activity?,
        maxResults: Int?,
        pageToken: String?,
        earliestTimestampEpochMillis: Long? // New parameter
    ): Result<PagedMessagesResponse> = withContext(ioDispatcher) {
        Timber.d("getMessagesForFolder: folderId='$folderId', maxResults=$maxResults, pageToken='$pageToken', earliestTimestampEpochMillis=$earliestTimestampEpochMillis")
        try {
            val accountId = getCurrentAccountId()
            val actualMaxResults = maxResults ?: DEFAULT_MAX_RESULTS.toInt()

            val listResponse: HttpResponse = httpClient.get("$BASE_URL/messages") {
                accept(ContentType.Application.Json)
                parameter("labelIds", folderId)
                parameter("maxResults", actualMaxResults)
                if (pageToken != null) {
                    parameter("pageToken", pageToken)
                }
                if (earliestTimestampEpochMillis != null && earliestTimestampEpochMillis > 0 && pageToken == null) { // Apply only on the first page request
                    val timestampSeconds = earliestTimestampEpochMillis / 1000
                    // Ensure 'q' parameter doesn't conflict with other potential uses if any in future.
                    // Current use is solely for this date filter.
                    parameter("q", "after:$timestampSeconds")
                    Timber.d("Applying date filter to Gmail messages.list: q=after:$timestampSeconds")
                }
            }

            if (!listResponse.status.isSuccess()) {
                val errorBody = listResponse.bodyAsText()
                Timber.e("getMessagesForFolder: Error listing messages: ${listResponse.status} - Body: $errorBody")
                val httpEx = ClientRequestException(listResponse, errorBody)
                return@withContext Result.failure(
                    ApiServiceException(
                        errorMapper.mapExceptionToErrorDetails(
                            httpEx
                        )
                    )
                )
            }

            val gmailMessageList =
                jsonParser.decodeFromString<GmailMessageList>(listResponse.bodyAsText())
            val messageIds = gmailMessageList.messages?.mapNotNull { it.id } ?: emptyList()
            val nextPageTokenFromList = gmailMessageList.nextPageToken

            Timber.d("getMessagesForFolder: Listed ${messageIds.size} message IDs. NextPageToken: '$nextPageTokenFromList'")

            if (messageIds.isEmpty()) {
                return@withContext Result.success(
                    PagedMessagesResponse(
                        emptyList(),
                        nextPageTokenFromList
                    )
                )
            }

            val detailedMessages = supervisorScope {
                messageIds.map { messageId ->
                    async {
                        try {
                            val rawGmailMessage = fetchRawGmailMessage(messageId)
                            rawGmailMessage?.let {
                                mapGmailMessageToMessage(
                                    it,
                                    accountId,
                                    folderId,
                                    isForBodyContent = false
                                )
                            }
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "getMessagesForFolder: Failed to fetch or map messageId $messageId"
                            )
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            Timber.i("getMessagesForFolder: Successfully fetched and mapped ${detailedMessages.size} out of ${messageIds.size} messages for folder '$folderId'.")
            Result.success(
                PagedMessagesResponse(
                    messages = detailedMessages,
                    nextPageToken = nextPageTokenFromList
                )
            )

        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "getMessagesForFolder: Google account ${e.accountId} needs re-authentication."
            )
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        } catch (e: Exception) {
            Timber.e(e, "getMessagesForFolder: Exception for folderId '$folderId'")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    // Stub implementation for new delta sync method for folders
    override suspend fun syncFolders(
        accountId: String,
        syncToken: String? // This is the startHistoryId for delta, or null for initial sync
    ): Result<DeltaSyncResult<MailFolder>> = withContext(ioDispatcher) {
        Timber.i("syncFolders called for account $accountId. SyncToken (startHistoryId): $syncToken")
        try {
            // Regardless of initial or delta, we fetch the current complete list of folders.
            // The worker is responsible for diffing and detecting actual changes/deletions.
            val foldersResult = getMailFolders(activity = null, accountId = accountId)

            if (foldersResult.isFailure) {
                Timber.e(
                    foldersResult.exceptionOrNull(),
                    "syncFolders: Failed to get mail folders."
                )
                return@withContext Result.failure(
                    foldersResult.exceptionOrNull() ?: ApiServiceException(
                        errorMapper.mapExceptionToErrorDetails(Exception("Unknown error fetching folders during syncFolders"))
                    )
                )
            }

            val currentFolders = foldersResult.getOrThrow()
            val latestHistoryId =
                getCurrentGmailHistoryId() // Get the latest historyId as the next sync token

            Timber.i("syncFolders: Successfully fetched ${currentFolders.size} folders. Next historyId: $latestHistoryId")
            Result.success(
                DeltaSyncResult(
                    newOrUpdatedItems = currentFolders,
                    deletedItemIds = emptyList(), // Gmail API for labels doesn't directly give deleted IDs list. Worker will diff.
                    nextSyncToken = latestHistoryId
                )
            )
        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(e, "Google account $accountId needs re-authentication during syncFolders.")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: ApiServiceException) {
            Timber.e(e, "ApiServiceException during syncFolders for account $accountId")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Generic exception during syncFolders for account $accountId")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    // Stub implementation for new delta sync method for messages
    override suspend fun syncMessagesForFolder(
        folderId: String,
        syncToken: String?, // This is the startHistoryId for delta, or null to get a fresh historyId for future deltas
        maxResultsFromInterface: Int?, // Hint, may be used for internal API call limits if applicable
        earliestTimestampEpochMillis: Long? // New parameter
    ): Result<DeltaSyncResult<Message>> = withContext(ioDispatcher) {
        Timber.i("syncMessagesForFolder called for folderId: $folderId. SyncToken (startHistoryId): $syncToken, earliestTimestamp: $earliestTimestampEpochMillis")
        if (earliestTimestampEpochMillis != null) {
            Timber.w("syncMessagesForFolder (Gmail): earliestTimestampEpochMillis ($earliestTimestampEpochMillis) is provided but currently unused by the history-based delta sync logic.")
        }
        var localAccountIdForLogging: String? = null // For logging in catch blocks
        try {
            val accountId = getCurrentAccountId()
            localAccountIdForLogging = accountId

            if (syncToken == null) {
                Timber.d("syncMessagesForFolder: syncToken is null. Fetching current historyId as nextSyncToken for future delta syncs.")
                val currentHistoryId = getCurrentGmailHistoryId()
                return@withContext Result.success(
                    DeltaSyncResult(
                        newOrUpdatedItems = emptyList(),
                        deletedItemIds = emptyList(),
                        nextSyncToken = currentHistoryId
                    )
                )
            }

            // Actual delta sync using provided startHistoryId (syncToken)
            val messageIdsToFetchDetailsFor = mutableSetOf<String>()
            val deletedMessageIdsCollector = mutableSetOf<String>()

            var pageTokenForHistoryInternal: String? = null
            var nextSyncTokenToReturnUltimately =
                syncToken // Initialize with current token, will be updated by the last page's historyId

            Timber.d("Starting delta sync for folder $folderId from historyId: $syncToken")

            do {
                val response: HttpResponse = httpClient.get("$BASE_URL/history") {
                    accept(ContentType.Application.Json)
                    parameter(
                        "startHistoryId",
                        syncToken
                    ) // The initial startHistoryId for the window remains constant
                    parameter("labelId", folderId)
                    // Specify historyTypes to get only relevant events and potentially more detailed messages in messagesAdded
                    parameter("historyTypes", "messageAdded,messageDeleted,labelAdded,labelRemoved")
                    maxResultsFromInterface?.let {
                        parameter(
                            "maxResults",
                            it.coerceIn(1, 100)
                        )
                    } // Gmail history maxResults is 500, but use interface hint bounded.
                    pageTokenForHistoryInternal?.let { parameter("pageToken", it) }
                }

                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Timber.e("syncMessagesForFolder: Error fetching Gmail history: ${response.status} - Body: $errorBody")
                    throw ApiServiceException(
                        errorMapper.mapExceptionToErrorDetails(
                            ClientRequestException(response, errorBody)
                        )
                    )
                }

                val historyResponse =
                    jsonParser.decodeFromString<GmailHistoryResponse>(response.bodyAsText())
                nextSyncTokenToReturnUltimately =
                    historyResponse.historyId // This is the historyId at the end of THIS page.

                Timber.v("Processing history page. HistoryId for this page end: ${historyResponse.historyId}, next page token: ${historyResponse.nextPageToken}")

                historyResponse.history?.forEach { historyEntry ->
                    // Messages added to the account AND having the folderId label
                    historyEntry.messagesAdded?.forEach { msgContainer ->
                        if (msgContainer.message.labelIds?.contains(folderId) == true) {
                            msgContainer.message.id?.let { messageIdsToFetchDetailsFor.add(it) }
                        }
                    }

                    // Messages deleted from the account (that had folderId label due to labelId filter in query)
                    historyEntry.messagesDeleted?.forEach { msgContainer ->
                        msgContainer.message.id?.let { deletedMessageIdsCollector.add(it) }
                    }

                    // Messages that had folderId label added
                    historyEntry.labelsAdded?.forEach { labelEvent ->
                        if (labelEvent.labelIds.contains(folderId)) {
                            messageIdsToFetchDetailsFor.add(labelEvent.message.id)
                        }
                    }

                    // Messages that had folderId label removed
                    historyEntry.labelsRemoved?.forEach { labelEvent ->
                        if (labelEvent.labelIds.contains(folderId)) {
                            deletedMessageIdsCollector.add(labelEvent.message.id)
                        }
                    }
                }
                pageTokenForHistoryInternal = historyResponse.nextPageToken
            } while (pageTokenForHistoryInternal != null)

            Timber.d("Finished processing history pages. Messages to fetch details for: ${messageIdsToFetchDetailsFor.size}, Deleted IDs collected: ${deletedMessageIdsCollector.size}")

            val newOrUpdatedMessageDetails = mutableListOf<Message>()
            if (messageIdsToFetchDetailsFor.isNotEmpty()) {
                supervisorScope {
                    val deferreds = messageIdsToFetchDetailsFor.map { msgId ->
                        async(ioDispatcher) {
                            try {
                                Timber.v("Fetching details for messageId $msgId for delta sync folder $folderId")
                                fetchRawGmailMessage(msgId)?.let {
                                    mapGmailMessageToMessage(
                                        it,
                                        accountId,
                                        folderId,
                                        isForBodyContent = false
                                    )
                                }
                            } catch (e: Exception) {
                                Timber.e(
                                    e,
                                    "syncMessagesForFolder: Failed to fetch/map details for msgId $msgId"
                                )
                                null
                            }
                        }
                    }
                    newOrUpdatedMessageDetails.addAll(deferreds.awaitAll().filterNotNull())
                }
            }

            // Ensure messages marked for deletion are not included in new/updated items
            val finalNewOrUpdatedItems =
                newOrUpdatedMessageDetails.filterNot { it.id in deletedMessageIdsCollector }

            Timber.i("syncMessagesForFolder (delta): Fetched ${finalNewOrUpdatedItems.size} new/updated, ${deletedMessageIdsCollector.size} deleted for folder $folderId. Next sync token: $nextSyncTokenToReturnUltimately")
            Result.success(
                DeltaSyncResult(
                    newOrUpdatedItems = finalNewOrUpdatedItems,
                    deletedItemIds = deletedMessageIdsCollector.toList(),
                    nextSyncToken = nextSyncTokenToReturnUltimately
                )
            )

        } catch (e: GoogleNeedsReauthenticationException) {
            Timber.w(
                e,
                "Google account ${e.accountId} needs re-authentication during syncMessagesForFolder (folder: $folderId)."
            ) // Used e.accountId
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        } catch (e: ApiServiceException) {
            Timber.e(
                e,
                "ApiServiceException during syncMessagesForFolder for folder $folderId (Account: $localAccountIdForLogging)"
            )
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(
                e,
                "Generic exception during syncMessagesForFolder for folder $folderId (Account: $localAccountIdForLogging)"
            )
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }
}