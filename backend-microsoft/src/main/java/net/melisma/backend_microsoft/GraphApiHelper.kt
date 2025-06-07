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
    val isRead: Boolean? = true, // Made nullable for delta
    val bodyPreview: String? = null,
    val body: KtorGraphItemBody? = null,
    val hasAttachments: Boolean? = null,
    val flag: KtorGraphFlag? = null, // Represents the 'starred' status via its flagStatus field
    @SerialName("@removed") val removed: GraphRemovedReason? = null // For delta sync
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

@Singleton
class GraphApiHelper @Inject constructor(
    @MicrosoftGraphHttpClient private val httpClient: HttpClient, // For Ktor-based calls
    private val errorMapper: MicrosoftErrorMapper,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val credentialStore: MicrosoftAuthUserCredentials
) : MailApiService {

    companion object {
        private const val MS_GRAPH_ROOT_ENDPOINT = "https://graph.microsoft.com/v1.0"
        private const val WKNAME_INBOX = "inbox"
        private const val WKNAME_SENTITEMS = "sentitems"
        private const val WKNAME_DRAFTS = "drafts"
        private const val WKNAME_ARCHIVE = "archive"
        private const val WKNAME_DELETEDITEMS = "deleteditems"
        private const val WKNAME_JUNKEMAIL = "junkemail"
        private const val WKNAME_NOTES = "notes"
        private const val WKNAME_SYNCISSUES = "syncissues"
        private const val APP_DISPLAY_NAME_INBOX = "Inbox"
        private const val APP_DISPLAY_NAME_SENT_ITEMS = "Sent Items"
        private const val APP_DISPLAY_NAME_DRAFTS = "Drafts"
        private const val APP_DISPLAY_NAME_ARCHIVE = "Archive"
        private const val APP_DISPLAY_NAME_TRASH = "Trash"
        private const val APP_DISPLAY_NAME_SPAM = "Spam"
        private const val DEFAULT_MAX_RESULTS = 20

        // These select fields are for Ktor calls now. Ensure they are valid for the API without the SDK.
        private const val MESSAGE_DEFAULT_SELECT_FIELDS =
            "id,conversationId,receivedDateTime,sentDateTime,subject,sender,from,toRecipients,isRead,bodyPreview,hasAttachments,flag"
        private const val MESSAGE_FULL_SELECT_FIELDS = MESSAGE_DEFAULT_SELECT_FIELDS + ",body"
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
                        "id,displayName,totalItemCount,unreadItemCount"
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
        val displayNameLower = graphFolder.displayName?.lowercase() // For fallback matching

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
                return null
            } // Hide
            else -> {
                // Fallback to display name matching for common folders if wellKnownName is not definitive
                type = when (displayNameLower) {
                    "inbox" -> WellKnownFolderType.INBOX.also {
                        finalDisplayName = APP_DISPLAY_NAME_INBOX
                    }

                    "sent items" -> WellKnownFolderType.SENT_ITEMS.also {
                        finalDisplayName = APP_DISPLAY_NAME_SENT_ITEMS
                    }

                    "drafts" -> WellKnownFolderType.DRAFTS.also {
                        finalDisplayName = APP_DISPLAY_NAME_DRAFTS
                    }

                    "archive" -> WellKnownFolderType.ARCHIVE.also {
                        finalDisplayName = APP_DISPLAY_NAME_ARCHIVE
                    }

                    "deleted items", "trash" -> WellKnownFolderType.TRASH.also {
                        finalDisplayName = APP_DISPLAY_NAME_TRASH
                    }

                    "junk email", "junk e-mail", "spam" -> WellKnownFolderType.SPAM.also {
                        finalDisplayName = APP_DISPLAY_NAME_SPAM
                    }

                    "notes", "conversation history", "quick step settings", "rss feeds" -> return null // Hide
                    else -> WellKnownFolderType.USER_CREATED
                }
            }
        }
        return MailFolder(
            id = graphFolder.id,
            displayName = finalDisplayName,
            totalItemCount = graphFolder.totalItemCount ?: 0, // Default to 0 if null
            unreadItemCount = graphFolder.unreadItemCount ?: 0, // Default to 0 if null
            type = type
        )
    }

    // Mapper for Ktor-parsed KtorGraphMessage to domain Message using fromApi factory
    private fun mapKtorGraphMessageToDomainMessage(
        ktorGraphMessage: KtorGraphMessage,
        accountId: String,
        folderId: String // Contextual folderId, might be from list view
    ): Message {
        val senderName = ktorGraphMessage.sender?.emailAddress?.name ?: ktorGraphMessage.from?.emailAddress?.name
        val senderAddress = ktorGraphMessage.sender?.emailAddress?.address ?: ktorGraphMessage.from?.emailAddress?.address

        val recipientAddresses = ktorGraphMessage.toRecipients?.mapNotNull {
            it.emailAddress?.address
        } ?: emptyList()
        val recipientNames = ktorGraphMessage.toRecipients?.mapNotNull {
            it.emailAddress?.name
        } ?: emptyList()

        // TODO: Map attachments if KtorGraphMessage includes them
        // val attachments = mapAttachments(ktorGraphMessage.attachments, ktorGraphMessage.id, accountId)

        return Message.fromApi(
            id = ktorGraphMessage.id, // Graph message ID
            remoteId = ktorGraphMessage.id, // Explicitly set remoteId to Graph message ID
            accountId = accountId,
            folderId = folderId, // This folderId is the context from where it was listed/fetched
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
            attachments = emptyList(), // Placeholder, attachments should be mapped if available
            lastSuccessfulSyncTimestamp = System.currentTimeMillis() // Freshly fetched from API
        )
    }

    override suspend fun getMessagesForFolder(
        folderId: String,
        activity: android.app.Activity?,
        maxResults: Int?,
        pageToken: String?,
        earliestTimestampEpochMillis: Long?
    ): Result<PagedMessagesResponse> = withContext(ioDispatcher) {
        Timber.d("getMessagesForFolder Ktor: folderId='$folderId', pageToken='$pageToken', earliestTimestamp='$earliestTimestampEpochMillis'")
        try {
            val activeAccountId = credentialStore.getActiveAccountId()
            if (activeAccountId == null) {
                Timber.e("No active account ID found. Cannot fetch messages for folder $folderId")
                return@withContext Result.failure(
                    ApiServiceException(
                        ErrorDetails(
                            message = "No active account found to fetch messages.",
                            code = "AUTH_NO_ACTIVE_ACCOUNT"
                        )
                    )
                )
            }

            val actualMaxResults = maxResults ?: DEFAULT_MAX_RESULTS
            val requestUrl =
                pageToken ?: "$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/$folderId/messages"

            val response: HttpResponse = httpClient.get(requestUrl) {
                if (pageToken == null) {
                    url {
                        parameters.append("\$top", actualMaxResults.toString())
                        parameters.append("\$select", MESSAGE_DEFAULT_SELECT_FIELDS)
                        if (earliestTimestampEpochMillis != null && earliestTimestampEpochMillis > 0) {
                            // Format epoch millis to ISO 8601 UTC string (e.g., "2023-10-27T10:00:00Z")
                            val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                            isoFormatter.timeZone = TimeZone.getTimeZone("UTC")
                            val isoDateString = isoFormatter.format(Date(earliestTimestampEpochMillis))
                            parameters.append("\$filter", "receivedDateTime ge $isoDateString")
                            Timber.d("Applying date filter to Graph messages: \$filter=receivedDateTime ge $isoDateString")
                        }
                    }
                }
                accept(ContentType.Application.Json)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                val httpEx = ClientRequestException(response, errorBody)
                Timber.e(httpEx, "getMessagesForFolder Ktor: Error ${response.status}")
                return@withContext Result.failure(
                    ApiServiceException(
                        errorMapper.mapExceptionToErrorDetails(
                            httpEx
                        )
                    )
                )
            }

            val graphResponse = response.body<GraphCollection<KtorGraphMessage>>()
            val ktorGraphMessages = graphResponse.value
            val nextLinkForNextPage = graphResponse.nextLink

            val domainMessages = ktorGraphMessages.mapNotNull {
                mapKtorGraphMessageToDomainMessage(
                    it,
                    activeAccountId,
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
            Timber.e(e, "getMessagesForFolder Ktor: Exception for folderId '$folderId'")
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
                    url {
                        parameter("\$select", MESSAGE_FULL_SELECT_FIELDS) // Ensure body is selected
                    }
                    accept(ContentType.Application.Json)
                    header("Prefer", "outlook.body-content-type=\"html\"") // Request HTML body
                }
                val responseBodyText = response.bodyAsText()

                if (!response.status.isSuccess()) {
                    Timber.e("Error fetching message content $messageId: ${response.status} - Body: $responseBodyText")
                    val httpEx = ClientRequestException(response, responseBodyText)
                    val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                    return@withContext Result.failure(ApiServiceException(mappedDetails))
                }

                val ktorGraphMessage = jsonParser.decodeFromString<KtorGraphMessage>(responseBodyText)
                
                // For getMessageContent, folderId might not be known. Pass empty string.
                // Worker should use existing folderId from DB when updating local entity.
                val domainMessage = mapKtorGraphMessageToDomainMessage(ktorGraphMessage, accountId, "") 

                Timber.i("Successfully fetched and parsed message content $messageId. Subject: ${domainMessage.subject}")
                Result.success(domainMessage)

            } catch (e: Exception) {
                Timber.e(e, "Exception fetching message content $messageId from Graph")
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
                Result.failure(ApiServiceException(mappedDetails))
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
        Timber.w("getMessageDetails: MS Graph SDK removed. Not implemented with Ktor.")
        throw NotImplementedError("getMessageDetails requires Ktor reimplementation as MS Graph SDK was removed.")
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

    override suspend fun markThreadRead(threadId: String, isRead: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
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

    override suspend fun deleteThread(threadId: String): Result<Unit> =
        withContext(ioDispatcher) {
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

    override suspend fun getMessageAttachments(messageId: String): Result<List<net.melisma.core_data.model.Attachment>> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("getMessageAttachments not implemented (SDK removed)")) }

    override suspend fun downloadAttachment(
        messageId: String,
        attachmentId: String
    ): Result<ByteArray> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("downloadAttachment not implemented (SDK removed)")) }

    override suspend fun createDraftMessage(draft: MessageDraft): Result<Message> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("createDraftMessage not implemented (SDK removed)")) }

    override suspend fun updateDraftMessage(
        messageId: String,
        draft: MessageDraft
    ): Result<Message> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("updateDraftMessage not implemented (SDK removed)")) }

    override suspend fun sendMessage(draft: MessageDraft): Result<String> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("sendMessage not implemented (SDK removed)")) }

    override suspend fun searchMessages(
        query: String,
        folderId: String?,
        maxResults: Int
    ): Result<List<Message>> {
        throw NotImplementedError("Search messages not implemented for Graph yet.")
    }

    // Stub implementation for new delta sync method for folders
    override suspend fun syncFolders(
        accountId: String,
        syncToken: String?
    ): Result<DeltaSyncResult<MailFolder>> = withContext(ioDispatcher) {
        Timber.i("syncFolders (Graph) called for account $accountId. SyncToken (deltaLink): $syncToken")
        try {
            val collectedNewOrUpdatedFolders = mutableListOf<MailFolder>()
            val collectedDeletedFolderIds = mutableListOf<String>()

            // Initial URL must be non-null. syncToken is String?, fallback is String.
            val initialRequestUrl: String =
                syncToken ?: "$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/delta"
            var currentRequestUrlAsNullable: String? =
                initialRequestUrl // Explicitly String? for loop control
            var finalDeltaLinkForNextSync: String? =
                syncToken // Initialize with incoming, update with last page's deltaLink

            Timber.d("Starting Graph folder delta sync. Initial URL: $initialRequestUrl")

            do {
                val urlToFetch =
                    currentRequestUrlAsNullable!! // Safe due to loop condition and initialization
                val response: HttpResponse = httpClient.get(urlToFetch) {
                    accept(ContentType.Application.Json)
                    // MS Graph recommends specific headers for delta queries, like Prefer: odata.track-changes
                    // For simplicity, Ktor defaults might be okay, but for production, review MS Graph docs.
                }

                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Timber.e("syncFolders (Graph): Error fetching delta: ${response.status} - Body: $errorBody")
                    val httpEx = ClientRequestException(response, errorBody)
                    return@withContext Result.failure(
                        ApiServiceException(
                            errorMapper.mapExceptionToErrorDetails(
                                httpEx
                            )
                        )
                    )
                }

                val deltaResponse = response.body<GraphDeltaResponse<GraphMailFolder>>()
                Timber.v("Graph folder delta page received. NextLink: ${deltaResponse.nextLink}, DeltaLink: ${deltaResponse.deltaLink}")

                deltaResponse.value.forEach { graphFolder ->
                    if (graphFolder.removed != null) {
                        collectedDeletedFolderIds.add(graphFolder.id)
                        Timber.v("Folder ${graphFolder.id} marked as removed.")
                    } else {
                        if (graphFolder.displayName == null) {
                            Timber.w("Graph folder ${graphFolder.id} is not marked removed but has null displayName. Skipping.")
                        } else {
                            mapGraphFolderToMailFolder(graphFolder)?.let {
                                collectedNewOrUpdatedFolders.add(it)
                                Timber.v("Folder ${graphFolder.id} mapped as new/updated: ${it.displayName}")
                            }
                        }
                    }
                }

                deltaResponse.deltaLink?.let {
                    finalDeltaLinkForNextSync = it
                }
                currentRequestUrlAsNullable = deltaResponse.nextLink // Assign String? to String?

            } while (currentRequestUrlAsNullable != null)

            Timber.i("syncFolders (Graph): Completed. ${collectedNewOrUpdatedFolders.size} new/updated, ${collectedDeletedFolderIds.size} deleted. Next deltaLink: $finalDeltaLinkForNextSync")
            Result.success(
                DeltaSyncResult(
                    newOrUpdatedItems = collectedNewOrUpdatedFolders,
                    deletedItemIds = collectedDeletedFolderIds.distinct(), // Ensure distinct IDs
                    nextSyncToken = finalDeltaLinkForNextSync
                )
            )

        } catch (e: Exception) {
            Timber.e(e, "syncFolders (Graph): Exception for accountId $accountId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    // Stub implementation for new delta sync method for messages
    override suspend fun syncMessagesForFolder(
        folderId: String,
        syncToken: String?,
        maxResults: Int?,
        earliestTimestampEpochMillis: Long?
    ): Result<DeltaSyncResult<Message>> = withContext(ioDispatcher) {
        Timber.i("syncMessagesForFolder (Graph) called for folderId: $folderId. SyncToken (deltaLink): $syncToken, earliestTimestamp: $earliestTimestampEpochMillis, maxResultsHint: $maxResults")
        if (earliestTimestampEpochMillis != null) {
            Timber.w("syncMessagesForFolder (Graph): earliestTimestampEpochMillis ($earliestTimestampEpochMillis) is provided but currently unused by the deltaLink-based sync logic.")
        }
        try {
            val accountId = credentialStore.getActiveAccountId() ?: throw ApiServiceException(
                errorMapper.mapExceptionToErrorDetails(IllegalStateException("No active Microsoft account found for syncMessagesForFolder"))
            )

            if (syncToken == null) {
                // Initial call to get the first deltaLink.
                // The messages on this first page are ignored; caller should have already done a full sync.
                val initialDeltaUrl =
                    "$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/$folderId/messages/delta"
                Timber.d("syncMessagesForFolder (Graph): syncToken is null. Fetching initial deltaLink from: $initialDeltaUrl")

                val response: HttpResponse = httpClient.get(initialDeltaUrl) {
                    accept(ContentType.Application.Json)
                    maxResults?.let { parameter("\$top", it.toString()) }
                }

                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Timber.e("syncMessagesForFolder (Graph): Error fetching initial delta link for $folderId: ${response.status} - Body: $errorBody")
                    throw ApiServiceException(
                        errorMapper.mapExceptionToErrorDetails(
                            ClientRequestException(response, errorBody)
                        )
                    )
                }
                val deltaResponse = response.body<GraphDeltaResponse<KtorGraphMessage>>()
                val initialDeltaLink = deltaResponse.deltaLink
                Timber.i("syncMessagesForFolder (Graph): Successfully fetched initial deltaLink for folder $folderId: $initialDeltaLink")
                return@withContext Result.success(
                    DeltaSyncResult(
                        newOrUpdatedItems = emptyList(),
                        deletedItemIds = emptyList(),
                        nextSyncToken = initialDeltaLink
                    )
                )
            }

            // Actual delta sync using provided deltaLink (syncToken)
            val collectedNewOrUpdatedMessages = mutableListOf<Message>()
            val collectedDeletedMessageIds = mutableListOf<String>()
            var currentRequestUrl: String? = syncToken // Start with the provided deltaLink
            var finalDeltaLinkForNextSync: String? =
                syncToken // Initialize, will be updated by the last page's deltaLink

            Timber.d("Starting Graph message delta sync for folder $folderId. Initial URL: $currentRequestUrl")

            while (currentRequestUrl != null) { // Loop as long as there is a nextLink or it's the first deltaLink
                val requestUrlForLoop =
                    currentRequestUrl!! // Use !! as currentRequestUrl is confirmed non-null by the loop condition
                val response: HttpResponse = httpClient.get(requestUrlForLoop) {
                    accept(ContentType.Application.Json)
                }

                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Timber.e("syncMessagesForFolder (Graph): Error fetching delta page: ${response.status} - Body: $errorBody")
                    throw ApiServiceException(
                        errorMapper.mapExceptionToErrorDetails(
                            ClientRequestException(response, errorBody)
                        )
                    )
                }

                val deltaResponse = response.body<GraphDeltaResponse<KtorGraphMessage>>()
                Timber.v("Graph message delta page received. NextLink: ${deltaResponse.nextLink}, DeltaLink: ${deltaResponse.deltaLink}")

                deltaResponse.value.forEach { ktorGraphMessage ->
                    if (ktorGraphMessage.removed != null) {
                        collectedDeletedMessageIds.add(ktorGraphMessage.id)
                        Timber.v("Message ${ktorGraphMessage.id} in folder $folderId marked as removed.")
                    } else {
                        mapKtorGraphMessageToDomainMessage(
                            ktorGraphMessage,
                            accountId,
                            folderId
                        )?.let {
                            collectedNewOrUpdatedMessages.add(it)
                            Timber.v("Message ${ktorGraphMessage.id} mapped as new/updated for folder $folderId: ${it.subject}")
                        }
                    }
                }

                deltaResponse.deltaLink?.let {
                    finalDeltaLinkForNextSync = it
                }
                currentRequestUrl = deltaResponse.nextLink
            }

            Timber.i("syncMessagesForFolder (Graph): Completed delta for folder $folderId. ${collectedNewOrUpdatedMessages.size} new/updated, ${collectedDeletedMessageIds.size} deleted. Next deltaLink: $finalDeltaLinkForNextSync")
            Result.success(
                DeltaSyncResult(
                    newOrUpdatedItems = collectedNewOrUpdatedMessages,
                    deletedItemIds = collectedDeletedMessageIds.distinct(),
                    nextSyncToken = finalDeltaLinkForNextSync
                )
            )

        } catch (e: Exception) {
            Timber.e(e, "syncMessagesForFolder (Graph): Exception for folderId $folderId")
            Result.failure(ApiServiceException(errorMapper.mapExceptionToErrorDetails(e)))
        }
    }

    override suspend fun getMessage(messageRemoteId: String): Result<Message> = withContext(ioDispatcher) {
        Timber.d("GraphApiHelper: Fetching message with remoteId: $messageRemoteId")
        val accountId = credentialStore.getActiveAccountId()
            ?: return@withContext Result.failure(ApiServiceException(ErrorDetails(message = "User ID not found for Graph API call", code = "AUTH_NO_ACTIVE_ACCOUNT")))

        try {
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageRemoteId") {
                url {
                    // Request full body and other details we might need
                    parameter("\$select", MESSAGE_FULL_SELECT_FIELDS)
                }
                accept(ContentType.Application.Json)
            }
            val responseBodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                Timber.e("Error fetching message $messageRemoteId: ${response.status} - Body: $responseBodyText")
                val httpEx = ClientRequestException(response, responseBodyText)
                val mappedDetails = errorMapper.mapExceptionToErrorDetails(httpEx)
                return@withContext Result.failure(ApiServiceException(mappedDetails))
            }

            val ktorGraphMessage = jsonParser.decodeFromString<KtorGraphMessage>(responseBodyText)

            // Attempt to find the parent folder ID for context. This is best-effort.
            // Graph API doesn't directly return parentFolderId in message object by default with $select.
            // We might need another call or rely on it being part of a broader sync context if crucial.
            // For a direct getMessage, we might not have a strong folderId context readily available from this call alone.
            // Let's pass a placeholder or try to infer if possible, but it might often be null/default.
            // A more robust way would be to fetch mailboxSettings or user settings for default folder IDs if needed.
            // For now, using null as the folder context is the most straightforward for a direct getMessage call.
            val folderContextId = ktorGraphMessage.id // Placeholder: graph message id is not folder id. For now, need a better way if folder context is critical here.
                                                  // Actual folder ID is not directly available in single message fetch without more context or another call.
                                                  // For parsing, this folderId is used to associate the message in our domain model.
                                                  // If the message is being refreshed, its existing folderId from DB should be preserved by the worker.
                                                  // Let's pass a known one or null if not applicable.
                                                  // Using "" as placeholder, worker should use existing folderId from DB.

            val domainMessage = mapKtorGraphMessageToDomainMessage(ktorGraphMessage, accountId, "") // Passing empty string for folderId context

            Timber.i("Successfully fetched and parsed message $messageRemoteId from Graph. Subject: ${domainMessage.subject}")
            Result.success(domainMessage)

        } catch (e: Exception) {
            Timber.e(e, "Exception fetching message $messageRemoteId from Graph")
            val mappedDetails = errorMapper.mapExceptionToErrorDetails(e)
            Result.failure(ApiServiceException(mappedDetails))
        }
    }

    private val jsonParser = kotlinx.serialization.json.Json {
        // ... existing code ...
    }
}

// Placeholder for MicrosoftAuthUserCredentials if it was still here
// interface MicrosoftAuthUserCredentials { 
//     suspend fun getAccessToken(): String?
//     suspend fun getActiveAccountId(): String? // Added this based on prior assumption
// }

// Placeholder for GraphServiceClients if it was defined here (it shouldn't be)
// object GraphServiceClients {
//     fun getGraphClient(accessToken: String): GraphServiceClient<okhttp3.Request> {
//         throw NotImplementedError("GraphServiceClients.getGraphClient needs to be implemented based on project setup.")
//     }
// }