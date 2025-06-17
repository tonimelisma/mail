package net.melisma.backend_microsoft

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.melisma.backend_microsoft.auth.MicrosoftAuthUserCredentials
import net.melisma.backend_microsoft.di.MicrosoftGraphHttpClient
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ApiServiceException
import net.melisma.core_data.model.DeltaSyncResult
import net.melisma.core_data.model.ErrorDetails
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDraft
import net.melisma.core_data.model.PagedMessagesResponse
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.model.fromApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.ktor.http.contentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import android.content.Context
import android.content.ContentResolver
import android.util.Base64
import kotlinx.serialization.json.Json
import net.melisma.core_data.model.Attachment
import io.ktor.client.request.put
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.min
import kotlinx.coroutines.ensureActive
import io.ktor.client.request.delete // Added import

@Serializable
private data class GraphCollection<T>( // Used by Ktor getMailFolders
    @SerialName("@odata.context") val context: String? = null,
    val value: List<T>,
    @SerialName("@odata.nextLink") val nextLink: String? = null
)

@Serializable
private data class GraphMailFolder( // Used by Ktor getMailFolders and Delta Sync
    val id: String,
    val displayName: String?, // Made nullable for delta
    val totalItemCount: Int? = 0, // Made nullable for delta
    val unreadItemCount: Int? = 0, // Made nullable for delta
    val wellKnownName: String? = null,
    @SerialName("@removed") val removed: GraphRemovedReason? = null // For delta sync
)

// Ktor specific DTOs for messages, used by getMessagesForFolder
@Serializable
private data class KtorGraphMessage(
    val id: String,
    @SerialName("conversationId") val conversationId: String? = null,
    val receivedDateTime: String? = null,
    val sentDateTime: String? = null,
    val subject: String? = null,
    val sender: KtorGraphRecipient? = null,
    val from: KtorGraphRecipient? = null,
    val toRecipients: List<KtorGraphRecipient>? = emptyList(), // Made nullable for delta
    val ccRecipients: List<KtorGraphRecipient>? = emptyList(), // Added for completeness from example
    val bccRecipients: List<KtorGraphRecipient>? = emptyList(), // Added for completeness from example
    val isRead: Boolean? = true, // Made nullable for delta
    val bodyPreview: String? = null,
    val body: KtorGraphItemBody? = null,
    val hasAttachments: Boolean? = null,
    val flag: KtorGraphFlag? = null, // Represents the 'starred' status via its flagStatus field
    @SerialName("@removed") val removed: GraphRemovedReason? = null, // For delta sync
    val parentFolderId: String? = null // Added this field
)

@Serializable
private data class KtorGraphRecipient(
    val emailAddress: KtorGraphEmailAddress? = null
)

@Serializable
private data class KtorGraphEmailAddress(
    val name: String? = null,
    val address: String? = null
)

@Serializable
private data class KtorGraphItemBody(
    val contentType: String? = null,
    val content: String? = null
)

@Serializable
private data class KtorGraphFlag(
    // Assuming the API returns "flagged", "notFlagged", or similar strings for star status
    val flagStatus: String? = null
)

// Data class for MS Graph Delta API responses
@Serializable
data class GraphDeltaResponse<T>(
    @SerialName("@odata.context") val context: String? = null,
    val value: List<T>,
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    @SerialName("@odata.deltaLink") val deltaLink: String? = null
)

// Represents an item that might have been removed in a delta query
// This class itself might not be directly used in GraphDeltaResponse<T> value list
// if T is made to include the @removed field directly.
@Serializable
data class GraphDeltaItem(
    val id: String, // Always present
    @SerialName("@removed") val removed: GraphRemovedReason? = null // Present if the item was removed
)

@Serializable
data class GraphRemovedReason(
    val reason: String? = null // e.g., "deleted" or "changed"
)

// Add new DTOs for thread operations
@Serializable
private data class GraphMessageIdReadStatus(val id: String, val isRead: Boolean?)

@Serializable
private data class GraphMessageIdOnly(val id: String)

@Serializable
data class GraphAttachmentRequest(
    @SerialName("@odata.type") val odataType: String = "#microsoft.graph.fileAttachment",
    val name: String,
    val contentType: String,
    val contentBytes: String, // Base64 encoded content
    val isInline: Boolean = false,
    @SerialName("contentId") val contentId: String? = null
)

@Serializable
data class KtorGraphItemBodyRequest( // Renamed to avoid conflict if KtorGraphItemBody is used elsewhere for response
    val contentType: String?,
    val content: String?
)

@Serializable
data class KtorGraphRecipientRequest( // Renamed for clarity
    val emailAddress: KtorGraphEmailAddressRequest
)

@Serializable
data class KtorGraphEmailAddressRequest( // Renamed for clarity
    val name: String? = null,
    val address: String?
)

@Serializable
data class GraphMessageRequest(
    val subject: String?,
    val importance: String? = "Normal", // Default importance
    val body: KtorGraphItemBodyRequest?,
    val toRecipients: List<KtorGraphRecipientRequest>?,
    val ccRecipients: List<KtorGraphRecipientRequest>? = null,
    val bccRecipients: List<KtorGraphRecipientRequest>? = null,
    var attachments: List<GraphAttachmentRequest>? = null, // Made var to update after upload sessions
    val id: String? = null // Only for updating existing drafts
)

@Serializable
data class GraphAttachmentItem(
    val attachmentType: String = "file",
    val name: String,
    val size: Long,
    val contentType: String? // Added contentType based on usage
)

@Serializable
data class GraphAttachmentUploadSessionRequest(
    @SerialName("AttachmentItem") val attachmentItem: GraphAttachmentItem
)

@Serializable
data class GraphAttachmentUploadSessionResponse(
    val uploadUrl: String,
    val expirationDateTime: String,
    val nextExpectedRanges: List<String>? = null,
    @SerialName("@odata.context") val context: String? = null // Added for completeness
)

@Serializable
data class GraphFileAttachmentResponse(
    @SerialName("@odata.type") val odataType: String = "#microsoft.graph.fileAttachment",
    val id: String, // Server's attachment ID
    val name: String?, // Correlated name
    val contentType: String?,
    val size: Long?,
    val isInline: Boolean?,
    val lastModifiedDateTime: String?,
    val contentBytes: String? = null, // Only present for small attachments if not fetched with $value
    val contentId: String? = null // Actual CID for inline images from Graph
)

@Serializable
data class GraphAttachmentResponseCollection(
    @SerialName("@odata.context") val context: String? = null,
    val value: List<GraphFileAttachmentResponse>
)

