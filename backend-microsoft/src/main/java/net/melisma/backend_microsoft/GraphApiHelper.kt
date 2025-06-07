package net.melisma.backend_microsoft

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.accept
import io.ktor.client.request.get
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
import net.melisma.backend_microsoft.auth.MicrosoftAuthUserCredentials
import net.melisma.backend_microsoft.di.MicrosoftGraphHttpClient
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ApiServiceException
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


@Serializable
private data class GraphCollection<T>( // Used by Ktor getMailFolders
    @SerialName("@odata.context") val context: String? = null,
    val value: List<T>,
    @SerialName("@odata.nextLink") val nextLink: String? = null
)

@Serializable
private data class GraphMailFolder( // Used by Ktor getMailFolders
    val id: String,
    val displayName: String,
    val totalItemCount: Int = 0,
    val unreadItemCount: Int = 0,
    val wellKnownName: String? = null
)

// Ktor specific DTOs for messages, used by getMessagesForFolder
@Serializable
private data class KtorGraphMessage(
    val id: String,
    @SerialName("conversationId") val conversationId: String?,
    val receivedDateTime: String? = null,
    val sentDateTime: String? = null,
    val subject: String? = null,
    val sender: KtorGraphRecipient? = null,
    val from: KtorGraphRecipient? = null,
    val toRecipients: List<KtorGraphRecipient> = emptyList(),
    val isRead: Boolean = true,
    val bodyPreview: String? = null,
    val body: KtorGraphItemBody? = null,
    val hasAttachments: Boolean? = null,
    val flag: KtorGraphFlag? = null // Represents the 'starred' status via its flagStatus field
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
        var finalDisplayName = graphFolder.displayName
        val wellKnownNameLower = graphFolder.wellKnownName?.lowercase()
        val displayNameLower = graphFolder.displayName.lowercase() // For fallback matching

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
            totalItemCount = graphFolder.totalItemCount,
            unreadItemCount = graphFolder.unreadItemCount,
            type = type
        )
    }

    // Mapper for Ktor-parsed KtorGraphMessage to domain Message using fromApi factory
    private fun mapKtorGraphMessageToDomainMessage(
        ktorGraphMessage: KtorGraphMessage,
        accountId: String,
        folderId: String
    ): Message? {
        val receivedDateTimeStr = ktorGraphMessage.receivedDateTime ?: return null

        return Message.fromApi(
            id = ktorGraphMessage.id,
            accountId = accountId,
            folderId = folderId,
            threadId = ktorGraphMessage.conversationId ?: ktorGraphMessage.id,
            receivedDateTime = receivedDateTimeStr,
            sentDateTime = ktorGraphMessage.sentDateTime,
            subject = ktorGraphMessage.subject ?: "",
            senderName = ktorGraphMessage.sender?.emailAddress?.name
                ?: ktorGraphMessage.from?.emailAddress?.name,
            senderAddress = ktorGraphMessage.sender?.emailAddress?.address
                ?: ktorGraphMessage.from?.emailAddress?.address,
            bodyPreview = ktorGraphMessage.bodyPreview ?: "",
            isRead = ktorGraphMessage.isRead,
            body = ktorGraphMessage.body?.content,
            bodyContentType = ktorGraphMessage.body?.contentType ?: "text",
            recipientNames = ktorGraphMessage.toRecipients.mapNotNull { it.emailAddress?.name },
            recipientAddresses = ktorGraphMessage.toRecipients.mapNotNull { it.emailAddress?.address },
            isStarred = ktorGraphMessage.flag?.flagStatus == "flagged", // Assuming "flagged" string from API
            hasAttachments = ktorGraphMessage.hasAttachments == true
        )
    }

    override suspend fun getMessagesForFolder(
        folderId: String,
        activity: android.app.Activity?,
        maxResults: Int?,
        pageToken: String?
    ): Result<PagedMessagesResponse> = withContext(ioDispatcher) {
        Timber.d("getMessagesForFolder Ktor: folderId='$folderId', pageToken='$pageToken'")
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
        Timber.w("getMessagesForThread: MS Graph SDK removed. Not implemented with Ktor.")
        Result.failure(NotImplementedError("getMessagesForThread requires Ktor reimplementation as MS Graph SDK was removed."))
    }

    override suspend fun getMessageContent(messageId: String): Result<Message> =
        withContext(ioDispatcher) {
            Timber.w("getMessageContent: MS Graph SDK removed. Not implemented with Ktor.")
            Result.failure(NotImplementedError("getMessageContent requires Ktor reimplementation as MS Graph SDK was removed."))
        }

    override suspend fun markMessageRead(messageId: String, isRead: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.w("markMessageRead: MS Graph SDK removed. Not implemented with Ktor.")
            Result.failure(NotImplementedError("markMessageRead requires Ktor reimplementation as MS Graph SDK was removed."))
        }

    override suspend fun starMessage(messageId: String, isStarred: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.w("starMessage: MS Graph SDK removed. Not implemented with Ktor.")
            Result.failure(NotImplementedError("starMessage requires Ktor reimplementation as MS Graph SDK was removed."))
        }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
        withContext(ioDispatcher) {
            Timber.w("deleteMessage: MS Graph SDK removed. Not implemented with Ktor.")
            Result.failure(NotImplementedError("deleteMessage requires Ktor reimplementation as MS Graph SDK was removed."))
        }

    override suspend fun getMessageDetails(messageId: String): Flow<Message?> = flow {
        Timber.w("getMessageDetails: MS Graph SDK removed. Not implemented with Ktor.")
        throw NotImplementedError("getMessageDetails requires Ktor reimplementation as MS Graph SDK was removed.")
    }

    // Stubs for other MailApiService methods from previous steps
    override suspend fun moveMessage(
        messageId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("moveMessage not implemented (SDK removed)")) }

    override suspend fun markThreadRead(threadId: String, isRead: Boolean): Result<Unit> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("markThreadRead not implemented (SDK removed)")) }

    override suspend fun deleteThread(threadId: String): Result<Unit> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("deleteThread not implemented (SDK removed)")) }

    override suspend fun moveThread(
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("moveThread not implemented (SDK removed)")) }

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
    ): Result<List<Message>> =
        withContext(ioDispatcher) { Result.failure(NotImplementedError("searchMessages not implemented (SDK removed)")) }

    // Removed GraphSdkMessage.toDomainMessage() extension function as GraphSdkMessage (SDK type) is gone.
    // The mapKtorGraphMessageToDomainMessage handles mapping from Ktor DTOs.
    // Removed getGraphClient() method.
    // Removed local DTOs that mirrored SDK structures (GraphMessage, GraphRecipient, GraphFlag, etc.)
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