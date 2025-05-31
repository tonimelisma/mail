package net.melisma.backend_microsoft

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.melisma.backend_microsoft.di.MicrosoftGraphHttpClient
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.WellKnownFolderType
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class GraphCollection<T>(
    @SerialName("@odata.context") val context: String? = null,
    val value: List<T>,
    @SerialName("@odata.nextLink") val nextLink: String? = null
)

@Serializable
private data class GraphMailFolder(
    val id: String,
    val displayName: String,
    val totalItemCount: Int = 0,
    val unreadItemCount: Int = 0,
    val wellKnownName: String? = null // Keep for parsing if API returns it by default
)

@Serializable
private data class GraphItemBody(
    val contentType: String? = null, // "text" or "html"
    val content: String? = null
)

@Serializable
private data class GraphMessage(
    val id: String,
    val conversationId: String?,
    val receivedDateTime: String? = null,
    val sentDateTime: String? = null,
    val subject: String? = null,
    val sender: GraphRecipient? = null,
    val from: GraphRecipient? = null,
    val toRecipients: List<GraphRecipient> = emptyList(),
    val isRead: Boolean = true,
    val bodyPreview: String? = null,
    val body: GraphItemBody? = null,
    val hasAttachments: Boolean? = null,
    val flag: GraphFlag? = null
)

@Serializable
private data class GraphFlag(
    val flagStatus: String? = null
)

@Serializable
private data class GraphRecipient(
    val emailAddress: GraphEmailAddress? = null
)

@Serializable
private data class GraphEmailAddress(
    val name: String? = null,
    val address: String? = null
)

@Serializable
private data class GraphAttachment(
    val id: String,
    val name: String? = null,
    val contentType: String? = null,
    val size: Long? = null,
    val contentId: String? = null,
    val isInline: Boolean? = null
)

