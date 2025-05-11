package net.melisma.backend_google

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.melisma.backend_google.errors.GoogleErrorMapper
import net.melisma.core_data.errors.ErrorMapperService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GmailApiHelperTest {

    // --- Test Constants ---
    private val testAccessToken = "DUMMY_ACCESS_TOKEN"
    private val testFolderId = "INBOX" // Gmail label ID
    private val testSelectFields =
        listOf("id", "subject", "receivedDateTime", "sender", "isRead", "bodyPreview")
    private val testMaxResults = 25
    private val baseUrl = "https://gmail.googleapis.com/gmail/v1/users/me"

    // --- JSON Test Data ---
    private val validLabelsJsonResponse = """
        {
          "labels": [
            { "id": "INBOX", "name": "INBOX", "type": "system", "messagesTotal": 100, "messagesUnread": 10 },
            { "id": "SENT", "name": "SENT", "type": "system", "messagesTotal": 50, "messagesUnread": 0 },
            { "id": "SPAM", "name": "SPAM", "type": "system", "messagesTotal": 20, "messagesUnread": 5 },
            { "id": "STARRED", "name": "STARRED", "type": "system", "messagesTotal": 10, "messagesUnread": 2 }
          ]
        }
    """.trimIndent()

    private val validMessagesListJsonResponse = """
        {
          "messages": [
            { "id": "msg1", "threadId": "thread1" },
            { "id": "msg2", "threadId": "thread2" },
            { "id": "msg3", "threadId": "thread3" }
          ],
          "nextPageToken": "token123",
          "resultSizeEstimate": 3
        }
    """.trimIndent()

    private val validMessageJsonResponse1 = """
        {
          "id": "msg1",
          "threadId": "thread1",
          "labelIds": ["INBOX", "IMPORTANT"],
          "snippet": "This is a message preview...",
          "payload": {
            "mimeType": "text/plain",
            "headers": [
              { "name": "Subject", "value": "Test Subject 1" },
              { "name": "From", "value": "John Doe <john.doe@example.com>" },
              { "name": "Date", "value": "Mon, 10 May 2025 10:30:00 +0000" }
            ]
          }
        }
    """.trimIndent()

    private val validMessageJsonResponse2 = """
        {
          "id": "msg2",
          "threadId": "thread2",
          "labelIds": ["INBOX", "UNREAD"],
          "snippet": "Another preview for testing...",
          "payload": {
            "mimeType": "text/plain",
            "headers": [
              { "name": "Subject", "value": "Test Subject 2" },
              { "name": "From", "value": "jane.smith@example.com" },
              { "name": "Date", "value": "Mon, 10 May 2025 11:30:00 +0000" }
            ]
          }
        }
    """.trimIndent()

    private val validMessageJsonResponse3 = """
        {
          "id": "msg3",
          "threadId": "thread3",
          "labelIds": ["INBOX"],
          "snippet": "",
          "payload": {
            "mimeType": "text/plain",
            "headers": [
              { "name": "From", "value": "No Subject <no.subject@example.com>" },
              { "name": "Date", "value": "Mon, 10 May 2025 12:30:00 +0000" }
            ]
          }
        }
    """.trimIndent()

    private val emptyLabelsJsonResponse = """
        {
          "labels": []
        }
    """.trimIndent()

    private val emptyMessagesJsonResponse = """
        {
          "messages": [],
          "resultSizeEstimate": 0
        }
    """.trimIndent()

    private fun gmailApiErrorJsonResponse(
        errorCode: String = "authError",
        errorMessage: String = "Invalid Credentials"
    ) = """
        {
          "error": {
            "code": 401,
            "message": "$errorMessage",
            "errors": [
              {
                "message": "$errorMessage",
                "domain": "global",
                "reason": "$errorCode"
              }
            ]
          }
        }
    """.trimIndent()

    private val malformedJsonResponse = """
        {
          "label": [ 
            { "id": "INBOX", "name": "INBOX" }
          ]
        }
    """.trimIndent() // Deliberately malformed "labels" key

    private lateinit var json: Json
    private lateinit var gmailApiHelper: GmailApiHelper
    private lateinit var errorMapper: ErrorMapperService

    @Before
    fun setUp() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
        }
        errorMapper = GoogleErrorMapper()
    }

    private fun createMockClient(handler: MockRequestHandler): HttpClient {
        val mockEngine = MockEngine { request -> handler(request) }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    // --- getMailFolders Tests ---

    @Test
    fun `getMailFolders success returns mapped folders`() = runTest {
        val mockClient = createMockClient { request ->
            assertEquals("$baseUrl/labels", request.url.toString())
            assertEquals("Bearer $testAccessToken", request.headers[HttpHeaders.Authorization])
            respond(
                content = validLabelsJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertEquals(4, folders?.size)

        // Check Inbox mapping
        folders?.find { it.id == "INBOX" }?.let { inbox ->
            assertEquals("Inbox", inbox.displayName)
            assertEquals(100, inbox.totalItemCount)
            assertEquals(10, inbox.unreadItemCount)
        } ?: fail("Inbox folder not found")

        // Check Sent mapping
        folders?.find { it.id == "SENT" }?.let { sent ->
            assertEquals("Sent", sent.displayName)
            assertEquals(50, sent.totalItemCount)
            assertEquals(0, sent.unreadItemCount)
        } ?: fail("Sent folder not found")

        // Check Spam mapping
        folders?.find { it.id == "SPAM" }?.let { spam ->
            assertEquals("Spam", spam.displayName)
            assertEquals(20, spam.totalItemCount)
            assertEquals(5, spam.unreadItemCount)
        } ?: fail("Spam folder not found")

        // Check Starred mapping
        folders?.find { it.id == "STARRED" }?.let { starred ->
            assertEquals("Starred", starred.displayName)
            assertEquals(10, starred.totalItemCount)
            assertEquals(2, starred.unreadItemCount)
        } ?: fail("Starred folder not found")
    }

    @Test
    fun `getMailFolders success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            respond(
                content = emptyLabelsJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertTrue(folders?.isEmpty() == true)
    }

    @Test
    fun `getMailFolders handles API error`() = runTest {
        val mockClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is Exception)
    }

    @Test
    fun `getMailFolders handles network exception`() = runTest {
        val networkErrorMessage = "Network connection failed"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertEquals(networkErrorMessage, exception?.message)
    }

    @Test
    fun `getMailFolders handles malformed JSON response`() = runTest {
        val mockClient = createMockClient {
            respond(
                content = malformedJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            "Exception type should be SerializationException or IOException, but was ${exception?.javaClass?.simpleName}",
            exception is SerializationException || exception is IOException
        )
    }

    // --- getMessagesForFolder Tests ---

    @Test
    fun `getMessagesForFolder success returns mapped messages`() = runTest {
        // Need to mock two types of requests:
        // 1. First request to get message IDs
        // 2. Subsequent requests to get message details
        var requestCount = 0
        val mockClient = createMockClient { request ->
            when (requestCount++) {
                0 -> {
                    // First request should be to get message IDs
                    assertTrue(request.url.toString().contains("$baseUrl/messages"))
                    assertTrue(request.url.toString().contains("labelIds=$testFolderId"))
                    assertTrue(request.url.toString().contains("maxResults=$testMaxResults"))
                    assertEquals(
                        "Bearer $testAccessToken",
                        request.headers[HttpHeaders.Authorization]
                    )
                    respond(
                        content = validMessagesListJsonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                1 -> {
                    // Second request for first message details
                    assertTrue(request.url.toString().contains("$baseUrl/messages/msg1"))
                    assertEquals(
                        "Bearer $testAccessToken",
                        request.headers[HttpHeaders.Authorization]
                    )
                    respond(
                        content = validMessageJsonResponse1,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                2 -> {
                    // Third request for second message details
                    assertTrue(request.url.toString().contains("$baseUrl/messages/msg2"))
                    assertEquals(
                        "Bearer $testAccessToken",
                        request.headers[HttpHeaders.Authorization]
                    )
                    respond(
                        content = validMessageJsonResponse2,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                3 -> {
                    // Fourth request for third message details
                    assertTrue(request.url.toString().contains("$baseUrl/messages/msg3"))
                    assertEquals(
                        "Bearer $testAccessToken",
                        request.headers[HttpHeaders.Authorization]
                    )
                    respond(
                        content = validMessageJsonResponse3,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                else -> error("Unexpected number of requests: $requestCount")
            }
        }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testMaxResults
        )

        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertEquals(3, messages?.size)

        // First message (with all fields)
        messages?.get(0)?.let { message ->
            assertEquals("msg1", message.id)
            assertEquals("Test Subject 1", message.subject)
            assertEquals("John Doe", message.senderName)
            assertEquals("john.doe@example.com", message.senderAddress)
            assertTrue(message.isRead) // No UNREAD label
            assertEquals("This is a message preview...", message.bodyPreview)
            // Verify date formatting would require more complex testing
        } ?: fail("First message is null")

        // Second message (with UNREAD label)
        messages?.get(1)?.let { message ->
            assertEquals("msg2", message.id)
            assertEquals("Test Subject 2", message.subject)
            assertNull(message.senderName) // Plain email with no name
            assertEquals("jane.smith@example.com", message.senderAddress)
            assertFalse(message.isRead) // Has UNREAD label
            assertEquals("Another preview for testing...", message.bodyPreview)
        } ?: fail("Second message is null")

        // Third message (missing subject)
        messages?.get(2)?.let { message ->
            assertEquals("msg3", message.id)
            assertTrue(message.subject == null || message.subject == "(No Subject)")
            assertEquals("No Subject", message.senderName)
            assertEquals("no.subject@example.com", message.senderAddress)
            assertTrue(message.isRead) // No UNREAD label
            assertTrue(message.bodyPreview?.isEmpty() == true)
        } ?: fail("Third message is null")
    }

    @Test
    fun `getMessagesForFolder success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            respond(
                content = emptyMessagesJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testMaxResults
        )

        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertTrue(messages?.isEmpty() == true)
    }

    @Test
    fun `getMessagesForFolder handles API error`() = runTest {
        val mockClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testMaxResults
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is Exception)
    }

    @Test
    fun `getMessagesForFolder handles network exception`() = runTest {
        val networkErrorMessage = "Network connection failed"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
        gmailApiHelper = GmailApiHelper(mockClient, errorMapper)

        val result = gmailApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testMaxResults
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertEquals(networkErrorMessage, exception?.message)
    }

    // Tests for message operations like markMessageRead, deleteMessage, moveMessage would follow a similar pattern
}