@Singleton
class GraphApiHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient, // Ktor HttpClient
    private val errorMapper: MicrosoftErrorMapper, // Corrected type
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val credentialStore: MicrosoftAuthUserCredentials, // Corrected type
    private val jsonParser: Json
) : MailApiService {

    private val TAG = "GraphApiHelper"

    companion object {
        private const val MS_GRAPH_ROOT_ENDPOINT = "https://graph.microsoft.com/v1.0"
        private const val LARGE_ATTACHMENT_THRESHOLD_BYTES = 3 * 1024 * 1024 // 3MB

        // WellKnownFolderNames from Graph API
        private const val WKNAME_INBOX = "inbox"
        private const val WKNAME_SENTITEMS = "sentitems"
        private const val WKNAME_DRAFTS = "drafts"
        private const val WKNAME_ARCHIVE = "archive"
        private const val WKNAME_DELETEDITEMS = "deleteditems"
        private const val WKNAME_JUNKEMAIL = "junkemail"
        // Less common, but exist
        private const val WKNAME_NOTES = "notes"
        private const val WKNAME_SYNCISSUES = "syncissues"


        // App's internal display names for these folders (can be localized later)
        private const val APP_DISPLAY_NAME_INBOX = "Inbox"
        private const val APP_DISPLAY_NAME_SENT_ITEMS = "Sent Items"
        private const val APP_DISPLAY_NAME_DRAFTS = "Drafts"
        private const val APP_DISPLAY_NAME_ARCHIVE = "Archive"
        private const val APP_DISPLAY_NAME_TRASH = "Trash" // Corresponds to deleteditems
        private const val APP_DISPLAY_NAME_SPAM = "Spam"   // Corresponds to junkemail


        private const val DEFAULT_MAX_RESULTS = 50 // Default page size for messages

        // Fields to select for message lists to minimize data transfer
        private const val MESSAGE_DEFAULT_SELECT_FIELDS =
            "id,conversationId,receivedDateTime,sentDateTime,subject,sender,from,toRecipients,ccRecipients,bccRecipients,isRead,bodyPreview,hasAttachments,flag,parentFolderId"

        // Fields to select when fetching a single message's full content
        private const val MESSAGE_FULL_SELECT_FIELDS = MESSAGE_DEFAULT_SELECT_FIELDS + ",body"
    }

    // Helper function to create a correlated name
    private fun createCorrelatedName(originalName: String?, clientCorrelationId: String): String {
        val safeOriginalName = originalName ?: "attachment"
        // Ensure clientCorrelationId is safe for a filename component (e.g., no slashes)
        val safeCorrelationId = clientCorrelationId.replace("/", "-").replace("\\", "-")
        return "melismaCorrId_${safeCorrelationId}_endCorrId_$safeOriginalName"
    }

    // Helper function to parse a correlated name into (clientCorrelationId, originalName)
    private fun parseCorrelatedName(correlatedName: String?): Pair<String?, String> {
        if (correlatedName == null) return Pair(null, "Unnamed Attachment")
        if (correlatedName.startsWith("melismaCorrId_")) {
            val idAndNamePart = correlatedName.substring("melismaCorrId_".length)
            val separatorIndex = idAndNamePart.indexOf("_endCorrId_")
            if (separatorIndex > 0) {
                val correlationId = idAndNamePart.substring(0, separatorIndex)
                val originalName = idAndNamePart.substring(separatorIndex + "_endCorrId_".length)
                return Pair(correlationId, originalName.ifEmpty { "Unnamed Attachment" })
            }
        }
        return Pair(null, correlatedName) // Not a correlated name or parsing failed, return full name as original
    }

    private suspend fun readFileContentAsBase64(uriString: String): String? = withContext(ioDispatcher) {
        Timber.d("Reading file content for URI: $uriString")
        try {
            val contentResolver = context.contentResolver
            val uri = Uri.parse(uriString)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(1024 * 4) // 4KB buffer
                var nRead: Int
                while (inputStream.read(data, 0, data.size).also { nRead = it } != -1) {
                    buffer.write(data, 0, nRead)
                }
                buffer.flush()
                val fileBytes = buffer.toByteArray()
                return@withContext Base64.encodeToString(fileBytes, Base64.NO_WRAP)
            }
            Timber.w("Could not open input stream for URI: $uriString")
            null
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException reading URI: $uriString. Check URI permissions.")
            null
        } catch (e: IOException) {
            Timber.e(e, "IOException reading URI: $uriString")
            null
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error reading URI: $uriString")
            null
        }
    }
    
    private suspend fun getFileSizeBytes(uriString: String): Long = withContext(ioDispatcher) {
        try {
            val contentResolver = context.contentResolver
            val uri = Uri.parse(uriString)
            // For content URIs, this is the most reliable way to get size
            context.contentResolver.openFileDescriptor(uri, "r")?.use { parcelFileDescriptor ->
                return@withContext parcelFileDescriptor.statSize
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting file size for URI: $uriString")
        }
        return@withContext 0L // Corrected: Return 0 if size cannot be determined
    }

    override suspend fun getMailFolders(
        activity: android.app.Activity?,
        accountId: String
    ): Result<List<MailFolder>> = withContext(ioDispatcher) {
        try {
            Timber.d("Fetching mail folders for accountId: $accountId...")
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders") {
                url {
                    parameters.append("\$top", "100")
                    parameters.append(
                        "\$select",
                        "id,displayName,totalItemCount,unreadItemCount,wellKnownName" // Added wellKnownName
                    )
                }
                accept(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) {
                val graphFolders = response.body<GraphCollection<GraphMailFolder>>().value
                val mailFolders = graphFolders.mapNotNull { mapGraphFolderToMailFolder(it) }
                    .sortedBy { it.displayName }
                Result.success(mailFolders)
            } else {
                val exception = ClientRequestException(response, response.bodyAsText())
                Timber.e(exception, "Error fetching folders: ${response.status}")
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(exception)))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching folders for accountId $accountId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    private fun mapGraphFolderToMailFolder(graphFolder: GraphMailFolder): MailFolder? {
        val type: WellKnownFolderType
        var finalDisplayName = graphFolder.displayName ?: "Unknown Folder"
        val wellKnownNameLower = graphFolder.wellKnownName?.lowercase()
        val displayNameLower = graphFolder.displayName?.lowercase()

        when (wellKnownNameLower) {
            WKNAME_INBOX -> {
                type = WellKnownFolderType.INBOX; finalDisplayName = APP_DISPLAY_NAME_INBOX
            }
            WKNAME_SENTITEMS -> {
                type = WellKnownFolderType.SENT_ITEMS; finalDisplayName =
                    APP_DISPLAY_NAME_SENT_ITEMS
            }
            WKNAME_DRAFTS -> {
                type = WellKnownFolderType.DRAFTS; finalDisplayName = APP_DISPLAY_NAME_DRAFTS
            }
            WKNAME_ARCHIVE -> {
                type = WellKnownFolderType.ARCHIVE; finalDisplayName = APP_DISPLAY_NAME_ARCHIVE
            }
            WKNAME_DELETEDITEMS -> {
                type = WellKnownFolderType.TRASH; finalDisplayName = APP_DISPLAY_NAME_TRASH
            }
            WKNAME_JUNKEMAIL -> {
                type = WellKnownFolderType.SPAM; finalDisplayName = APP_DISPLAY_NAME_SPAM
            }
            WKNAME_NOTES, WKNAME_SYNCISSUES -> {
                return null // Hide these specific system folders
            }
            else -> {
                type = when (displayNameLower) { // Fallback for some common names if wellKnownName is not set or custom
                    "inbox" -> WellKnownFolderType.INBOX.also { finalDisplayName = APP_DISPLAY_NAME_INBOX }
                    "sent items" -> WellKnownFolderType.SENT_ITEMS.also { finalDisplayName = APP_DISPLAY_NAME_SENT_ITEMS }
                    "drafts" -> WellKnownFolderType.DRAFTS.also { finalDisplayName = APP_DISPLAY_NAME_DRAFTS }
                    "archive" -> WellKnownFolderType.ARCHIVE.also { finalDisplayName = APP_DISPLAY_NAME_ARCHIVE }
                    "deleted items", "trash" -> WellKnownFolderType.TRASH.also { finalDisplayName = APP_DISPLAY_NAME_TRASH }
                    "junk email", "junk e-mail", "spam" -> WellKnownFolderType.SPAM.also { finalDisplayName = APP_DISPLAY_NAME_SPAM }
                    "notes", "conversation history", "quick step settings", "rss feeds", "sync issues" -> return null // Hide more by display name
                    else -> WellKnownFolderType.USER_CREATED
                }
            }
        }
        return MailFolder(
            id = graphFolder.id,
            displayName = finalDisplayName,
            totalItemCount = graphFolder.totalItemCount ?: 0,
            unreadItemCount = graphFolder.unreadItemCount ?: 0,
            type = type
        )
    }

    // Mapper for Ktor-parsed KtorGraphMessage to domain Message using fromApi factory
    private fun mapKtorGraphMessageToDomainMessage(
        ktorGraphMessage: KtorGraphMessage,
        accountId: String,
        folderIdFromContext: String // Contextual folderId, might be from list view or known parent
    ): Message {
        val senderName = ktorGraphMessage.sender?.emailAddress?.name ?: ktorGraphMessage.from?.emailAddress?.name
        val senderAddress = ktorGraphMessage.sender?.emailAddress?.address ?: ktorGraphMessage.from?.emailAddress?.address

        val recipientAddresses = ktorGraphMessage.toRecipients?.mapNotNull { it.emailAddress?.address } ?: emptyList()
        val recipientNames = ktorGraphMessage.toRecipients?.mapNotNull { it.emailAddress?.name } ?: emptyList()
        
        // Use parentFolderId from Graph message if folderIdFromContext is empty or a placeholder
        // Otherwise, trust folderIdFromContext (e.g., when listing messages for a specific folder)
        val actualFolderId = if (folderIdFromContext.isBlank() || folderIdFromContext == "DRAFTS_PLACEHOLDER" || folderIdFromContext == "SENT_PLACEHOLDER") {
            ktorGraphMessage.parentFolderId ?: folderIdFromContext // Fallback to original context if parentFolderId is null
        } else {
            folderIdFromContext
        }

        // TODO: Map attachments when parsing *received* messages.
        // This requires new DTOs for received attachments and parsing logic.
        // For now, `hasAttachments` indicates presence, but `attachments` list remains empty.

        return Message.fromApi(
            id = ktorGraphMessage.id,
            remoteId = ktorGraphMessage.id,
            accountId = accountId,
            folderId = actualFolderId,
            threadId = ktorGraphMessage.conversationId,
            receivedDateTime = ktorGraphMessage.receivedDateTime ?: "",
            sentDateTime = ktorGraphMessage.sentDateTime,
            subject = ktorGraphMessage.subject,
            senderName = senderName,
            senderAddress = senderAddress,
            bodyPreview = ktorGraphMessage.bodyPreview,
            isRead = ktorGraphMessage.isRead ?: true,
            body = ktorGraphMessage.body?.content,
            bodyContentType = ktorGraphMessage.body?.contentType,
            recipientNames = recipientNames.ifEmpty { null },
            recipientAddresses = recipientAddresses.ifEmpty { null },
            isStarred = ktorGraphMessage.flag?.flagStatus == "flagged",
            hasAttachments = ktorGraphMessage.hasAttachments ?: false,
            attachments = emptyList(), // Placeholder for received attachments
            lastSuccessfulSyncTimestamp = System.currentTimeMillis(),
            remoteLabelIds = listOfNotNull(actualFolderId)
        )
    }

    override suspend fun getMessagesForFolder(
        folderId: String,
        activity: android.app.Activity?,
        maxResults: Int?,
        pageToken: String?,
        earliestTimestampEpochMillis: Long?
    ): Result<PagedMessagesResponse> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: getMessagesForFolder. FolderId: $folderId, PageToken: $pageToken, MaxResults: $maxResults")
        val accountId = credentialStore.getActiveAccountId()
             ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call", code = "AUTH_NO_ACTIVE_ACCOUNT")))

        try {
            val effectiveMaxResults = maxResults ?: DEFAULT_MAX_RESULTS
            val requestUrl = pageToken ?: buildString {
                append("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/$folderId/messages")
                append("?\$top=$effectiveMaxResults")
                append("&\$select=$MESSAGE_DEFAULT_SELECT_FIELDS") // Use constant
                append("&\$orderby=receivedDateTime desc") // Ensure consistent ordering for paging
                if (earliestTimestampEpochMillis != null) {
                    val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date(earliestTimestampEpochMillis))
                    append("&\$filter=receivedDateTime ge $isoDate")
                }
            }

            Timber.d("Requesting messages: $requestUrl")
            val response: HttpResponse = httpClient.get(requestUrl) {
                accept(ContentType.Application.Json)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                val httpEx = ClientRequestException(response, errorBody)
                Timber.e(httpEx, "Error fetching messages for folder $folderId: ${response.status}")
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }

            val graphResponse = response.body<GraphCollection<KtorGraphMessage>>()
            val ktorGraphMessages = graphResponse.value
            val nextLinkForNextPage = graphResponse.nextLink

            val domainMessages = ktorGraphMessages.mapNotNull {
                mapKtorGraphMessageToDomainMessage(
                    it,
                    accountId,
                    folderId
                )
            }
            Result.success(
                PagedMessagesResponse(
                    messages = domainMessages,
                    nextPageToken = nextLinkForNextPage
                )
            )

        } catch (e: Exception) {
            Timber.e(e, "Exception fetching messages for folder $folderId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    // Methods below were using MS Graph SDK and are now stubbed or need Ktor reimplementation.

    override suspend fun getMessagesForThread(
        threadId: String,
        folderId: String,
        selectFields: List<String>,
        maxResults: Int
    ): Result<List<Message>> = withContext(ioDispatcher) {
        Timber.w("getMessagesForThread: Not implemented in this phase. Iterative thread actions will use single message fetches if needed.")
        Result.failure(NotImplementedError("getMessagesForThread requires Ktor reimplementation."))
    }

    override suspend fun getMessageContent(messageId: String): Result<Message> =
        withContext(ioDispatcher) {
            Timber.d("GraphApiHelper: Fetching full message content for remoteId: $messageId")
            val accountId = credentialStore.getActiveAccountId()
                ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call", code = "AUTH_NO_ACTIVE_ACCOUNT")))

            try {
                val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                    url { parameter("\$select", MESSAGE_FULL_SELECT_FIELDS) } // Ensure full details including parentFolderId
                    accept(ContentType.Application.Json)
                    header("Prefer", "outlook.body-content-type=\"html\"") // Restoring this header
                }
                val responseBodyText = response.bodyAsText()

                if (!response.status.isSuccess()) {
                    Timber.e("Error fetching message content $messageId: ${response.status} - Body: $responseBodyText")
                    val httpEx = ClientRequestException(response, responseBodyText)
                    val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                    return@withContext Result.failure(ApiServiceException(mappedDetails)) // Corrected: Reverted to Result.failure
                }

                val ktorGraphMessage = jsonParser.decodeFromString<KtorGraphMessage>(responseBodyText)
                
                // For getMessageContent, folderId might not be known. Pass empty string.
                // Worker should use existing folderId from DB when updating local entity.
                val domainMessage = mapKtorGraphMessageToDomainMessage(ktorGraphMessage, accountId, "")

                Timber.i("Successfully fetched and parsed message $messageId from Graph. Subject: ${domainMessage.subject}")
                Result.success(domainMessage)

            } catch (e: Exception) {
                Timber.e(e, "Exception fetching message content $messageId from Graph")
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails)) // Corrected: Reverted to Result.failure
            }
        }

    override suspend fun markMessageRead(messageId: String, isRead: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.d("GraphApiHelper: markMessageRead for messageId: $messageId, isRead: $isRead")
            try {
                val requestBody = buildJsonObject {
                    put("isRead", JsonPrimitive(isRead))
                }

                val response: HttpResponse = httpClient.patch("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                if (response.status.isSuccess()) {
                    Timber.i("Successfully marked message $messageId as isRead=$isRead")
                    Result.success(Unit)
                } else {
                    val errorBody = response.bodyAsText()
                    Timber.e("Error marking message $messageId as isRead=$isRead: ${response.status} - $errorBody")
                    val httpEx = ClientRequestException(response, errorBody)
                    Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception in markMessageRead for messageId: $messageId")
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
            }
        }

    override suspend fun starMessage(messageId: String, isStarred: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.d("GraphApiHelper: starMessage for messageId: $messageId, isStarred: $isStarred")
            try {
                val flagStatus = if (isStarred) "flagged" else "notFlagged"
                val requestBody = buildJsonObject {
                    put("flag", buildJsonObject {
                        put("flagStatus", JsonPrimitive(flagStatus))
                    })
                }

                val response: HttpResponse = httpClient.patch("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                if (response.status.isSuccess()) {
                    Timber.i("Successfully starred message $messageId as isStarred=$isStarred (status=$flagStatus)")
                    Result.success(Unit)
                } else {
                    val errorBody = response.bodyAsText()
                    Timber.e("Error starring message $messageId as isStarred=$isStarred: ${response.status} - $errorBody")
                    val httpEx = ClientRequestException(response, errorBody)
                    Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception in starMessage for messageId: $messageId")
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
            }
        }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.d("GraphApiHelper: deleteMessage for messageId: $messageId")
            try {
                val requestBody = buildJsonObject {
                    put("destinationId", JsonPrimitive("deleteditems"))
                }

                val response: HttpResponse = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/move") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                
                if (response.status.isSuccess()) {
                    Timber.i("Successfully deleted (moved to deleteditems) message $messageId")
                    Result.success(Unit)
                } else {
                    val errorBody = response.bodyAsText()
                    Timber.e("Error deleting message $messageId: ${response.status} - $errorBody")
                    val httpEx = ClientRequestException(response, errorBody)
                    Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception in deleteMessage for messageId: $messageId")
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
            }
        }

    override suspend fun getMessageDetails(messageId: String): Flow<Message?> = flow {
        Timber.d("GraphApiHelper: getMessageDetails for $messageId")
        val accountId = credentialStore.getActiveAccountId()
        if (accountId == null) {
            emit(null) // Or throw an error wrapped in Result if interface changes
            Timber.w("No active account ID, cannot fetch message details for $messageId")
            return@flow
        }
        try {
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                parameter("\$select", MESSAGE_FULL_SELECT_FIELDS) // Use constant
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Timber.e("Error fetching message content $messageId: ${response.status} - Body: $responseBodyText")
                val httpEx = ClientRequestException(response, responseBodyText)
                // val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx) // Not needed if emitting null
                emit(null) // Corrected: Emit null for Flow<Message?>
                return@flow
            }

            val ktorGraphMessage = jsonParser.decodeFromString<KtorGraphMessage>(responseBodyText)
            val domainMessage = mapKtorGraphMessageToDomainMessage(ktorGraphMessage, accountId, "")
            emit(domainMessage) // Corrected: Emit Message directly
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching message content $messageId from Graph")
            // val mappedDetails = errorMapper.mapExceptionToErrorDetails(e) // Not needed if emitting null
            emit(null) // Corrected: Emit null for Flow<Message?>
        }
    }

    // Stubs for other MailApiService methods from previous steps
    override suspend fun moveMessage(
        messageId: String,
        currentFolderId: String, // Note: Graph API move doesn't use currentFolderId for the operation itself
        destinationFolderId: String // This should be the REMOTE ID of the destination folder
    ): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.d("GraphApiHelper: moveMessage for messageId: $messageId to destinationFolderId: $destinationFolderId (current: $currentFolderId noted)")
            try {
                val requestBody = buildJsonObject {
                    put("destinationId", JsonPrimitive(destinationFolderId))
                }

                val response: HttpResponse = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/move") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                if (response.status.isSuccess()) {
                    Timber.i("Successfully moved message $messageId to $destinationFolderId")
                    Result.success(Unit)
                } else {
                    val errorBody = response.bodyAsText()
                    Timber.e("Error moving message $messageId to $destinationFolderId: ${response.status} - $errorBody")
                    val httpEx = ClientRequestException(response, errorBody)
                    Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception in moveMessage for messageId: $messageId")
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
            }
        }

    override suspend fun markThreadRead(threadId: String, isRead: Boolean): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: markThreadRead for threadId: $threadId, isRead: $isRead")
        try {
            credentialStore.getActiveAccountId() // Ensure active account
                ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call", code = "AUTH_NO_ACTIVE_ACCOUNT")))

            val listResponse: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
                url {
                    parameter("\$filter", "conversationId eq '$threadId'")
                    parameter("\$select", "id,isRead")
                    parameter("\$top", "250")
                }
                accept(ContentType.Application.Json)
            }

            if (!listResponse.status.isSuccess()) {
                val errorBody = listResponse.bodyAsText()
                Timber.e("Error fetching messages for thread $threadId to mark read: ${listResponse.status} - $errorBody")
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(ClientRequestException(listResponse, errorBody))))
            }

            val messagesInThread = listResponse.body<GraphCollection<GraphMessageIdReadStatus>>().value
            Timber.d("Found ${messagesInThread.size} messages in thread $threadId to potentially update read status.")

            var firstError: Throwable? = null
            for (msg in messagesInThread) {
                if (msg.isRead != isRead) {
                    val markResult = markMessageRead(msg.id, isRead)
                    if (markResult.isFailure) {
                        Timber.e(markResult.exceptionOrNull(), "Failed to mark message ${msg.id} in thread $threadId. Aborting thread operation.")
                        firstError = markResult.exceptionOrNull() ?: Exception("Unknown error marking message in thread")
                        break
                    }
                }
            }
            
            return@withContext if (firstError == null) {
                Timber.i("Successfully processed markThreadRead for thread $threadId to isRead=$isRead")
                Result.success(Unit)
            } else {
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(firstError)))
            }

        } catch (e: Exception) {
            Timber.e(e, "Exception in markThreadRead for threadId: $threadId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    override suspend fun deleteThread(threadId: String): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: deleteThread for threadId: $threadId")
        try {
            credentialStore.getActiveAccountId() 
                ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call", code = "AUTH_NO_ACTIVE_ACCOUNT")))
            
            val listResponse: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
                url {
                    parameter("\$filter", "conversationId eq '$threadId'")
                    parameter("\$select", "id")
                    parameter("\$top", "250")
                }
                accept(ContentType.Application.Json)
            }

            if (!listResponse.status.isSuccess()) {
                val errorBody = listResponse.bodyAsText()
                Timber.e("Error fetching messages for thread $threadId to delete: ${listResponse.status} - $errorBody")
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(ClientRequestException(listResponse, errorBody))))
            }

            val messagesInThread = listResponse.body<GraphCollection<GraphMessageIdOnly>>().value
            Timber.d("Found ${messagesInThread.size} messages in thread $threadId to delete.")
            
            var firstError: Throwable? = null
            for (msg in messagesInThread) {
                val deleteResult = deleteMessage(msg.id)
                if (deleteResult.isFailure) {
                    Timber.e(deleteResult.exceptionOrNull(), "Failed to delete message ${msg.id} in thread $threadId. Aborting thread operation.")
                    firstError = deleteResult.exceptionOrNull() ?: Exception("Unknown error deleting message in thread")
                    break
                }
            }
            
            return@withContext if (firstError == null) {
                Timber.i("Successfully processed deleteThread for thread $threadId")
                Result.success(Unit)
            } else {
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(firstError)))
            }

        } catch (e: Exception) {
            Timber.e(e, "Exception in deleteThread for threadId: $threadId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    override suspend fun moveThread(
        threadId: String,
        currentFolderId: String, // Remote ID
        destinationFolderId: String // Remote ID
    ): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.d("GraphApiHelper: moveThread for threadId: $threadId from $currentFolderId to $destinationFolderId")
            try {
                credentialStore.getActiveAccountId()
                    ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call", code = "AUTH_NO_ACTIVE_ACCOUNT")))

                val listResponse: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
                    url {
                        parameter("\$filter", "conversationId eq '$threadId' and parentFolderId eq '$currentFolderId'")
                        parameter("\$select", "id")
                        parameter("\$top", "250")
                    }
                    accept(ContentType.Application.Json)
                }

                if (!listResponse.status.isSuccess()) {
                    val errorBody = listResponse.bodyAsText()
                    Timber.e("Error fetching messages for thread $threadId in folder $currentFolderId to move: ${listResponse.status} - $errorBody")
                    return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(ClientRequestException(listResponse, errorBody))))
                }
                
                val messagesInThreadAndFolder = listResponse.body<GraphCollection<GraphMessageIdOnly>>().value
                Timber.d("Found ${messagesInThreadAndFolder.size} messages in thread $threadId and folder $currentFolderId to move to $destinationFolderId.")

                if (messagesInThreadAndFolder.isEmpty()){
                    Timber.i("No messages found matching criteria for moveThread operation. Thread: $threadId, Source Folder: $currentFolderId. Considering successful.")
                    return@withContext Result.success(Unit)
                }

                var firstError: Throwable? = null
                for (msg in messagesInThreadAndFolder) {
                    val moveResult = moveMessage(msg.id, currentFolderId, destinationFolderId)
                    if (moveResult.isFailure) {
                        Timber.e(moveResult.exceptionOrNull(), "Failed to move message ${msg.id} in thread $threadId. Aborting thread operation.")
                        firstError = moveResult.exceptionOrNull() ?: Exception("Unknown error moving message in thread")
                        break
                    }
                }
                
                return@withContext if (firstError == null) {
                    Timber.i("Successfully processed moveThread for thread $threadId to $destinationFolderId")
                    Result.success(Unit)
                } else {
                    Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(firstError)))
                }

            } catch (e: Exception) {
                Timber.e(e, "Exception in moveThread for threadId: $threadId")
                Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
            }
        }

    override suspend fun downloadAttachment(
        messageId: String,
        attachmentId: String
    ): Result<ByteArray> =
        withContext(ioDispatcher) { 
            Timber.w("downloadAttachment for Graph not implemented yet.")
            // This would fetch the content of a specific attachment.
            // GET /me/messages/{messageId}/attachments/{attachmentId}
            // The response for fileAttachment would contain contentBytes.
            Result.failure(NotImplementedError("downloadAttachment for Graph not implemented")) 
        }

    // Ensure all MailApiService methods are overridden
    // Stubs for methods not directly part of this increment but in MailApiService
     override suspend fun searchMessages(
        query: String,
        folderId: String?, // Remote folder ID
        maxResults: Int
    ): Result<List<Message>>  = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: searchMessages. Query: '$query', FolderId: $folderId, MaxResults: $maxResults")
        val accountId = credentialStore.getActiveAccountId()
            ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found", code = "AUTH_NO_ACTIVE_ACCOUNT")))

        try {
            val searchScope = if (folderId.isNullOrBlank()) "me" else "me/mailFolders/$folderId"
            // Construct the search query string. Note: Graph search on messages uses a specific syntax.
            // For simplicity, just passing the query. Might need KQL escaping or $search parameter.
            // Using $filter for now as it's more common for field-based queries if 'query' is structured.
            // If 'query' is free text, $search is better: "$MS_GRAPH_ROOT_ENDPOINT/$searchScope/messages?\$search=\"$query\""
            // Let's assume for now 'query' might be a field search or simple term.
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/$searchScope/messages") {
                parameter("\$filter", "contains(subject, '$query') or contains(body, '$query') or from/emailAddress/address eq '$query' or toRecipients/any(r:r/emailAddress/address eq '$query')")
                parameter("\$top", maxResults)
                parameter("\$select", MESSAGE_DEFAULT_SELECT_FIELDS) // Use constant
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Timber.e("Error searching messages: ${response.status} - Body: $responseBodyText")
                val httpEx = ClientRequestException(response, responseBodyText)
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }

            val searchResults = jsonParser.decodeFromString<GraphCollection<KtorGraphMessage>>(responseBodyText).value
            val domainMessages = searchResults.map { mapKtorGraphMessageToDomainMessage(it, accountId, it.parentFolderId ?: folderId ?: "") }
            
            Result.success(domainMessages)
        } catch (e: Exception) {
            Timber.e(e, "Exception during Graph message search")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    override suspend fun syncFolders(
        accountId: String,
        syncToken: String?
    ): Result<DeltaSyncResult<MailFolder>> = withContext(ioDispatcher) {
        Timber.i("syncFolders (Graph) called for account $accountId. SyncToken (deltaLink): $syncToken")
        try {
            val collectedNewOrUpdatedFolders = mutableListOf<MailFolder>()
            val collectedDeletedFolderIds = mutableListOf<String>()
            val initialRequestUrl: String = syncToken ?: "$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/delta"
            var currentRequestUrlAsNullable: String? = initialRequestUrl
            var finalDeltaLinkForNextSync: String? = syncToken

            do {
                val urlToFetch = currentRequestUrlAsNullable
                if (urlToFetch == null) { // Added null check for safety, though theoretically unreachable if loop condition is strict
                    Timber.w("syncFolders: currentRequestUrlAsNullable became null unexpectedly within the loop. Breaking.")
                    break
                }
                val response: HttpResponse = httpClient.get(urlToFetch) { accept(ContentType.Application.Json) }

                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(ClientRequestException(response, errorBody))))
                }
                val deltaResponse = response.body<GraphDeltaResponse<GraphMailFolder>>()
                deltaResponse.value.forEach { graphFolder ->
                    if (graphFolder.removed != null) {
                        collectedDeletedFolderIds.add(graphFolder.id)
                    } else {
                        if (graphFolder.displayName == null) {
                             Timber.w("Graph folder ${graphFolder.id} is not marked removed but has null displayName during delta sync. Skipping.")
                        } else {
                             mapGraphFolderToMailFolder(graphFolder)?.let { collectedNewOrUpdatedFolders.add(it) }
                        }
                    }
                }
                deltaResponse.deltaLink?.let { finalDeltaLinkForNextSync = it }
                currentRequestUrlAsNullable = deltaResponse.nextLink
            } while (currentRequestUrlAsNullable != null)
            Result.success(DeltaSyncResult(collectedNewOrUpdatedFolders, collectedDeletedFolderIds.distinct(), finalDeltaLinkForNextSync))
        } catch (e: Exception) { Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e))) }
    }

    override suspend fun syncMessagesForFolder(
        folderRemoteId: String,
        syncToken: String?,
        maxResults: Int?,
        earliestTimestampEpochMillis: Long?
    ): Result<DeltaSyncResult<Message>> = withContext(ioDispatcher) {
        val accountId = credentialStore.getActiveAccountId()
            ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found", code = "AUTH_NO_ACTIVE_ACCOUNT")))
        Timber.d("GraphApiHelper: syncMessagesForFolder (Delta). FolderRemoteId: $folderRemoteId, SyncToken: $syncToken")

        try {
            val requestUrl = syncToken ?: buildString {
                append("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/$folderRemoteId/messages/delta")
                append("?\$select=$MESSAGE_DEFAULT_SELECT_FIELDS") // Use constant for select fields
                if (earliestTimestampEpochMillis != null && syncToken == null) { // Filter only for initial sync
                    val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date(earliestTimestampEpochMillis))
                    // Note: $filter on delta is tricky and might not always work as expected or be supported for all fields.
                    // Graph typically expects delta to be used without $filter after initial token.
                    // For initial sync, some suggest doing a normal GET with filter then a delta.
                    // For simplicity here, trying to apply if it's an initial full sync.
                    // This might need to be removed if Graph API rejects it for delta queries.
                    // It's generally safer to get all changes with delta and filter client-side if needed after initial sync.
                    // Let's remove the filter from delta for now as it's often problematic.
                    // append("&\$filter=receivedDateTime ge $isoDate")
                }
            }
            // ... rest of the function

            val collectedNewOrUpdated = mutableListOf<Message>()
            val collectedDeletedIds = mutableListOf<String>()

            var nextUrl: String? = requestUrl
            var finalDeltaLink: String? = syncToken

            do {
                val urlToFetch = nextUrl ?: break

                val response: HttpResponse = httpClient.get(urlToFetch) {
                    accept(ContentType.Application.Json)
                    maxResults?.let { parameter("\$top", it) }
                }

                if (!response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val httpEx = ClientRequestException(response, body)
                    return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
                }

                val deltaResp = response.body<GraphDeltaResponse<KtorGraphMessage>>()

                deltaResp.value.forEach { gm ->
                    if (gm.removed != null) {
                        collectedDeletedIds.add(gm.id)
                    } else {
                        collectedNewOrUpdated.add(
                            mapKtorGraphMessageToDomainMessage(
                                gm,
                                accountId,
                                folderRemoteId
                            )
                        )
                    }
                }

                deltaResp.deltaLink?.let { finalDeltaLink = it }
                nextUrl = deltaResp.nextLink

            } while (nextUrl != null)

            Result.success(
                DeltaSyncResult(
                    newOrUpdatedItems = collectedNewOrUpdated,
                    deletedItemIds = collectedDeletedIds.distinct(),
                    nextSyncToken = finalDeltaLink
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Exception during Graph delta message sync for folder $folderRemoteId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    override suspend fun getMessage(messageRemoteId: String): Result<Message> = withContext(ioDispatcher) {
        val accountId = credentialStore.getActiveAccountId()
            ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for getMessage", code = "AUTH_NO_ACTIVE_ACCOUNT")))
        Timber.d("GraphApiHelper: getMessage for remote ID $messageRemoteId")

        try {
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageRemoteId") {
                parameter("\$select", MESSAGE_FULL_SELECT_FIELDS) // Use constant for full fields
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val httpEx = ClientRequestException(response, responseBodyText)
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }
            val ktorGraphMessage = jsonParser.decodeFromString<KtorGraphMessage>(responseBodyText)
            // Use parentFolderId from the message itself if available, otherwise an empty string or placeholder
            val domainMessage = mapKtorGraphMessageToDomainMessage(ktorGraphMessage, accountId, ktorGraphMessage.parentFolderId ?: "")
            Result.success(domainMessage)
        } catch (e: Exception) { Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e))) }
    }

    override suspend fun sendMessage(draft: MessageDraft): Result<String> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: sendMessage. Subject: ${draft.subject}, Attachments: ${draft.attachments.size}")
        val accountId = credentialStore.getActiveAccountId() // Checked by caller (ActionUploadWorker)
            ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call in sendMessage", code = "AUTH_NO_ACTIVE_ACCOUNT")))

        var tempDraftMessageId: String? = null // To store ID of draft if created

        try {
            val smallAttachmentsForRequest = mutableListOf<GraphAttachmentRequest>()
            val largeAttachmentsToUpload = mutableListOf<net.melisma.core_data.model.Attachment>() // Original domain Attachment

            draft.attachments.forEach { att ->
                if (att.localUri.isNullOrBlank()) {
                    Timber.w("Attachment ${att.fileName} has no local URI. Skipping.")
                    return@forEach
                }
                val fileSize = getFileSizeBytes(att.localUri!!)
                 if (fileSize == 0L && att.size > 0L) {
                     Timber.w("Send: Attachment ${att.fileName} URI invalid or size 0 via URI, but draft has size ${att.size}. Skipping.")
                     return@forEach
                 }
                 if (fileSize == 0L) {
                    Timber.w("Send: Attachment ${att.fileName} has size 0 or URI is invalid: ${att.localUri}. Skipping.")
                    return@forEach
                 }

                if (fileSize < LARGE_ATTACHMENT_THRESHOLD_BYTES) {
                    val contentBytes = readFileContentAsBase64(att.localUri!!)
                    if (contentBytes != null) {
                        smallAttachmentsForRequest.add(
                            GraphAttachmentRequest(
                                name = createCorrelatedName(att.fileName, att.id), // Use correlated name
                                contentType = att.contentType ?: "application/octet-stream",
                                contentBytes = contentBytes,
                                isInline = att.isInline,
                                contentId = att.contentId // Actual CID for inline images
                            )
                        )
                    }
                } else {
                    largeAttachmentsToUpload.add(att) // Add the domain model Attachment
                }
            }
            
            val toRecipients = draft.to.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }
            val ccRecipients = draft.cc.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }.ifEmpty { null }
            val bccRecipients = draft.bcc.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }.ifEmpty { null }

            val initialMessageBody = GraphMessageRequest(
                subject = draft.subject,
                body = KtorGraphItemBodyRequest(
                    contentType = if (draft.body.contains("<html>", ignoreCase = true) || draft.body.contains("<p>", ignoreCase = true)) "HTML" else "Text",
                    content = draft.body
                ),
                toRecipients = toRecipients,
                ccRecipients = ccRecipients,
                bccRecipients = bccRecipients,
                attachments = smallAttachmentsForRequest.ifEmpty { null } // Include small attachments directly
            )

            // Step 1: Create a draft message on the server. This message will contain metadata and small attachments.
            // POST to /me/messages creates a draft in the user's mailbox (typically Drafts folder)
            Timber.d("sendMessage: Creating initial draft with small attachments. Body: $initialMessageBody")
            val createDraftResponse: HttpResponse = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
                contentType(ContentType.Application.Json)
                setBody(initialMessageBody)
            }
            val createDraftResponseBodyText = createDraftResponse.bodyAsText()

            if (!createDraftResponse.status.isSuccess()) { // Expect 201 Created
                Timber.e("Error creating temporary draft for send: ${createDraftResponse.status} - Body: $createDraftResponseBodyText")
                val httpEx = ClientRequestException(createDraftResponse, createDraftResponseBodyText)
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }
            val createdDraftGraphMessage = jsonParser.decodeFromString<KtorGraphMessage>(createDraftResponseBodyText)
            tempDraftMessageId = createdDraftGraphMessage.id // Store the ID of the created draft
            Timber.i("Temporary draft created with ID: $tempDraftMessageId for sending. Small attachments included: ${smallAttachmentsForRequest.size}")

            // Step 2: Upload large attachments to this draft ID
            // tempDraftMessageId MUST be non-null here if we reached this point.
            val currentDraftIdForCleanup = tempDraftMessageId!! 

            for (largeAtt in largeAttachmentsToUpload) {
                if (largeAtt.localUri.isNullOrBlank()) continue 
                val fileSize = getFileSizeBytes(largeAtt.localUri!!) 
                 if (fileSize == 0L) continue


                Timber.d("Processing large attachment for send: ${largeAtt.fileName}, size: $fileSize, URI: ${largeAtt.localUri}")
                val sessionResult = createUploadSessionInternal(currentDraftIdForCleanup, largeAtt.fileName ?: "attachment", fileSize, largeAtt.contentType)
                if (sessionResult.isFailure) {
                    val error = sessionResult.exceptionOrNull() ?: Exception("Upload session creation failed for ${largeAtt.fileName}")
                    Timber.e(error, "Failed to create upload session for ${largeAtt.fileName} on draft $currentDraftIdForCleanup.")
                    
                    val cleanupResult = deleteMessage(currentDraftIdForCleanup) // Attempt to delete the server draft
                    if (cleanupResult.isFailure) {
                        Timber.w(cleanupResult.exceptionOrNull(), "Failed to cleanup temporary draft $currentDraftIdForCleanup after upload session failure for ${largeAtt.fileName}.")
                    } else {
                        Timber.i("Successfully cleaned up temporary draft $currentDraftIdForCleanup after upload session failure for ${largeAtt.fileName}.")
                    }
                    return@withContext Result.failure(error)
                }
                val uploadSession = sessionResult.getOrThrow()

                val uploadResult = uploadAttachmentInChunks(uploadSession.uploadUrl, largeAtt.localUri!!, fileSize)
                if (uploadResult.isFailure) {
                     val error = uploadResult.exceptionOrNull() ?: Exception("Chunk upload failed for ${largeAtt.fileName}")
                     Timber.e(error, "Failed to upload chunks for ${largeAtt.fileName} on draft $currentDraftIdForCleanup.")
                     
                     val cleanupResult = deleteMessage(currentDraftIdForCleanup) // Attempt to delete the server draft
                     if (cleanupResult.isFailure) {
                        Timber.w(cleanupResult.exceptionOrNull(), "Failed to cleanup temporary draft $currentDraftIdForCleanup after chunk upload failure for ${largeAtt.fileName}.")
                     } else {
                        Timber.i("Successfully cleaned up temporary draft $currentDraftIdForCleanup after chunk upload failure for ${largeAtt.fileName}.")
                     }
                     return@withContext Result.failure(error)
                }
                Timber.i("Large attachment ${largeAtt.fileName} uploaded successfully to draft $currentDraftIdForCleanup.")
            }

            // Step 3: Send the draft (which now has all attachments associated)
            Timber.d("Sending draft message with ID: $currentDraftIdForCleanup")
            val sendResponse: HttpResponse = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$currentDraftIdForCleanup/send") {
                // No body needed for send action on an existing draft
            }

            if (!sendResponse.status.isSuccess()) { // Expect 202 Accepted
                val sendErrorBody = sendResponse.bodyAsText()
                Timber.e("Error sending draft $currentDraftIdForCleanup: ${sendResponse.status} - Body: $sendErrorBody")
                val httpEx = ClientRequestException(sendResponse, sendErrorBody)
                // The draft (currentDraftIdForCleanup) likely still exists in user's Drafts folder on server if send fails.
                // This is acceptable as per previous logic.
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }

            Timber.i("Draft message $currentDraftIdForCleanup sent successfully (API returned ${sendResponse.status}).")
            Result.success(currentDraftIdForCleanup) 

        } catch (e: Exception) {
            Timber.e(e, "Exception sending message in GraphApiHelper. Temp draft ID (if created): $tempDraftMessageId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    // Stub for createDraftMessage
    override suspend fun createDraftMessage(draft: MessageDraft): Result<Message> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: createDraftMessage. Subject: ${draft.subject}, Attachments: ${draft.attachments.size}")
        val accountId = credentialStore.getActiveAccountId()
            ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call in createDraftMessage", code = "AUTH_NO_ACTIVE_ACCOUNT")))

        var createdDraftMessageId: String? = null

        try {
            val smallAttachmentsForRequest = mutableListOf<GraphAttachmentRequest>()
            val largeAttachmentsToUpload = mutableListOf<net.melisma.core_data.model.Attachment>()

            draft.attachments.forEach { att ->
                if (att.localUri.isNullOrBlank()) {
                    Timber.w("CreateDraft: Attachment ${att.fileName} has no local URI. Skipping.")
                    return@forEach
                }
                val fileSize = getFileSizeBytes(att.localUri!!)
                if (fileSize == 0L) {
                    Timber.w("CreateDraft: Attachment ${att.fileName} has size 0 or URI is invalid. Skipping.")
                    return@forEach
                }

                if (fileSize < LARGE_ATTACHMENT_THRESHOLD_BYTES) {
                    val contentBytes = readFileContentAsBase64(att.localUri!!)
                    if (contentBytes != null) {
                        smallAttachmentsForRequest.add(
                            GraphAttachmentRequest(
                                name = createCorrelatedName(att.fileName, att.id), // Use correlated name
                                contentType = att.contentType ?: "application/octet-stream",
                                contentBytes = contentBytes,
                                isInline = att.isInline,
                                contentId = att.contentId // Actual CID for inline images
                            )
                        )
                    } else {
                         Timber.w("CreateDraft: Failed to read content for small attachment ${att.fileName}. Skipping.")
                    }
                } else {
                    largeAttachmentsToUpload.add(att)
                }
            }

            val toRecipients = draft.to.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }
            val ccRecipients = draft.cc.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }.ifEmpty { null }
            val bccRecipients = draft.bcc.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }.ifEmpty { null }

            val messagePayload = GraphMessageRequest(
                subject = draft.subject,
                body = KtorGraphItemBodyRequest(
                    contentType = if (draft.body.contains("<html>", ignoreCase = true) || draft.body.contains("<p>", ignoreCase = true)) "HTML" else "Text",
                    content = draft.body
                ),
                toRecipients = toRecipients,
                ccRecipients = ccRecipients,
                bccRecipients = bccRecipients,
                attachments = smallAttachmentsForRequest.ifEmpty { null }
            )

            // Step 1: Create draft message with small attachments
            Timber.d("CreateDraft: Creating initial draft. Payload: $messagePayload")
            val createResponse: HttpResponse = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
                contentType(ContentType.Application.Json)
                setBody(messagePayload)
            }
            val createResponseBodyText = createResponse.bodyAsText()

            if (!createResponse.status.isSuccess()) { // Expect 201 Created
                Timber.e("Error creating draft: ${createResponse.status} - Body: $createResponseBodyText")
                val httpEx = ClientRequestException(createResponse, createResponseBodyText)
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }

            val createdGraphMessage = jsonParser.decodeFromString<KtorGraphMessage>(createResponseBodyText)
            createdDraftMessageId = createdGraphMessage.id
            Timber.i("Draft created with ID: $createdDraftMessageId. Small attachments included: ${smallAttachmentsForRequest.size}")

            // Step 2: Upload large attachments to this draft ID
            for (largeAtt in largeAttachmentsToUpload) {
                if (largeAtt.localUri.isNullOrBlank()) continue
                val fileSize = getFileSizeBytes(largeAtt.localUri!!)
                if (fileSize == 0L) continue

                Timber.d("CreateDraft: Processing large attachment: ${largeAtt.fileName}, size: $fileSize, URI: ${largeAtt.localUri} for draft $createdDraftMessageId")
                val correlatedNameForLargeAtt = createCorrelatedName(largeAtt.fileName, largeAtt.id)
                val sessionResult = createUploadSessionInternal(createdDraftMessageId, correlatedNameForLargeAtt, fileSize, largeAtt.contentType)
                if (sessionResult.isFailure) {
                    val error = sessionResult.exceptionOrNull() ?: Exception("Upload session creation failed for ${largeAtt.fileName}")
                    Timber.e(error, "Failed to create upload session for ${largeAtt.fileName} on draft $createdDraftMessageId.")
                    // As per plan, leave draft on server, return failure to indicate incomplete operation
                    return@withContext Result.failure(error)
                }
                val uploadSession = sessionResult.getOrThrow()

                val uploadResult = uploadAttachmentInChunks(uploadSession.uploadUrl, largeAtt.localUri!!, fileSize)
                if (uploadResult.isFailure) {
                     val error = uploadResult.exceptionOrNull() ?: Exception("Chunk upload failed for new large attachment ${largeAtt.fileName}")
                     Timber.e(error, "Failed to upload chunks for new large attachment ${largeAtt.fileName} on draft $createdDraftMessageId.")
                     return@withContext Result.failure(error)
                }
                Timber.i("CreateDraft: New large attachment ${largeAtt.fileName} uploaded successfully to draft $createdDraftMessageId.")
            }

            // Step 3: Fetch the full draft message (including all attachments) to return its final state
            val finalMessageResult = getMessageContent(createdDraftMessageId)
             if (finalMessageResult.isFailure) {
                 Timber.e(finalMessageResult.exceptionOrNull(), "CreateDraft: Failed to fetch final state of draft $createdDraftMessageId after processing attachments.")
                 return@withContext Result.failure(finalMessageResult.exceptionOrNull() ?: Exception("Failed to fetch final draft state"))
            }
            var finalMessage = finalMessageResult.getOrThrow()
            
            val finalAttachmentsResult = getMessageAttachmentsInternal(createdDraftMessageId, accountId)
            if (finalAttachmentsResult.isSuccess) {
                finalMessage = finalMessage.copy(attachments = finalAttachmentsResult.getOrThrow())
            } else {
                 Timber.w(finalAttachmentsResult.exceptionOrNull(), "CreateDraft: Could not fetch final attachments for draft $createdDraftMessageId, attachment list in returned message might be incomplete.")
            }

            Timber.i("Draft $createdDraftMessageId created and all attachments processed successfully. Returning final message state.")
            Result.success(finalMessage)

        } catch (e: Exception) {
            Timber.e(e, "Exception creating draft message in GraphApiHelper. Created Draft ID (if any): $createdDraftMessageId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    // Actual implementation for getMessageAttachments, to be called internally or by a potentially revised public API
    private suspend fun getMessageAttachmentsInternal(messageId: String, accountId: String): Result<List<net.melisma.core_data.model.Attachment>> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: getMessageAttachmentsInternal for messageId: $messageId")
        try {
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/attachments") {
                accept(ContentType.Application.Json)
                // Optionally, use $select to limit fields if needed, e.g. "id,name,contentType,size,isInline,lastModifiedDateTime"
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Timber.e("Error fetching attachments for $messageId: ${response.status} - Body: $responseBodyText")
                val httpEx = ClientRequestException(response, responseBodyText)
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }

            val graphAttachmentCollection = jsonParser.decodeFromString<GraphAttachmentResponseCollection>(responseBodyText)
            val domainAttachments = graphAttachmentCollection.value.map { graphAtt ->
                mapGraphFileAttachmentResponseToDomain(graphAtt, messageId, accountId)
            }
            Result.success(domainAttachments)
        } catch (e: Exception) {
            Timber.e(e, "Exception in getMessageAttachmentsInternal for $messageId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }
    
    // Override the public getMessageAttachments to use the internal version
    override suspend fun getMessageAttachments(messageId: String): Result<List<net.melisma.core_data.model.Attachment>> {
         val accountId = credentialStore.getActiveAccountId()
            ?: return Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call in getMessageAttachments", code = "AUTH_NO_ACTIVE_ACCOUNT")))
        return getMessageAttachmentsInternal(messageId, accountId)
    }

    private fun mapGraphFileAttachmentResponseToDomain(graphAttachment: GraphFileAttachmentResponse, messageRemoteId: String, accountId: String): net.melisma.core_data.model.Attachment {
        val (clientCorrelationId, originalName) = parseCorrelatedName(graphAttachment.name)
        
        val domainAttachmentId = clientCorrelationId ?: graphAttachment.id 
        if (clientCorrelationId == null) {
            Timber.w("Failed to parse clientCorrelationId from attachment name: '${graphAttachment.name}'. Using server attachment ID '${graphAttachment.id}' as domain ID.")
        }

        // Assuming Attachment domain model will be updated to include accountId and remoteId
        // and that downloadStatus and lastSyncError are the last two params.
        return net.melisma.core_data.model.Attachment(
            id = domainAttachmentId, 
            messageId = messageRemoteId, 
            accountId = accountId, // Will be added to domain model
            fileName = originalName, 
            contentType = graphAttachment.contentType ?: "application/octet-stream",
            size = graphAttachment.size ?: 0L,
            isInline = graphAttachment.isInline ?: false,
            remoteId = graphAttachment.id, // Will be added to domain model
            localUri = null, 
            contentId = graphAttachment.contentId,
            // Default values for fields not directly from GraphFileAttachmentResponse in this context
            downloadStatus = if (graphAttachment.contentBytes != null) "COMPLETED" else "NOT_DOWNLOADED", 
            lastSyncError = null 
        )
    }

    override suspend fun updateDraftMessage(
        messageId: String, 
        draft: MessageDraft
    ): Result<Message> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: updateDraftMessage. MessageId: $messageId, Subject: ${draft.subject}, Attachments: ${draft.attachments.size}")
        val accountId = credentialStore.getActiveAccountId()
            ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call in updateDraftMessage", code = "AUTH_NO_ACTIVE_ACCOUNT")))

        try {
            val existingServerAttachmentsResult = getMessageAttachmentsInternal(messageId, accountId)
            if (existingServerAttachmentsResult.isFailure) {
                Timber.e(existingServerAttachmentsResult.exceptionOrNull(), "UpdateDraft: Failed to fetch existing attachments for draft $messageId. Aborting update.")
                return@withContext Result.failure(existingServerAttachmentsResult.exceptionOrNull() ?: Exception("Failed to fetch existing attachments for update"))
            }
            // Using fileName for matching, make it safer for nulls
            val serverAttachmentsMap = existingServerAttachmentsResult.getOrThrow()
                .associateBy { it.fileName ?: "unknown_server_filename_${java.util.UUID.randomUUID()}" } 

            val localDraftAttachmentsMap = draft.attachments
                .associateBy { it.fileName ?: "unknown_local_filename_${java.util.UUID.randomUUID()}" }


            val attachmentsToDelete = serverAttachmentsMap.filterKeys { serverAttName ->
                !localDraftAttachmentsMap.containsKey(serverAttName)
            }

            for (attachmentToDelete in attachmentsToDelete.values) { // Iterate over Attachment objects
                val deleteUrl = "$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/attachments/${attachmentToDelete.remoteId}" // Use remoteId for server's attachment ID
                Timber.d("UpdateDraft: Deleting attachment '${attachmentToDelete.fileName}' (RemoteID: ${attachmentToDelete.remoteId}, URL: $deleteUrl) from draft $messageId")
                try {
                    val deleteResponse: HttpResponse = httpClient.delete(deleteUrl) // httpClient.delete should now resolve
                    if (!deleteResponse.status.isSuccess()) {
                        Timber.w("UpdateDraft: Failed to delete attachment ${attachmentToDelete.remoteId} from draft $messageId. Status: ${deleteResponse.status}. Body: ${deleteResponse.bodyAsText()}")
                    } else {
                        Timber.i("UpdateDraft: Successfully deleted attachment ${attachmentToDelete.remoteId} from draft $messageId. Status: ${deleteResponse.status}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "UpdateDraft: Exception deleting attachment ${attachmentToDelete.remoteId} from draft $messageId.")
                }
            }

            // Step 3: Prepare message payload for PATCH (core fields + small attachments to add/replace)
            val smallAttachmentsForRequest = mutableListOf<GraphAttachmentRequest>()
            val largeAttachmentsToUpload = mutableListOf<net.melisma.core_data.model.Attachment>() // Domain attachments to be newly uploaded

            draft.attachments.forEach { localAtt ->
                // Only add if it's "new" (not found on server by name) or if we decide to re-upload for simplicity
                if (!serverAttachmentsMap.containsKey(localAtt.fileName)) {
                    if (localAtt.localUri.isNullOrBlank()) {
                        Timber.w("UpdateDraft: New attachment ${localAtt.fileName} has no local URI. Skipping.")
                        return@forEach
                    }
                    val fileSize = getFileSizeBytes(localAtt.localUri!!)
                    if (fileSize == 0L) {
                        Timber.w("UpdateDraft: New attachment ${localAtt.fileName} has size 0 or URI is invalid. Skipping.")
                        return@forEach
                    }

                    if (fileSize < LARGE_ATTACHMENT_THRESHOLD_BYTES) {
                        val contentBytes = readFileContentAsBase64(localAtt.localUri!!)
                        if (contentBytes != null) {
                            smallAttachmentsForRequest.add(
                                GraphAttachmentRequest(
                                    name = createCorrelatedName(localAtt.fileName, localAtt.id), // Correlated name
                                    contentType = localAtt.contentType ?: "application/octet-stream",
                                    contentBytes = contentBytes,
                                    isInline = localAtt.isInline,
                                    contentId = localAtt.contentId // Actual CID for inline images
                                )
                            )
                        } else {
                             Timber.w("UpdateDraft: Failed to read content for new small attachment ${localAtt.fileName}. Skipping.")
                        }
                    } else {
                        largeAttachmentsToUpload.add(localAtt) // This is a new large attachment
                    }
                }
            }
            
            // PATCH the message core details.
            // For attachments: Graph API for PATCH on message does not directly handle adding attachments.
            // Small new attachments need to be added via POST /attachments after PATCH.
            // So, the 'attachments' field in GraphMessageRequest for PATCH should be null or omitted.
            val toRecipients = draft.to.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }
            val ccRecipients = draft.cc.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }.ifEmpty { null }
            val bccRecipients = draft.bcc.map { KtorGraphRecipientRequest(KtorGraphEmailAddressRequest(name = it.displayName, address = it.emailAddress)) }.ifEmpty { null }

            val messagePatchPayload = GraphMessageRequest( // Using GraphMessageRequest DTO, but ensure it's suitable for PATCH
                subject = draft.subject,
                body = KtorGraphItemBodyRequest(
                    contentType = if (draft.body.contains("<html>", ignoreCase = true) || draft.body.contains("<p>", ignoreCase = true)) "HTML" else "Text",
                    content = draft.body
                ),
                toRecipients = toRecipients,
                ccRecipients = ccRecipients,
                bccRecipients = bccRecipients,
                attachments = null // Do not include attachments in the PATCH body for the message itself. Handle them separately.
            )
            
            Timber.d("UpdateDraft: Patching core message details for draft $messageId. Payload: $messagePatchPayload")
            val patchResponse: HttpResponse = httpClient.patch("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                contentType(ContentType.Application.Json)
                setBody(messagePatchPayload)
            }
            val patchResponseBodyText = patchResponse.bodyAsText()

            if (!patchResponse.status.isSuccess()) {
                Timber.e("Error patching draft $messageId: ${patchResponse.status} - Body: $patchResponseBodyText")
                val httpEx = ClientRequestException(patchResponse, patchResponseBodyText)
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }
            Timber.i("Draft $messageId core details patched successfully.")

            // Step 4: Add NEW small attachments (POST to /attachments endpoint)
            smallAttachmentsForRequest.forEach { smallAttRequest ->
                 Timber.d("UpdateDraft: Adding new small attachment '${smallAttRequest.name}' to draft $messageId.")
                 val addSmallAttResponse: HttpResponse = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/attachments") {
                     contentType(ContentType.Application.Json)
                     setBody(smallAttRequest)
                 }
                 if (!addSmallAttResponse.status.isSuccess()) { // Expect 201 Created
                     Timber.w("UpdateDraft: Failed to add new small attachment ${smallAttRequest.name} to draft $messageId. Status: ${addSmallAttResponse.status}. Body: ${addSmallAttResponse.bodyAsText()}. Continuing...")
                     // Decide if this is a hard failure or continue
                 } else {
                     Timber.i("UpdateDraft: Successfully added new small attachment ${smallAttRequest.name} to draft $messageId.")
                 }
            }

            // Step 5: Upload NEW large attachments
            for (largeAtt in largeAttachmentsToUpload) { // These are identified as new
                if (largeAtt.localUri.isNullOrBlank()) continue
                val fileSize = getFileSizeBytes(largeAtt.localUri!!)
                if (fileSize == 0L) continue

                Timber.d("UpdateDraft: Processing new large attachment: ${largeAtt.fileName}, size: $fileSize for draft $messageId")
                val correlatedNameForLargeAtt = createCorrelatedName(largeAtt.fileName, largeAtt.id)
                val sessionResult = createUploadSessionInternal(messageId, correlatedNameForLargeAtt, fileSize, largeAtt.contentType)
                if (sessionResult.isFailure) {
                    val error = sessionResult.exceptionOrNull() ?: Exception("Upload session creation failed for new large attachment ${largeAtt.fileName}")
                    Timber.e(error, "Failed to create upload session for new large attachment ${largeAtt.fileName} on draft $messageId.")
                    return@withContext Result.failure(error) // If adding a new large attachment fails, consider it a failure for the update.
                }
                val uploadSession = sessionResult.getOrThrow()

                val uploadResult = uploadAttachmentInChunks(uploadSession.uploadUrl, largeAtt.localUri!!, fileSize)
                if (uploadResult.isFailure) {
                     val error = uploadResult.exceptionOrNull() ?: Exception("Chunk upload failed for new large attachment ${largeAtt.fileName}")
                     Timber.e(error, "Failed to upload chunks for new large attachment ${largeAtt.fileName} on draft $messageId.")
                     return@withContext Result.failure(error)
                }
                Timber.i("UpdateDraft: New large attachment ${largeAtt.fileName} uploaded successfully to draft $messageId.")
            }

            // Step 6: Fetch the final state of the draft message
            val finalMessageResult = getMessageContent(messageId)
             if (finalMessageResult.isFailure) {
                 Timber.e(finalMessageResult.exceptionOrNull(), "UpdateDraft: Failed to fetch final state of draft $messageId after update.")
                 return@withContext Result.failure(finalMessageResult.exceptionOrNull() ?: Exception("Failed to fetch final draft state post-update"))
            }
            var finalMessage = finalMessageResult.getOrThrow()
            
            val finalAttachmentsResult = getMessageAttachmentsInternal(messageId, accountId)
            if (finalAttachmentsResult.isSuccess) {
                finalMessage = finalMessage.copy(attachments = finalAttachmentsResult.getOrThrow())
            } else {
                 Timber.w(finalAttachmentsResult.exceptionOrNull(), "UpdateDraft: Could not fetch final attachments for draft $messageId, list may be incomplete.")
            }

            Timber.i("Draft $messageId updated and attachments processed successfully.")
            Result.success(finalMessage)

        } catch (e: Exception) {
            Timber.e(e, "Exception updating draft message $messageId in GraphApiHelper.")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    private suspend fun createUploadSessionInternal(messageId: String, correlatedAttachmentName: String, attachmentSize: Long, attachmentContentType: String?): Result<GraphAttachmentUploadSessionResponse> {
        Timber.d("createUploadSessionInternal for message $messageId, attachment $correlatedAttachmentName, size $attachmentSize")
        try {
            val request = GraphAttachmentUploadSessionRequest(
                attachmentItem = GraphAttachmentItem(
                    name = correlatedAttachmentName, // Use the correlated name provided
                    size = attachmentSize,
                    contentType = attachmentContentType ?: "application/octet-stream"
                    // attachmentType is "file" by default in DTO
                )
            )
            val response: HttpResponse = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/attachments/createUploadSession") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) { // Expect 200 OK for createUploadSession
                Timber.e("Error creating upload session for $correlatedAttachmentName on $messageId: ${response.status} - Body: $responseBodyText")
                val httpEx = ClientRequestException(response, responseBodyText)
                return Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }
            val sessionResponse = jsonParser.decodeFromString<GraphAttachmentUploadSessionResponse>(responseBodyText)
            Timber.i("Upload session created for $correlatedAttachmentName on $messageId. URL: ${sessionResponse.uploadUrl}")
            return Result.success(sessionResponse)
        } catch (e: Exception) {
            Timber.e(e, "Exception creating upload session for $correlatedAttachmentName on $messageId")
            return Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    private suspend fun uploadAttachmentInChunks(uploadUrl: String, attachmentFileUri: String, totalSize: Long): Result<Unit> = withContext(ioDispatcher) {
        Timber.d("uploadAttachmentInChunks: Uploading to $uploadUrl, file $attachmentFileUri, totalSize $totalSize")
        var bytesUploaded: Long = 0
        // Graph recommends chunk size multiples of 320 KiB (320 * 1024 bytes).
        // Max chunk size 4MB. Using a smaller chunk size for more granular progress if needed, e.g., 1MB.
        val chunkSize = minOf(totalSize, 1 * 1024 * 1024).toInt() // 1MB chunks, or less if file is smaller

        try {
            context.contentResolver.openInputStream(Uri.parse(attachmentFileUri))?.use { inputStream ->
                val buffer = ByteArray(chunkSize)
                var numBytesRead: Int

                while (bytesUploaded < totalSize) {
                    ensureActive() // Check for coroutine cancellation
                    numBytesRead = inputStream.read(buffer)
                    if (numBytesRead == -1) break // End of file

                    val currentChunk = if (numBytesRead == chunkSize) buffer else buffer.copyOf(numBytesRead)
                    val chunkEnd = bytesUploaded + numBytesRead - 1

                    Timber.d("Uploading chunk: bytes $bytesUploaded-$chunkEnd/$totalSize to $uploadUrl")

                    val response: HttpResponse = httpClient.put(uploadUrl) {
                        header(HttpHeaders.ContentLength, numBytesRead.toString())
                        header(HttpHeaders.ContentRange, "bytes $bytesUploaded-$chunkEnd/$totalSize")
                        setBody(ByteReadChannel(currentChunk)) // Ktor can take ByteReadChannel directly
                    }

                    if (!response.status.isSuccess()) { // Expect 200 OK or 201 Created for last chunk, 202 Accepted for others
                        val errorBody = response.bodyAsText()
                        Timber.e("Error uploading chunk $bytesUploaded-$chunkEnd: ${response.status} - $errorBody. URL: $uploadUrl")
                        return@withContext Result.failure(
                            IOException("Upload chunk failed: ${response.status} - $errorBody")
                        )
                    }
                    Timber.i("Chunk $bytesUploaded-$chunkEnd uploaded successfully. Status: ${response.status}")
                    bytesUploaded += numBytesRead

                    // Graph API sometimes returns nextExpectedRanges in response for large files,
                    // but for sequential upload, simply continuing is typical.
                }
            } ?: return@withContext Result.failure(IOException("Could not open input stream for URI: $attachmentFileUri"))

            if (bytesUploaded == totalSize) {
                Timber.i("File $attachmentFileUri uploaded completely to $uploadUrl.")
                Result.success(Unit)
            } else {
                Timber.e("File upload incomplete for $attachmentFileUri. Uploaded $bytesUploaded/$totalSize bytes.")
                Result.failure(IOException("File upload incomplete. Uploaded $bytesUploaded/$totalSize bytes."))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during chunked upload to $uploadUrl for file $attachmentFileUri")
            Result.failure(e)
        }
    }

    override suspend fun hasChangesSince(
        accountId: String,
        deltaToken: String?
    ): Result<Pair<Boolean, String?>> = withContext(ioDispatcher) {
        Timber.d("hasChangesSince (Graph) called for account $accountId. DeltaToken: $deltaToken")
        // Use /me/messages/delta to scope the delta query to the authenticated user.
        val requestUrl = deltaToken ?: "$MS_GRAPH_ROOT_ENDPOINT/me/messages/delta"

        try {
            val response: HttpResponse = httpClient.get(requestUrl) {
                accept(ContentType.Application.Json)
                // We only need one item to know if there are changes.
                // This is an optimization over fetching the whole first page.
                parameter("\$top", 1)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Timber.e("hasChangesSince (Graph): Error fetching delta: ${response.status} - Body: $errorBody")
                val httpEx = ClientRequestException(response, errorBody)
                return@withContext Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(httpEx)))
            }

            val deltaResponse = response.body<GraphDeltaResponse<KtorGraphMessage>>()
            val hasChanges = !deltaResponse.value.isNullOrEmpty()
            val nextToken = deltaResponse.deltaLink ?: deltaResponse.nextLink

            Timber.d("hasChangesSince (Graph): hasChanges=$hasChanges, nextToken=$nextToken")
            Result.success(hasChanges to nextToken)

        } catch (e: Exception) {
            Timber.e(e, "Generic exception during hasChangesSince (Graph) for account $accountId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }
}