@Singleton
class GraphApiHelper @Inject constructor(
    @MicrosoftGraphHttpClient private val httpClient: HttpClient,
    private val errorMapper: MicrosoftErrorMapper
) : MailApiService {

    companion object {
        private const val TAG = "GraphApiHelper"
        private const val MS_GRAPH_ROOT_ENDPOINT = "https://graph.microsoft.com/v1.0"

        // Actual wellKnownName values from Microsoft Graph (lowercase for matching)
        private const val WKNAME_INBOX = "inbox"
        private const val WKNAME_SENTITEMS = "sentitems"
        private const val WKNAME_DRAFTS = "drafts"
        private const val WKNAME_ARCHIVE = "archive"
        private const val WKNAME_DELETEDITEMS = "deleteditems"
        private const val WKNAME_JUNKEMAIL = "junkemail"
        private const val WKNAME_NOTES = "notes"
        private const val WKNAME_SYNCISSUES = "syncissues" // Example, if you want to handle it
        // You might find more at: https://learn.microsoft.com/en-us/graph/api/resources/mailfolder?view=graph-rest-1.0 (look for wellKnownName property)

        // Standardized display names for the app
        private const val APP_DISPLAY_NAME_INBOX = "Inbox"
        private const val APP_DISPLAY_NAME_SENT_ITEMS = "Sent Items"
        private const val APP_DISPLAY_NAME_DRAFTS = "Drafts"
        private const val APP_DISPLAY_NAME_ARCHIVE = "Archive"
        private const val APP_DISPLAY_NAME_TRASH = "Trash"
        private const val APP_DISPLAY_NAME_SPAM = "Spam"
    }

    override suspend fun getMailFolders(
        activity: android.app.Activity?,
        accountId: String
    ): Result<List<MailFolder>> {
        return try {
            Log.d(TAG, "Fetching mail folders for accountId: $accountId...")
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders") {
                url {
                    parameters.append("\$top", "100")
                    // Do not explicitly select wellKnownName to avoid the BadRequest
                    // The API might return it by default for some folders.
                    parameters.append("\$select", "id,displayName,totalItemCount,unreadItemCount")
                }
                accept(ContentType.Application.Json)
            }

            if (response.status.isSuccess()) {
                val graphFolders = response.body<GraphCollection<GraphMailFolder>>().value
                val mailFolders = graphFolders
                    .mapNotNull { mapGraphFolderToMailFolder(it) }
                    .sortedBy { it.displayName }

                Log.d(
                    TAG,
                    "Successfully fetched and processed ${mailFolders.size} visible folders."
                )
                Result.success(mailFolders)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching folders: ${response.status} - Error details in API response."
                )
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching folders", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    private fun mapGraphFolderToMailFolder(graphFolder: GraphMailFolder): MailFolder? {
        val type: WellKnownFolderType
        var finalDisplayName = graphFolder.displayName // Default to original

        val wellKnownNameLower = graphFolder.wellKnownName?.lowercase()
        val displayNameLower = graphFolder.displayName.lowercase()

        // Prioritize wellKnownName if available and matched
        when (wellKnownNameLower) {
            WKNAME_INBOX -> {
                type = WellKnownFolderType.INBOX
                finalDisplayName = APP_DISPLAY_NAME_INBOX
            }

            WKNAME_SENTITEMS -> {
                type = WellKnownFolderType.SENT_ITEMS
                finalDisplayName = APP_DISPLAY_NAME_SENT_ITEMS
            }

            WKNAME_DRAFTS -> {
                type = WellKnownFolderType.DRAFTS
                finalDisplayName = APP_DISPLAY_NAME_DRAFTS
            }

            WKNAME_ARCHIVE -> {
                type = WellKnownFolderType.ARCHIVE
                finalDisplayName = APP_DISPLAY_NAME_ARCHIVE
            }

            WKNAME_DELETEDITEMS -> {
                type = WellKnownFolderType.TRASH
                finalDisplayName = APP_DISPLAY_NAME_TRASH
            }

            WKNAME_JUNKEMAIL -> {
                type = WellKnownFolderType.SPAM
                finalDisplayName = APP_DISPLAY_NAME_SPAM
            }

            WKNAME_NOTES, WKNAME_SYNCISSUES -> { // Explicitly hide these if identified by wellKnownName
                Log.d(
                    TAG,
                    "Hiding Microsoft system folder (by wellKnownName): ${graphFolder.displayName} (wellKnownName: ${graphFolder.wellKnownName})"
                )
                return null
            }

            else -> {
                // If wellKnownName didn't match or wasn't present, try mapping by displayName
                when (displayNameLower) {
                    "inbox" -> {
                        type = WellKnownFolderType.INBOX; finalDisplayName = APP_DISPLAY_NAME_INBOX
                    }

                    "sent items" -> {
                        type = WellKnownFolderType.SENT_ITEMS; finalDisplayName =
                            APP_DISPLAY_NAME_SENT_ITEMS
                    }

                    "drafts" -> {
                        type = WellKnownFolderType.DRAFTS; finalDisplayName =
                            APP_DISPLAY_NAME_DRAFTS
                    }

                    "archive" -> {
                        type = WellKnownFolderType.ARCHIVE; finalDisplayName =
                            APP_DISPLAY_NAME_ARCHIVE
                    }

                    "deleted items" -> {
                        type = WellKnownFolderType.TRASH; finalDisplayName = APP_DISPLAY_NAME_TRASH
                    }

                    "junk email", "junk e-mail" -> {
                        type = WellKnownFolderType.SPAM; finalDisplayName = APP_DISPLAY_NAME_SPAM
                    }

                    "notes", "conversation history", "quick step settings", "rss feeds" -> {
                        Log.d(
                            TAG,
                            "Hiding Microsoft folder (by displayName): ${graphFolder.displayName}"
                        )
                        return null
                    }

                    else -> type = WellKnownFolderType.USER_CREATED
                }
            }
        }

        Log.v(
            TAG,
            "Mapping MS folder: ID='${graphFolder.id}', OrigName='${graphFolder.displayName}', WKN='${graphFolder.wellKnownName}' -> AppName='$finalDisplayName', AppType='$type'"
        )

        return MailFolder(
            id = graphFolder.id,
            displayName = finalDisplayName,
            totalItemCount = graphFolder.totalItemCount,
            unreadItemCount = graphFolder.unreadItemCount,
            type = type
        )
    }

    override suspend fun getMessagesForFolder(
        folderId: String,
        selectFields: List<String>,
        maxResults: Int
    ): Result<List<Message>> {
        Log.d(
            TAG,
            "GraphApiHelper: getMessagesForFolder called for folderId: $folderId with selectFields: $selectFields, maxResults: $maxResults"
        )
        return try {
            Log.d(TAG, "Fetching messages for folder: $folderId")
            val response: HttpResponse =
                httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/$folderId/messages") {
                    url {
                        parameters.append("\$select", selectFields.joinToString(","))
                        parameters.append("\$top", maxResults.toString())
                        parameters.append("\$orderby", "receivedDateTime desc")
                    }
                    accept(ContentType.Application.Json)
                }

            if (response.status.isSuccess()) {
                val graphMessages = response.body<GraphCollection<GraphMessage>>().value
                Log.d(
                    TAG,
                    "GraphApiHelper: Fetched ${graphMessages.size} GraphMessages for folder $folderId."
                )
                if (graphMessages.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "GraphApiHelper: First 3 GraphMessages (or fewer) for folder $folderId: " +
                                graphMessages.take(3)
                                    .joinToString { "MsgID: ${it.id}, ConvID: ${it.conversationId}" })
                }
                val messages = graphMessages.mapNotNull { mapGraphMessageToMessage(it) }
                Log.d(TAG, "Successfully fetched ${messages.size} messages for folder $folderId.")
                Result.success(messages)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching messages for $folderId: ${response.status} - Error details in API response."
                )
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching messages for folder $folderId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    private fun mapGraphMessageToMessage(graphMessage: GraphMessage): Message {
        Log.v(
            TAG,
            "GraphApiHelper: mapGraphMessageToMessage - MsgID: ${graphMessage.id}, ConvID: ${graphMessage.conversationId}, Received: ${graphMessage.receivedDateTime}, Sent: ${graphMessage.sentDateTime}"
        )
        val sender = graphMessage.sender
        val from = graphMessage.from
        val effectiveSender = from ?: sender

        val formattedReceivedDateTime = graphMessage.receivedDateTime?.let { parseOutlookDate(it) }
            ?.let { formatDateToIsoString(it) } ?: graphMessage.receivedDateTime
        val formattedSentDateTime = graphMessage.sentDateTime?.let { parseOutlookDate(it) }
            ?.let { formatDateToIsoString(it) } ?: graphMessage.sentDateTime

        val bodyContent = graphMessage.body?.content
        // Determine content type, default to text if not specified or if body is null
        val bodyContentType = (graphMessage.body as? GraphItemBody)?.contentType?.let {
            if (it.equals("html", ignoreCase = true)) "text/html" else "text/plain"
        } ?: "text/plain"

        Log.d(
            TAG,
            "Mapping GraphMessage: ID='${graphMessage.id}', Subject='${graphMessage.subject}', Preview='${graphMessage.bodyPreview}', HasFullBodyObject: ${graphMessage.body != null}, BodyContentType (from API): ${graphMessage.body?.contentType}, MappedContentType: $bodyContentType, BodyContentIsEmpty: ${bodyContent.isNullOrEmpty()}"
        )

        return Message(
            id = graphMessage.id,
            threadId = graphMessage.conversationId,
            receivedDateTime = formattedReceivedDateTime
                ?: "",
            sentDateTime = formattedSentDateTime, 
            subject = graphMessage.subject,
            senderName = effectiveSender?.emailAddress?.name,
            senderAddress = effectiveSender?.emailAddress?.address,
            // Only use bodyPreview if body.content is null or empty, to avoid redundancy
            bodyPreview = if (bodyContent.isNullOrEmpty()) graphMessage.bodyPreview else null,
            isRead = graphMessage.isRead,
            body = bodyContent, // Use the extracted body content
            bodyContentType = bodyContentType // Use the determined content type
        )
    }

    // Helper to format Date object to "yyyy-MM-dd'T'HH:mm:ss'Z'" UTC string
    private fun formatDateToIsoString(date: java.util.Date): String {
        val outputDateFormat =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        outputDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return outputDateFormat.format(date)
    }

    private fun parseOutlookDate(dateStr: String): java.util.Date? {
        // Microsoft Graph typically uses ISO 8601 format.
        // Examples: "2014-01-01T00:00:00Z" or with milliseconds "2014-01-01T00:00:00.123Z"
        val formatters = listOf(
            java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'",
                java.util.Locale.US
            ), // With up to 7 millis
            java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                java.util.Locale.US
            ),     // With 3 millis
            java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                java.util.Locale.US
            )          // Without millis
        )
        for (formatter in formatters) {
            formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
            try {
                return formatter.parse(dateStr.trim())
            } catch (e: Exception) { /* Try next format */
            }
        }
        Log.w(TAG, "Could not parse Outlook date string: '$dateStr' with known formats.")
        return null
    }

    override suspend fun markMessageRead(messageId: String, isRead: Boolean): Result<Unit> {
        Log.d(TAG, "Marking message $messageId as isRead=$isRead")
        return try {
            val requestBody = buildJsonObject {
                put("isRead", isRead)
            }
            // Simplest possible Ktor PATCH with setBody
            val response: HttpResponse =
                httpClient.patch("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                    setBody(requestBody)
                    // NO explicit contentType(), headers{}, or accept() here.
                    // Relying entirely on ContentNegotiation plugin for Content-Type
                    // and defaultRequest for Accept if set at client level.
                }

            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully marked message $messageId as isRead=$isRead")
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error marking message $messageId: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception marking message $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun starMessage(messageId: String, isStarred: Boolean): Result<Unit> {
        Log.d(TAG, "starMessage: messageId=$messageId, isStarred=$isStarred")
        return try {
            val requestBody = buildJsonObject {
                put("flag", buildJsonObject {
                    put("flagStatus", if (isStarred) "flagged" else "notFlagged")
                })
            }
            // The ContentNegotiation plugin (configured with json) should automatically set
            // the Content-Type to application/json when setBody is called with a JsonObject.
            val response: HttpResponse =
                httpClient.patch("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                    setBody(requestBody) // Rely on ContentNegotiation
                    accept(ContentType.Application.Json) // Still want to accept JSON in response
                }

            if (response.status.isSuccess()) {
                Log.i(
                    TAG,
                    "Successfully starred/unstarred message $messageId as isStarred=$isStarred"
                )
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error starring/unstarring message $messageId: ${response.status} - $errorBody"
                )
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in starMessage for message $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> {
        Log.d(TAG, "Deleting message $messageId (moving to deleteditems)")
        return try {
            val response: HttpResponse =
                httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/move") {
                    setBody(buildJsonObject { put("destinationId", "deleteditems") })
                    // NO explicit contentType(), headers{}, or accept() here.
                    accept(ContentType.Application.Json) // Keeping accept for now, as it's separate from Content-Type for body
                }
            if (response.status.isSuccess()) { // isSuccess covers 2xx
                Log.d(TAG, "Successfully deleted (moved to deleteditems) message $messageId")
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error deleting message $messageId: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting message $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun moveMessage(
        messageId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        Log.d(TAG, "Moving message $messageId from $currentFolderId to $destinationFolderId")
        return try {
            val requestBody = buildJsonObject {
                put("destinationId", destinationFolderId)
            }
            val response: HttpResponse =
                httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/move") {
                setBody(requestBody)
                    // NO explicit contentType(), headers{}, or accept() here.
                    accept(ContentType.Application.Json) // Keeping accept for now
            }

            if (response.status.isSuccess()) { // Graph move returns 201 Created on success
                Log.d(TAG, "Successfully moved message $messageId to $destinationFolderId")
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error moving message $messageId: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception moving message $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun getMessagesForThread(
        threadId: String,
        folderId: String, // This parameter is used for logging context but ignored for Graph API endpoint
        selectFields: List<String>, // Removed default value
        maxResults: Int // Removed default value
    ): Result<List<Message>> {
        Log.d(
            TAG,
            "GraphApiHelper: getMessagesForThread (Outlook) called for conversationId: $threadId. FolderId '$folderId' (context). MaxResults: $maxResults. SelectFields: $selectFields"
        )
        return try {
            val defaultRequiredFields = listOf(
                "id",
                "conversationId",
                "receivedDateTime",
                "sentDateTime",
                "subject",
                "bodyPreview",
                "sender",
                "from",
                "isRead",
                "parentFolderId"
            )
            val effectiveSelectFields =
                (defaultRequiredFields + selectFields).distinct().joinToString(",")

            val response: HttpResponse =
                httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
                url {
                    parameters.append("\$filter", "conversationId eq '$threadId'")
                    parameters.append("\$select", effectiveSelectFields)
                    parameters.append("\$top", maxResults.toString())
                }
                    // No accept(ContentType.Application.Json) here, assuming defaultRequest or ContentNegotiation handles it
            }

            if (response.status.isSuccess()) {
                val graphMessageCollection = response.body<GraphCollection<GraphMessage>>()
                val messages =
                    graphMessageCollection.value.mapNotNull { mapGraphMessageToMessage(it) }
                Log.d(
                    TAG,
                    "GraphApiHelper: Successfully mapped ${messages.size} messages for Outlook conversationId: $threadId"
                )
                Result.success(messages)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching messages for conversation $threadId (Outlook): ${response.status} - Error details in API response."
                )
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getMessagesForThread for ID: $threadId (Outlook)", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    // Added missing getMessageDetails implementation
    override suspend fun getMessageDetails(messageId: String): Flow<Message?> = flow {
        Log.d(TAG, "Fetching details for message ID: $messageId")
        try {
            val response: HttpResponse =
                httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                    url {
                        parameters.append(
                            "\$select",
                            "id,conversationId,receivedDateTime,sentDateTime,subject,bodyPreview,sender,from,isRead,body" // Ensured 'body' is selected
                        )
                    }
                    // accept(ContentType.Application.Json) // Assuming defaultRequest or ContentNegotiation handles it
                }

            if (response.status.isSuccess()) {
                val graphMessageFromApi =
                    response.body<GraphMessage>() // GraphMessage now expects the 'body' object
                val mappedMessage =
                    mapGraphMessageToMessage(graphMessageFromApi) // mapGraphMessageToMessage now uses graphMessage.body.content
                emit(mappedMessage)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message details for $messageId: ${response.status} - $errorBody"
                )
                emit(null) 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching message details for $messageId", e)
            emit(null) 
        }
    }

    override suspend fun getMessageContent(messageId: String): Result<Message> {
        Log.d(TAG, "getMessageContent: Fetching full content for messageId $messageId")
        return try {
            val response: HttpResponse =
                httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                    url {
                        // Select all fields typically needed for a Message, PLUS the body
                        parameters.append(
                            "\$select",
                            "id,conversationId,receivedDateTime,sentDateTime,subject,bodyPreview,sender,from,isRead,body"
                        )
                    }
                    accept(ContentType.Application.Json)
                }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error fetching message content for $messageId: ${response.status} - $errorBody"
                )
                return Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }

            val graphMessage = response.body<GraphMessage>()
            val message =
                mapGraphMessageToMessage(graphMessage) // mapGraphMessageToMessage should now populate body and bodyContentType

            Log.i(
                TAG,
                "Successfully fetched and mapped content for message $messageId. Content type: ${message.bodyContentType}"
            )
            Result.success(message)

        } catch (e: Exception) {
            Log.e(TAG, "Exception in getMessageContent for $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    // Start of added Thread operations
    override suspend fun markThreadRead(threadId: String, isRead: Boolean): Result<Unit> {
        Log.d(TAG, "Attempting to mark all messages in conversation $threadId as isRead=$isRead")
        // Microsoft Graph does not have a direct "mark conversation as read" endpoint.
        // This typically requires fetching all messages in the conversation and marking them individually or in batches.
        // For this stub, we'll mimic a single conceptual operation, knowing it's more complex underneath.
        // This is a placeholder to satisfy the interface and for basic testing.
        // Actual implementation would involve logic similar to getMessagesForThread and then batching markMessageRead calls.

        // Simulate a conceptual PATCH request to the thread (this endpoint doesn't actually exist for Graph threads)
        // This is primarily to test if Ktor DSL for PATCH + setBody works without explicit Content-Type here.
        return try {
            buildJsonObject { put("isRead", isRead) } // Example body
            // Using a placeholder endpoint for testing Ktor call structure, not a real Graph API endpoint for threads
            "$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/inbox/messages?\$filter=conversationId eq '$threadId'" // Escaped $filter

            // The following is a conceptual test of httpClient.patch with setBody
            // It will likely fail at runtime if not a valid PATCH endpoint or if body is not right for such an endpoint.
            // The goal here is to ensure it COMPILES without Ktor DSL unresolved reference errors.
            Log.w(
                TAG,
                "markThreadRead: Using a conceptual PATCH for conversation $threadId. THIS IS A STUB AND WILL LIKELY FAIL AT RUNTIME IF ENDPOINT ISN'T CORRECT FOR PATCH."
            )

            // For Graph, one would typically fetch messages in the thread and mark them individually.
            // This simplified call is a placeholder.
            // Let's assume for now we get all messages and mark them one by one.
            val messagesResult = getMessagesForThread(
                threadId = threadId,
                folderId = "inbox"
            ) // Use a common folder like inbox for context
            if (messagesResult.isSuccess) {
                val messages = messagesResult.getOrThrow()
                if (messages.isEmpty()) {
                    Log.w(
                        TAG,
                        "No messages found for conversation $threadId to mark as read=$isRead"
                    )
                    return Result.success(Unit)
                }
                var allSuccessful = true
                messages.forEach { message ->
                    val markResult = markMessageRead(message.id, isRead)
                    if (markResult.isFailure) {
                        allSuccessful = false
                        Log.e(
                            TAG,
                            "Failed to mark message ${message.id} in conversation $threadId. Error: ${markResult.exceptionOrNull()?.message}"
                        )
                        // Optionally, break or collect errors
                    }
                }
                if (allSuccessful) {
                    Log.d(
                        TAG,
                        "Successfully marked all ${messages.size} messages in conversation $threadId as isRead=$isRead"
                    )
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to mark one or more messages in conversation $threadId"))
                }
            } else {
                Log.e(
                    TAG,
                    "Failed to get messages for conversation $threadId to mark read. Error: ${messagesResult.exceptionOrNull()?.message}"
                )
                Result.failure(
                    messagesResult.exceptionOrNull()
                        ?: Exception("Failed to get messages for conversation $threadId")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in markThreadRead for $threadId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun deleteThread(threadId: String): Result<Unit> {
        Log.d(TAG, "deleteThread called for conversationId: $threadId")
        Log.e(
            TAG,
            "deleteThread for Microsoft Graph is complex and not fully implemented in this stub. It would involve fetching all messages in conversation $threadId and deleting them individually or batching."
        )
        try {
            val messagesResult = getMessagesForThread(
                threadId = threadId,
                folderId = "inbox"
            ) // Assuming inbox context
            if (messagesResult.isSuccess) {
                val messages = messagesResult.getOrThrow()
                if (messages.isEmpty()) {
                    Log.w(TAG, "No messages found for conversation $threadId to delete")
                    return Result.success(Unit)
                }
                var allSuccessful = true
                messages.forEach { message ->
                    val deleteOp = deleteMessage(message.id) // deleteMessage moves to deleteditems
                    if (deleteOp.isFailure) {
                        allSuccessful = false
                        Log.e(
                            TAG,
                            "Failed to delete message ${message.id} in conversation $threadId. Error: ${deleteOp.exceptionOrNull()?.message}"
                        )
                    }
                }
                return if (allSuccessful) {
                    Log.d(
                        TAG,
                        "Successfully deleted all ${messages.size} messages in conversation $threadId"
                    )
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to delete one or more messages in conversation $threadId"))
                }
            } else {
                Log.e(
                    TAG,
                    "Failed to get messages for conversation $threadId to delete. Error: ${messagesResult.exceptionOrNull()?.message}"
                )
                return Result.failure(
                    messagesResult.exceptionOrNull()
                        ?: Exception("Failed to get messages for conversation $threadId")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteThread for $threadId", e)
            return Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun moveThread(
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        Log.d(
            TAG,
            "moveThread called for conversationId: $threadId from $currentFolderId to $destinationFolderId"
        )
        Log.e(
            TAG,
            "moveThread for Microsoft Graph is complex and not fully implemented in this stub. It would involve fetching all messages in conversation $threadId from $currentFolderId and moving them individually or batching to $destinationFolderId."
        )
        try {
            val messagesResult =
                getMessagesForThread(threadId = threadId, folderId = currentFolderId)
            if (messagesResult.isSuccess) {
                val messages = messagesResult.getOrThrow()
                if (messages.isEmpty()) {
                    Log.w(
                        TAG,
                        "No messages found in folder $currentFolderId for conversation $threadId to move to $destinationFolderId"
                    )
                    return Result.success(Unit)
                }
                var allSuccessful = true
                messages.forEach { message ->
                    val moveOp = moveMessage(message.id, currentFolderId, destinationFolderId)
                    if (moveOp.isFailure) {
                        allSuccessful = false
                        Log.e(
                            TAG,
                            "Failed to move message ${message.id} in conversation $threadId. Error: ${moveOp.exceptionOrNull()?.message}"
                        )
                    }
                }
                return if (allSuccessful) {
                    Log.d(
                        TAG,
                        "Successfully moved all ${messages.size} messages in conversation $threadId to $destinationFolderId"
                    )
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to move one or more messages in conversation $threadId to $destinationFolderId"))
                }
            } else {
                Log.e(
                    TAG,
                    "Failed to get messages for conversation $threadId in folder $currentFolderId to move. Error: ${messagesResult.exceptionOrNull()?.message}"
                )
                return Result.failure(
                    messagesResult.exceptionOrNull()
                        ?: Exception("Failed to get messages for conversation $threadId in folder $currentFolderId")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in moveThread for $threadId", e)
            return Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun getMessageAttachments(messageId: String): Result<List<net.melisma.core_data.model.Attachment>> {
        Log.d(TAG, "getMessageAttachments: messageId='$messageId'")
        return try {
            val response =
                httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/attachments") {
                    accept(ContentType.Application.Json)
                }

            if (response.status.isSuccess()) {
                val attachmentsResponse = response.body<GraphCollection<GraphAttachment>>()
                val attachments = attachmentsResponse.value.map { graphAttachment ->
                    net.melisma.core_data.model.Attachment(
                        id = graphAttachment.id,
                        fileName = graphAttachment.name ?: "attachment",
                        size = graphAttachment.size ?: 0L,
                        contentType = graphAttachment.contentType ?: "application/octet-stream",
                        contentId = graphAttachment.contentId,
                        isInline = graphAttachment.isInline == true
                    )
                }
                Result.success(attachments)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Failed to get attachments for message $messageId: ${response.status} - $errorBody"
                )
                Result.failure(Exception("Failed to get attachments: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getMessageAttachments for $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun downloadAttachment(
        messageId: String,
        attachmentId: String
    ): Result<ByteArray> {
        Log.d(TAG, "downloadAttachment: messageId='$messageId', attachmentId='$attachmentId'")
        return try {
            val response =
                httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/attachments/$attachmentId/\$value") {
                    // Note: $value endpoint returns raw binary data
                }

            if (response.status.isSuccess()) {
                val data = response.body<ByteArray>()
                Result.success(data)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Failed to download attachment $attachmentId for message $messageId: ${response.status} - $errorBody"
                )
                Result.failure(Exception("Failed to download attachment: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in downloadAttachment for $messageId/$attachmentId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun createDraftMessage(draft: net.melisma.core_data.model.MessageDraft): Result<Message> {
        Log.d(TAG, "createDraftMessage: subject='${draft.subject}'")
        return try {
            val draftPayload = buildGraphDraftPayload(draft)
            val response = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
                accept(ContentType.Application.Json)
                setBody(draftPayload)
            }

            if (response.status.isSuccess()) {
                val graphMessage = response.body<GraphMessage>()
                val message = convertGraphMessageToMessage(graphMessage)
                Result.success(message)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Failed to create draft: ${response.status} - $errorBody")
                Result.failure(Exception("Failed to create draft: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in createDraftMessage", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun updateDraftMessage(
        messageId: String,
        draft: net.melisma.core_data.model.MessageDraft
    ): Result<Message> {
        Log.d(TAG, "updateDraftMessage: messageId='$messageId', subject='${draft.subject}'")
        return try {
            val draftPayload = buildGraphDraftPayload(draft)
            val response = httpClient.patch("$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId") {
                accept(ContentType.Application.Json)
                setBody(draftPayload)
            }

            if (response.status.isSuccess()) {
                val graphMessage = response.body<GraphMessage>()
                val message = convertGraphMessageToMessage(graphMessage)
                Result.success(message)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Failed to update draft $messageId: ${response.status} - $errorBody")
                Result.failure(Exception("Failed to update draft: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in updateDraftMessage for $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun sendMessage(draft: net.melisma.core_data.model.MessageDraft): Result<String> {
        Log.d(TAG, "sendMessage: subject='${draft.subject}'")
        return try {
            val sendPayload = buildGraphSendPayload(draft)
            val response = httpClient.post("$MS_GRAPH_ROOT_ENDPOINT/me/sendMail") {
                accept(ContentType.Application.Json)
                setBody(sendPayload)
            }

            if (response.status.isSuccess()) {
                // Microsoft Graph sendMail returns 202 with no body on success
                // Generate a unique ID since we don't get one back
                val sentMessageId = "sent_${System.currentTimeMillis()}"
                Result.success(sentMessageId)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Failed to send message: ${response.status} - $errorBody")
                Result.failure(Exception("Failed to send message: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendMessage", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun searchMessages(
        query: String,
        folderId: String?,
        maxResults: Int
    ): Result<List<Message>> {
        Log.d(TAG, "searchMessages: query='$query', folderId='$folderId', maxResults=$maxResults")
        return try {
            val searchUrl = if (folderId != null) {
                "$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders/$folderId/messages"
            } else {
                "$MS_GRAPH_ROOT_ENDPOINT/me/messages"
            }

            val response = httpClient.get(searchUrl) {
                accept(ContentType.Application.Json)
                if (query.isNotBlank()) {
                    // Microsoft Graph uses $search for text search
                    url.parameters.append("\$search", "\"$query\"")
                }
                url.parameters.append("\$top", maxResults.toString())
            }

            if (response.status.isSuccess()) {
                val messagesResponse = response.body<GraphCollection<GraphMessage>>()
                val messages = messagesResponse.value.map { convertGraphMessageToMessage(it) }
                Result.success(messages)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Failed to search messages: ${response.status} - $errorBody")
                Result.failure(Exception("Failed to search messages: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in searchMessages", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    // Helper methods for the new functionality

    private fun buildGraphDraftPayload(draft: net.melisma.core_data.model.MessageDraft): Map<String, Any> {
        val payload = mutableMapOf<String, Any>()

        draft.subject?.let { payload["subject"] = it }

        val body = mapOf(
            "contentType" to "HTML",
            "content" to (draft.body ?: "")
        )
        payload["body"] = body

        val toRecipients = draft.to.map { email ->
            mapOf("emailAddress" to mapOf("address" to email))
        }
        payload["toRecipients"] = toRecipients

        draft.cc?.let { ccList ->
            if (ccList.isNotEmpty()) {
                val ccRecipients = ccList.map { email ->
                    mapOf("emailAddress" to mapOf("address" to email))
                }
                payload["ccRecipients"] = ccRecipients
            }
        }

        draft.bcc?.let { bccList ->
            if (bccList.isNotEmpty()) {
                val bccRecipients = bccList.map { email ->
                    mapOf("emailAddress" to mapOf("address" to email))
                }
                payload["bccRecipients"] = bccRecipients
            }
        }

        // Mark as draft
        payload["isDraft"] = true

        return payload
    }

    private fun buildGraphSendPayload(draft: net.melisma.core_data.model.MessageDraft): Map<String, Any> {
        val message = buildGraphDraftPayload(draft).toMutableMap()
        message.remove("isDraft") // Remove draft flag for sending

        return mapOf("message" to message)
    }

    private fun convertGraphMessageToMessage(graphMessage: GraphMessage): Message {
        return Message(
            id = graphMessage.id,
            threadId = graphMessage.conversationId,
            receivedDateTime = graphMessage.receivedDateTime ?: "",
            sentDateTime = graphMessage.sentDateTime,
            subject = graphMessage.subject,
            senderName = graphMessage.from?.emailAddress?.name,
            senderAddress = graphMessage.from?.emailAddress?.address,
            bodyPreview = graphMessage.bodyPreview,
            isRead = graphMessage.isRead ?: false,
            recipientNames = graphMessage.toRecipients?.map { it.emailAddress?.name ?: "" }
                ?: emptyList(),
            recipientAddresses = graphMessage.toRecipients?.map { it.emailAddress?.address ?: "" }
                ?: emptyList(),
            isStarred = graphMessage.flag?.flagStatus == "flagged",
            hasAttachments = graphMessage.hasAttachments == true,
            timestamp = parseGraphDateTime(graphMessage.receivedDateTime)
        )
    }

    private fun parseGraphDateTime(dateTimeString: String?): Long {
        return dateTimeString?.let {
            try {
                // Microsoft Graph returns ISO 8601 format
                java.time.Instant.parse(it).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        } ?: System.currentTimeMillis()
    }
    // End of added operations
}