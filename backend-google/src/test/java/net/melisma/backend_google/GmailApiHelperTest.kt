package net.melisma.backend_google

// import net.melisma.core_data.errors.MappedErrorDetails // Commented out
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.errors.GoogleErrorMapper
import net.melisma.core_data.model.ErrorDetails
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import timber.log.Timber
import java.io.IOException
import android.content.Context
import io.ktor.client.engine.mock.respondError
import net.melisma.core_data.model.MessageDraft

@OptIn(ExperimentalCoroutinesApi::class)
class GmailApiHelperTest {

    private lateinit var mockErrorMapper: GoogleErrorMapper
    private lateinit var gmailApiHelper: GmailApiHelper
    private lateinit var json: Json
    private lateinit var mockEngine: MockEngine

    // New mocks for dependencies
    private lateinit var mockIoDispatcher: CoroutineDispatcher
    private lateinit var mockAuthManager: GoogleAuthManager
    private lateinit var mockContext: Context

    // To hold request handlers for different scenarios in a test
    private var requestHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        {
            respondError(HttpStatusCode.NotFound, "Unhandled request: ${it.url.fullPath}")
        }

    companion object {
        private const val BASE_URL_PREFIX = "https://gmail.googleapis.com/gmail/v1/users/me"

        // --- JSON Response Constants ---

        private val validLabelListJsonResponse = """
        {
          "labels": [
            { "id": "INBOX", "name": "INBOX", "type": "system", "messageListVisibility": "show", "messagesTotal": 10, "messagesUnread": 2 },
            { "id": "SENT", "name": "SENT", "type": "system", "labelListVisibility": "labelShow", "messageListVisibility": "show", "messagesTotal": 5, "messagesUnread": 0 },
            { "id": "Label_1", "name": "UserFolder1", "type": "user", "labelListVisibility": "labelShow", "messageListVisibility": "show", "messagesTotal": 7, "messagesUnread": 1 },
            { "id": "TRASH", "name": "TRASH", "type": "system", "labelListVisibility": "labelHide", "messagesTotal": 3, "messagesUnread": 0 },
            { "id": "SPAM", "name": "Spam", "type": "system", "messageListVisibility": "hide" },
            { "id": "IMPORTANT", "name": "Important", "type": "system", "messageListVisibility": "hide" },
            { "id": "STARRED", "name": "Starred", "type": "system", "messageListVisibility": "labelHide" },
            { "id": "CHAT", "name": "Chat", "type": "system" },
            { "id": "SCHEDULED", "name": "[Gmail]/Scheduled", "type": "system", "messageListVisibility": "hide" },
            { "id": "ALL", "name": "[Gmail]/All Mail", "type": "system", "messageListVisibility": "show" }
          ]
        }
        """.trimIndent()

        private val emptyLabelListJsonResponse = """{"labels": []}""".trimIndent()

        private fun apiErrorJsonResponse(code: Int = 400, message: String = "Bad Request") = """
        {
          "error": { "code": $code, "message": "$message" }
        }
        """.trimIndent()

        private val malformedJsonResponse = """{"labels": [}"""

        // For getMessagesForFolder: step 1 (list of IDs)
        private val messageIdListJsonResponse = """
        {
          "messages": [
            { "id": "id_msg_1", "threadId": "thread_A" },
            { "id": "id_msg_2", "threadId": "thread_B" }
          ],
          "resultSizeEstimate": 2
        }
        """.trimIndent()

        private val emptyMessageIdListJsonResponse =
            """{"messages": [], "resultSizeEstimate": 0}""".trimIndent()

        // For getMessagesForFolder: step 2 (full message details)
        private fun fullMessageJsonResponse(
            id: String,
            subject: String,
            from: String,
            snippet: String,
            internalDate: String,
            labelIds: List<String>
        ) = """
        {
          "id": "$id",
          "threadId": "thread_for_$id",
          "labelIds": ${
            labelIds.joinToString(
                separator = ",",
                prefix = "[",
                postfix = "]"
            ) { "\"$it\"" }
        },
          "snippet": "$snippet",
          "internalDate": "$internalDate",
          "payload": {
            "headers": [
              { "name": "Subject", "value": "$subject" },
              { "name": "From", "value": "$from" },
              { "name": "Date", "value": "Mon, 1 Jan 2024 10:00:00 +0000" }
            ]
          }
        }
        """.trimIndent()

        private val fullMessage1Json = fullMessageJsonResponse(
            "id_msg_1",
            "Subject 1",
            "Sender One <one@example.com>",
            "Snippet for msg 1",
            "1700000000000",
            listOf("INBOX", "UNREAD")
        )
        private val fullMessage2Json = fullMessageJsonResponse(
            "id_msg_2",
            "Subject 2",
            "sender.two@example.com",
            "Snippet for msg 2",
            "1700000001000",
            listOf("INBOX")
        )

        // For getMessagesForThread
        private val validGmailThreadJsonResponse = """
        {
          "id": "thread_A",
          "messages": [
            ${
            fullMessageJsonResponse(
                "id_msg_thread_1",
                "Thread Subject A",
                "User A <a@example.com>",
                "Thread snippet A1",
                "1700000100000",
                listOf("INBOX")
            )
        },
            ${
            fullMessageJsonResponse(
                "id_msg_thread_2",
                "Thread Subject A",
                "User B <b@example.com>",
                "Thread snippet A2",
                "1700000200000",
                listOf("INBOX", "UNREAD")
            )
        }
          ]
        }
        """.trimIndent()

        private val emptyGmailThreadJsonResponse =
            """{"id": "thread_empty", "messages": []}""".trimIndent()

        // For modify operations (markRead, delete, move) - often a 200 OK with the modified resource or 204 No Content
        // Gmail usually returns the modified message resource. For simplicity of boolean Result, OK is enough.
        private val successModifyJsonResponse = """{}""" // Minimal successful JSON body


        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            // Mock Timber to avoid Android-specific logging calls in a JUnit test environment
            mockkStatic(Timber::class)
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            unmockkStatic(Timber::class)
        }
    }

    @Before
    fun setUp() {
        mockIoDispatcher = Dispatchers.Unconfined
        mockAuthManager = mockk(relaxed = true)
        mockContext = mockk(relaxed = true) // Relaxed mock for context as it's used for file ops
        mockErrorMapper = mockk()

        // Default mock behavior
        coEvery { mockAuthManager.getNullableActiveAccountId() } returns "test_account_id"
        coEvery { mockErrorMapper.mapExceptionToErrorDetails(any()) } returns ErrorDetails(
            "UNKNOWN_ERROR",
            "An unknown error occurred"
        )
        coEvery {
            mockErrorMapper.mapExceptionToErrorDetails(any<ClientRequestException>())
        } returns ErrorDetails("HTTP_ERROR", "An HTTP error occurred")


        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        mockEngine = MockEngine { request ->
            requestHandler(this, request)
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        gmailApiHelper = GmailApiHelper(
            context = mockContext,
            httpClient = httpClient,
            errorMapper = mockErrorMapper,
            ioDispatcher = mockIoDispatcher,
            authManager = mockAuthManager
        )
    }

    private fun setOkTextResponse(content: String) {
        requestHandler = {
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
    }

    private fun setErrorResponse(statusCode: HttpStatusCode, errorJson: String) {
        requestHandler = {
            respond(
                content = errorJson,
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
    }

    private fun setNetworkErrorResponse(exception: IOException) {
        requestHandler = { throw exception }
    }

    // --- getMailFolders Tests ---
    @Test
    fun `getMailFolders returns success with mapped folders on valid response`() = runTest {
        // Arrange
        requestHandler = { request ->
            if (request.url.fullPath == "/gmail/v1/users/me/labels") {
                respond(
                    content = validLabelListJsonResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                error("Unhandled request in test: ${request.url.fullPath}")
            }
        }

        // Act
        val result = gmailApiHelper.getMailFolders(null, "test_account")

        // Assert
        assertTrue(result.isSuccess)
        val folders = result.getOrThrow()
        assertEquals(7, folders.size) // 7 visible folders out of 10 total labels
        assertEquals("Inbox", folders.find { it.id == "INBOX" }?.displayName)
        assertEquals("Archive", folders.find { it.id == "ALL" }?.displayName)
        assertTrue(folders.none { it.id == "TRASH" || it.id == "SPAM" || it.id == "IMPORTANT" })
    }

    @Test
    fun `getMailFolders returns success with empty list on empty response`() = runTest {
        // Arrange
        requestHandler = {
            respond(
                content = emptyLabelListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        // Act
        val result = gmailApiHelper.getMailFolders(null, "test_account")

        // Assert
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getMailFolders returns failure on API error`() = runTest {
        // Arrange
        requestHandler = {
            respond(
                content = apiErrorJsonResponse(),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        coEvery { mockErrorMapper.mapExceptionToErrorDetails(any<ClientRequestException>()) } returns ErrorDetails(
            "API_ERROR",
            "Bad Request"
        )

        // Act
        val result = gmailApiHelper.getMailFolders(null, "test_account")

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as? ApiServiceException
        assertEquals("API_ERROR", exception?.errorDetails?.code)
    }

    @Test
    fun `getMailFolders returns failure on malformed JSON`() = runTest {
        // Arrange
        requestHandler = {
            respond(
                content = malformedJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        coEvery { mockErrorMapper.mapExceptionToErrorDetails(any<Exception>()) } returns ErrorDetails(
            "JSON_PARSE_ERROR",
            "Malformed JSON"
        )

        // Act
        val result = gmailApiHelper.getMailFolders(null, "test_account")

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as? ApiServiceException
        assertEquals("JSON_PARSE_ERROR", exception?.errorDetails?.code)
    }

    @Test
    fun `getMailFolders returns failure on network exception`() = runTest {
        // Arrange
        requestHandler = {
            throw IOException("Network failed")
        }
        coEvery { mockErrorMapper.mapExceptionToErrorDetails(any<IOException>()) } returns ErrorDetails(
            "NETWORK_ERROR",
            "Network failed"
        )

        // Act
        val result = gmailApiHelper.getMailFolders(null, "test_account")

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as? ApiServiceException
        assertEquals("NETWORK_ERROR", exception?.errorDetails?.code)
    }

    // --- getMessagesForFolder Tests ---
    @Test
    fun `getMessagesForFolder returns paged messages on success`() = runTest {
        // This test requires mocking two separate API calls:
        // 1. GET /messages to get the list of message IDs
        // 2. GET /messages/{id} for each message ID to get its details
        requestHandler = { request ->
            when {
                // Step 1: Request for message IDs in the folder
                request.url.fullPath.contains("/messages?labelIds=INBOX") -> {
                    respond(
                        content = messageIdListJsonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                // Step 2: Requests for individual message details
                request.url.fullPath.contains("/messages/id_msg_1") -> {
                    respond(
                        content = fullMessage1Json,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                request.url.fullPath.contains("/messages/id_msg_2") -> {
                    respond(
                        content = fullMessage2Json,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                else -> error("Unhandled request: ${request.url.fullPath}")
            }
        }

        val result = gmailApiHelper.getMessagesForFolder("INBOX")

        assertTrue(result.isSuccess)
        val pagedResponse = result.getOrThrow()
        assertEquals(2, pagedResponse.messages.size)
        assertEquals("id_msg_1", pagedResponse.messages[0].id)
        assertEquals("Subject 2", pagedResponse.messages[1].subject)
        assertEquals(false, pagedResponse.messages[1].isRead) // Message 2 has only INBOX label
    }

    @Test
    fun `getMessagesForThread returns list of messages`() = runTest {
        requestHandler = { request ->
            if (request.url.fullPath.contains("/threads/thread_A")) {
                respond(
                    content = validGmailThreadJsonResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                error("Unhandled request: ${request.url.fullPath}")
            }
        }

        val result = gmailApiHelper.getMessagesForThread("thread_A", "INBOX")

        assertTrue(result.isSuccess)
        val messages = result.getOrThrow()
        assertEquals(2, messages.size)
        assertEquals("id_msg_thread_2", messages[1].id)
    }

    @Test
    fun `markMessageRead adds or removes UNREAD label`() = runTest {
        var capturedBody: String? = null
        requestHandler = { request ->
            if (request.method == HttpMethod.Post && request.url.fullPath.contains("/messages/id_msg_1/modify")) {
                capturedBody = (request.body as? io.ktor.client.request.forms.TextContent)?.text
                respond(
                    content = successModifyJsonResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                error("Unhandled request: ${request.url.fullPath}")
            }
        }

        // Mark as read (should remove UNREAD)
        val resultRead = gmailApiHelper.markMessageRead(messageId = "id_msg_1", isRead = true)
        assertTrue(resultRead.isSuccess)
        assertTrue(capturedBody?.contains("\"removeLabelIds\":[\"UNREAD\"]") == true)

        // Mark as unread (should add UNREAD)
        val resultUnread = gmailApiHelper.markMessageRead(messageId = "id_msg_1", isRead = false)
        assertTrue(resultUnread.isSuccess)
        assertTrue(capturedBody?.contains("\"addLabelIds\":[\"UNREAD\"]") == true)
    }

    @Test
    fun `starMessage adds or removes STARRED label`() = runTest {
        var capturedBody: String? = null
        requestHandler = { request ->
            if (request.method == HttpMethod.Post && request.url.fullPath.contains("/messages/id_msg_1/modify")) {
                capturedBody = (request.body as? io.ktor.client.request.forms.TextContent)?.text
                respond(
                    content = successModifyJsonResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                error("Unhandled request: ${request.url.fullPath}")
            }
        }

        // Star message
        val resultStarred = gmailApiHelper.starMessage(messageId = "id_msg_1", isStarred = true)
        assertTrue(resultStarred.isSuccess)
        assertTrue(capturedBody?.contains("\"addLabelIds\":[\"STARRED\"]") == true)

        // Unstar message
        val resultUnstarred = gmailApiHelper.starMessage(messageId = "id_msg_1", isStarred = false)
        assertTrue(resultUnstarred.isSuccess)
        assertTrue(capturedBody?.contains("\"removeLabelIds\":[\"STARRED\"]") == true)
    }

    @Test
    fun `deleteMessage moves message to trash`() = runTest {
        requestHandler = { request ->
            if (request.method == HttpMethod.Post && request.url.fullPath == "/gmail/v1/users/me/messages/id_msg_1/trash") {
                respond(
                    content = successModifyJsonResponse, // Trash API returns the message resource
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                error("Unhandled request: ${request.url.fullPath}")
            }
        }

        val result = gmailApiHelper.deleteMessage("id_msg_1")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `sendMessage sends raw message payload`() = runTest {
        var capturedBody: String? = null
        requestHandler = { request ->
             if (request.method == HttpMethod.Post && request.url.fullPath.endsWith("/messages/send")) {
                capturedBody = (request.body as? io.ktor.client.request.forms.TextContent)?.text
                respond(
                    content = """{ "id": "sent_msg_id", "labelIds": ["SENT"] }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                 error("Unhandled request: ${request.url.fullPath}")
            }
        }

        val draft = MessageDraft(
            to = listOf(net.melisma.core_data.model.EmailAddress(emailAddress = "recipient@example.com")),
            subject = "Test Send",
            body = "This is the body."
        )

        val result = gmailApiHelper.sendMessage(draft)

        assertTrue(result.isSuccess)
        assertEquals("sent_msg_id", result.getOrThrow())
        // Verify the raw payload contains the correct headers and base64 encoded body
        assertTrue(capturedBody?.contains("\"raw\":\"") == true)
        assertTrue(capturedBody?.contains("VG86IHJlY2lwaWVudEBleGFtcGxlLmNvbQ==") == true) // To: recipient@example.com
        assertTrue(capturedBody?.contains("U3ViamVjdDogVGVzdCBTZW5k") == true) // Subject: Test Send
    }
}