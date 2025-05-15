package net.melisma.backend_microsoft

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.isSuccess
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
    val bodyPreview: String? = null
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

    override suspend fun getMailFolders(): Result<List<MailFolder>> {
        return try {
            Log.d(TAG, "Fetching mail folders...")
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders") {
                url {
                    parameters.append("\$top", "100")
                    // Do not explicitly select wellKnownName to avoid the BadRequest
                    // The API might return it by default for some folders.
                    parameters.append("\$select", "id,displayName,totalItemCount,unreadItemCount")
                }
                accept(Json)
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
                    accept(Json)
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
            "GraphApiHelper: mapGraphMessageToMessage - MsgID: ${graphMessage.id}, ConvID: ${graphMessage.conversationId}"
        ) // Using Verbose for potentially frequent log
        val sender = graphMessage.sender // GraphMessage.sender is already GraphRecipient?
        val from = graphMessage.from // Also GraphRecipient?
        val effectiveSender = from ?: sender // Prefer 'from' if available

        // Date: Prefer sentDateTime for sent items, otherwise receivedDateTime
        // Assuming folder context isn't directly available here to check if it's "Sent Items"
        // A more robust way might involve passing folder context or message properties indicating direction.
        // For now, prioritize receivedDateTime as it's more consistently available.
        val dateTimeStr = graphMessage.receivedDateTime ?: graphMessage.sentDateTime
        val date = dateTimeStr?.let { parseOutlookDate(it) }
            ?: java.util.Date() // parseOutlookDate needs to be robust

        val outputDateFormat =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        outputDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

        return Message(
            id = graphMessage.id,
            threadId = graphMessage.conversationId, // Map conversationId to threadId
            receivedDateTime = outputDateFormat.format(date),
            subject = graphMessage.subject,
            senderName = effectiveSender?.emailAddress?.name,
            senderAddress = effectiveSender?.emailAddress?.address,
            bodyPreview = graphMessage.bodyPreview,
            isRead = graphMessage.isRead
                ?: true // Default to true if null, as per existing GraphMessage model
        )
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

    override suspend fun markMessageRead(
        messageId: String,
        isRead: Boolean
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Marking message $messageId as ${if (isRead) "read" else "unread"}")
            val endpoint = "$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId"
            val requestBody = buildJsonObject { put("isRead", isRead) }
            val response = httpClient.patch(endpoint) {
                accept(Json)
                setBody(requestBody)
            }
            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully marked message $messageId as ${if (isRead) "read" else "unread"}")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error marking message: ${response.status} - Error details in API response."
                )
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception marking message $messageId as ${if (isRead) "read" else "unread"}", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun deleteMessage(
        messageId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Deleting message $messageId")
            val endpoint = "$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId"
            val response = httpClient.delete(endpoint)
            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully deleted message $messageId")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error deleting message: ${response.status} - Error details in API response."
                )
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting message $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun moveMessage(
        messageId: String,
        targetFolderId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Moving message $messageId to folder $targetFolderId")
            val endpoint = "$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/move"
            val requestBody = buildJsonObject { put("destinationId", targetFolderId) }
            val response = httpClient.post(endpoint) {
                accept(Json)
                setBody(requestBody)
            }
            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully moved message $messageId to folder $targetFolderId")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "Error moving message: ${response.status} - Error details in API response."
                )
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception moving message $messageId to folder $targetFolderId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    override suspend fun getMessagesForThread(
        threadId: String,
        folderId: String // This parameter will be ignored for this specific Graph API call
    ): Result<List<Message>> {
        Log.d(
            TAG,
            "GraphApiHelper: getMessagesForThread (Outlook) called for conversationId: $threadId. FolderId '$folderId' is ignored for global conversation fetch."
        )
        return try {
            // Query the /me/messages endpoint and filter by conversationId for true cross-folder threading
            val response: HttpResponse =
                httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages") { // Changed from /me/mailFolders/$folderId/messages
                url {
                    parameters.append("\$filter", "conversationId eq '$threadId'")
                    parameters.append(
                        "\$select",
                        "id,conversationId,receivedDateTime,sentDateTime,subject,bodyPreview,sender,from,toRecipients,ccRecipients,bccRecipients,isRead,parentFolderId,hasAttachments,importance,inferenceClassification,internetMessageId,isDraft,isReadReceiptRequested,replyTo,flag"
                    )
                    parameters.append(
                        "\$top",
                        "100"
                    ) // Max messages per conversation; consider pagination if needed for >100
                }
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
}