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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GmailApiHelperTest {

    // --- Test Constants ---
    private val testFolderId = "INBOX"
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
        errorCode: Int = 401,
        errorMessage: String = "Invalid Credentials"
    ) = """
        {
          "error": {
            "code": $errorCode,
            "message": "$errorMessage",
            "errors": [
              {
                "message": "$errorMessage",
                "domain": "global",
                "reason": "${if (errorCode == 401) "authError" else "backendError"}"
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
    """.trimIndent()

    private lateinit var json: Json
    private lateinit var gmailApiHelper: GmailApiHelper
    private lateinit var mockErrorMapper: ErrorMapperService

    @Before
    fun setUp() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
        }
        mockErrorMapper = GoogleErrorMapper()
    }

    private fun createMockClient(handler: MockRequestHandler): HttpClient {
        val mockEngine = MockEngine { request ->
            this.handler(request)
        }
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
            this.respond(
                content = validLabelsJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMailFolders()
        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertEquals(4, folders?.size)
        folders?.find { it.id == "INBOX" }?.let { inbox ->
            assertEquals("Inbox", inbox.displayName)
            assertEquals(100, inbox.totalItemCount)
            assertEquals(10, inbox.unreadItemCount)
        } ?: fail("Inbox folder not found")
    }

    @Test
    fun `getMailFolders success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = emptyLabelsJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMailFolders()
        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertTrue(folders?.isEmpty() == true)
    }

    @Test
    fun `getMailFolders handles API error`() = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = gmailApiErrorJsonResponse(errorCode = 401),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMailFolders()
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
    }

    @Test
    fun `getMailFolders handles network exception`() =
        runTest { // This is where line ~247 likely is
        val networkErrorMessage = "Network connection failed"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
            gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
            val result = gmailApiHelper.getMailFolders()
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
            // CORRECTED LINE for 247: Use safe call for exception.message, or ensure 'exception' is non-null AND its 'message' is non-null for direct access.
            // Given 'exception' is asserted as not null, the compiler might be worried about '.message' itself being null.
            // For assertEquals, passing a potentially null 'exception.message' is okay.
            // The error "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'kotlin.Throwable?'"
            // implies the 'exception' variable itself is seen as nullable at the point of use.
            // Despite assertNotNull, if the compiler cannot guarantee this across all paths for the specific expression, it will complain.
            // Let's make it absolutely clear to the compiler by using the non-null asserted call,
            // since we've already asserted it's not null.
            assertEquals(networkErrorMessage, exception!!.message)
    }

    @Test
    fun `getMailFolders handles malformed JSON response`() = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = malformedJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMailFolders()
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is SerializationException || exception is IOException)
    }

    // --- getMessagesForFolder Tests ---

    @Test
    fun `getMessagesForFolder success returns mapped messages`() = runTest {
        var requestCount = 0
        val mockClient = createMockClient { request ->
            when (requestCount++) {
                0 -> {
                    assertTrue(request.url.toString().startsWith("$baseUrl/messages"))
                    assertEquals(testFolderId, request.url.parameters["labelIds"])
                    assertEquals(testMaxResults.toString(), request.url.parameters["maxResults"])
                    this.respond(
                        content = validMessagesListJsonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }
                1 -> {
                    assertEquals(
                        "$baseUrl/messages/msg1",
                        request.url.toString().substringBefore("?")
                    )
                    assertEquals("metadata", request.url.parameters["format"])
                    assertTrue(
                        request.url.parameters.getAll("metadataHeaders")
                            ?.contains("Subject") == true
                    )
                    this.respond(
                        content = validMessageJsonResponse1,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }
                2 -> {
                    assertEquals(
                        "$baseUrl/messages/msg2",
                        request.url.toString().substringBefore("?")
                    )
                    this.respond(
                        content = validMessageJsonResponse2,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }
                3 -> {
                    assertEquals(
                        "$baseUrl/messages/msg3",
                        request.url.toString().substringBefore("?")
                    )
                    this.respond(
                        content = validMessageJsonResponse3,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                else -> error("Unexpected request count: $requestCount, URL: ${request.url}")
            }
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(
            folderId = testFolderId,
            selectFields = listOf("id", "subject", "snippet"),
            maxResults = testMaxResults
        )
        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertEquals(3, messages?.size)
        messages?.find { it.id == "msg1" }?.let {
            assertEquals("Test Subject 1", it.subject)
        } ?: fail("Message 'msg1' not found or subject mismatch")
        messages?.find { it.id == "msg2" }?.let {
            assertEquals("Test Subject 2", it.subject)
            assertFalse(it.isRead)
        } ?: fail("Message 'msg2' not found or read status/subject mismatch")
        messages?.find { it.id == "msg3" }?.let {
            assertEquals("(No Subject)", it.subject)
        } ?: fail("Message 'msg3' not found or subject mismatch")
    }

    @Test
    fun `getMessagesForFolder success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = emptyMessagesJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(testFolderId, emptyList(), testMaxResults)
        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertTrue(messages?.isEmpty() == true)
    }

    @Test
    fun `getMessagesForFolder handles API error on initial list call`() = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = gmailApiErrorJsonResponse(errorCode = 403),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(testFolderId, emptyList(), testMaxResults)
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
    }

    @Test
    fun `getMessagesForFolder handles API error on subsequent detail call`() = runTest {
        var requestCount = 0
        val mockClient = createMockClient { request ->
            when (requestCount++) {
                0 -> {
                    this.respond(
                        content = validMessagesListJsonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                1 -> {
                    assertEquals(
                        "$baseUrl/messages/msg1",
                        request.url.toString().substringBefore("?")
                    )
                    this.respond(
                        content = gmailApiErrorJsonResponse(
                            errorCode = 500,
                            errorMessage = "Server error on detail for msg1"
                        ),
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                2 -> {
                    assertEquals(
                        "$baseUrl/messages/msg2",
                        request.url.toString().substringBefore("?")
                    )
                    this.respond(
                        content = validMessageJsonResponse2,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                3 -> {
                    assertEquals(
                        "$baseUrl/messages/msg3",
                        request.url.toString().substringBefore("?")
                    )
                    this.respond(
                        content = validMessageJsonResponse3,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString()
                        )
                    )
                }

                else -> error("Unexpected request count: $requestCount")
            }
        }
        gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(testFolderId, emptyList(), testMaxResults)
        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertEquals(2, messages?.size)
        assertNull(messages?.find { it.id == "msg1" })
        assertNotNull(messages?.find { it.id == "msg2" })
        assertNotNull(messages?.find { it.id == "msg3" })
    }


    @Test
    fun `getMessagesForFolder handles network exception on initial list call`() =
        runTest { // This is where line ~430 likely is
            val networkErrorMessage = "Network connection failed for list"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
            gmailApiHelper = GmailApiHelper(mockClient, mockErrorMapper)
            val result =
                gmailApiHelper.getMessagesForFolder(testFolderId, emptyList(), testMaxResults)
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
            // CORRECTED LINE for 430:
            assertEquals(networkErrorMessage, exception!!.message)
    }
}