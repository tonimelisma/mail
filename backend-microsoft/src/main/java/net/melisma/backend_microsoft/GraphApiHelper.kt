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
import io.ktor.http.ContentType
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
import javax.inject.Inject
import javax.inject.Singleton

// --- Data Classes for Graph API Responses ---
// Define these based on the actual JSON structure from Microsoft Graph

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
    val unreadItemCount: Int = 0
    // Add other fields if needed
)

@Serializable
private data class GraphMessage(
    val id: String,
    val receivedDateTime: String? = null,
    val subject: String? = null,
    val sender: GraphRecipient? = null,
    val isRead: Boolean = true, // Default to true if missing? Check Graph API default
    val bodyPreview: String? = null
    // Add other fields if needed
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


/**
 * Helper class for making calls to the Microsoft Graph API (v1.0) using Ktor.
 * Encapsulates network request logic and JSON parsing for mail-related data.
 * Implements the MailApiService interface to provide a standardized API for mail operations.
 * Provided as a Singleton by Hilt.
 */
@Singleton
class GraphApiHelper @Inject constructor(
    @MicrosoftGraphHttpClient private val httpClient: HttpClient,
    private val errorMapper: MicrosoftErrorMapper
) : MailApiService {

    companion object {
        private const val TAG = "GraphApiHelper"
        private const val MS_GRAPH_ROOT_ENDPOINT = "https://graph.microsoft.com/v1.0"
    }

    /**
     * Fetches the list of mail folders for the authenticated user using Ktor.
     *
     * @return Result containing the list of mail folders or an error
     */
    override suspend fun getMailFolders(): Result<List<MailFolder>> {
        return try {
            Log.d(TAG, "Fetching mail folders (Ktor Auth)...")
            val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders") {
                url {
                    parameters.append("\$top", "100") // Example query parameter
                    // Add other parameters like $select if needed
                }
                accept(Json)
            }

            if (response.status.isSuccess()) {
                val graphFolders = response.body<GraphCollection<GraphMailFolder>>().value
                val mailFolders = graphFolders.map { graphFolder ->
                    MailFolder(
                        id = graphFolder.id,
                        displayName = graphFolder.displayName,
                        totalItemCount = graphFolder.totalItemCount,
                        unreadItemCount = graphFolder.unreadItemCount
                    )
                }.sortedBy { it.displayName } // Sort alphabetically
                Log.d(TAG, "Successfully fetched ${mailFolders.size} folders.")
                Result.success(mailFolders)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error fetching folders: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            // Catch Ktor exceptions (ClientRequestException, ServerResponseException, etc.)
            // and other potential issues like SerializationException, IOException
            Log.e(TAG, "Exception fetching folders", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    /**
     * Fetches a list of messages for a specific mail folder using Ktor.
     *
     * @param folderId The ID of the folder to fetch messages from
     * @param selectFields Optional list of fields to include in the response
     * @param maxResults Maximum number of messages to return (pagination limit)
     * @return Result containing the list of messages or an error
     */
    override suspend fun getMessagesForFolder(
        folderId: String,
        selectFields: List<String>,
        maxResults: Int
    ): Result<List<Message>> {
        return try {
            Log.d(TAG, "Fetching messages for folder: $folderId")
            // Note: URL encoding for folderId is handled automatically by Ktor's URL builder
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
                val messages = graphMessages.mapNotNull { graphMsg ->
                    // Basic mapping, adjust as needed based on GraphMessage structure
                    Message(
                        id = graphMsg.id, // ID is mandatory
                        receivedDateTime = graphMsg.receivedDateTime ?: "",
                        subject = graphMsg.subject,
                        senderName = graphMsg.sender?.emailAddress?.name,
                        senderAddress = graphMsg.sender?.emailAddress?.address,
                        bodyPreview = graphMsg.bodyPreview,
                        isRead = graphMsg.isRead
                    )
                }
                Log.d(TAG, "Successfully fetched ${messages.size} messages for folder $folderId.")
                Result.success(messages)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error fetching messages for $folderId: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching messages for folder $folderId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    // Removed makeGraphApiCall and parsing methods as Ktor handles this now.

    /**
     * Marks a message as read or unread.
     *
     * @param messageId The ID of the message to update
     * @param isRead Whether the message should be marked as read (true) or unread (false)
     * @return Result indicating success or failure
     */
    override suspend fun markMessageRead(
        messageId: String,
        isRead: Boolean
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Marking message $messageId as ${if (isRead) "read" else "unread"}")
            val endpoint = "$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId"

            val requestBody = buildJsonObject {
                put("isRead", isRead)
            }

            val response = httpClient.patch(endpoint) {
                accept(Json)
                setBody(requestBody)
            }

            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully marked message $messageId as ${if (isRead) "read" else "unread"}")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error marking message: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception marking message $messageId as ${if (isRead) "read" else "unread"}", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    /**
     * Deletes a message (moves it to trash/deleted items folder).
     *
     * @param messageId The ID of the message to delete
     * @return Result indicating success or failure
     */
    override suspend fun deleteMessage(
        messageId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Deleting message $messageId")
            val endpoint = "$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId"

            val response = httpClient.delete(endpoint) {
                // Authentication is handled by the Auth plugin in the HttpClient
            }

            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully deleted message $messageId")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error deleting message: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting message $messageId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }

    /**
     * Moves a message to a different folder.
     *
     * @param messageId The ID of the message to move
     * @param targetFolderId The ID of the destination folder
     * @return Result indicating success or failure
     */
    override suspend fun moveMessage(
        messageId: String,
        targetFolderId: String
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "Moving message $messageId to folder $targetFolderId")
            val endpoint = "$MS_GRAPH_ROOT_ENDPOINT/me/messages/$messageId/move"

            val requestBody = buildJsonObject {
                put("destinationId", targetFolderId)
            }

            val response = httpClient.post(endpoint) {
                accept(Json)
                setBody(requestBody)
            }

            if (response.status.isSuccess()) {
                Log.d(TAG, "Successfully moved message $messageId to folder $targetFolderId")
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Error moving message: ${response.status} - $errorBody")
                Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception moving message $messageId to folder $targetFolderId", e)
            Result.failure(errorMapper.mapExceptionToError(e))
        }
    }
}