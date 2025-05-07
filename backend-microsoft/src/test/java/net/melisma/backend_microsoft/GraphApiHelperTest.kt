package net.melisma.backend_microsoft

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GraphApiHelperTest {

    // --- Test Data ---
    private val testAccessToken = "DUMMY_ACCESS_TOKEN"
    private val testFolderId = "folder_inbox_id"
    private val testSelectFields =
        listOf("id", "subject", "receivedDateTime", "sender", "isRead", "bodyPreview")
    private val testTop = 25

    // Example JSON responses (simplified)
    private val validFoldersJsonResponse = """
        {
          "value": [
            { "id": "id_inbox", "displayName": "Inbox", "totalItemCount": 100, "unreadItemCount": 10 },
            { "id": "id_sent", "displayName": "Sent Items", "totalItemCount": 50, "unreadItemCount": 0 }
          ]
        }
    """.trimIndent()

    private val validMessagesJsonResponse = """
        {
          "value": [
            { "id": "msg1", "receivedDateTime": "2025-05-04T10:00:00Z", "subject": "Test 1", "bodyPreview": "Preview 1...", "isRead": false, "sender": { "emailAddress": { "name": "Sender One", "address": "sender1@test.com" } } },
            { "id": "msg2", "receivedDateTime": "2025-05-04T11:30:00Z", "subject": null, "bodyPreview": "", "isRead": true, "sender": { "emailAddress": { "name": null, "address": "sender2@test.com" } } }
          ]
        }
    """.trimIndent()

    private val errorJsonResponse = """
        {
          "error": {
            "code": "InvalidAuthenticationToken",
            "message": "Access token has expired.",
            "innerError": {
              "date": "2025-05-05T12:00:00",
              "request-id": "some-guid",
              "client-request-id": "some-other-guid"
            }
          }
        }
    """.trimIndent()

    private lateinit var json: Json
    private lateinit var graphApiHelper: GraphApiHelper

    @Before
    fun setUp() {
        json = Json { ignoreUnknownKeys = true; isLenient = true } // Configure Json parser
    }

    // Helper to create HttpClient with MockEngine
    private fun createMockClient(handler: MockRequestHandler): HttpClient {
        val mockEngine = MockEngine { request ->
            handler(request) // Delegate request handling
        }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json) // Use the same Json configuration
            }
        }
    }

    // --- getMailFolders Tests ---

    @Test
    fun `getMailFolders success returns mapped folders`() = runTest {
        // Arrange
        val mockClient = createMockClient { request ->
            // Verify request URL and headers
            assertEquals(
                "https://graph.microsoft.com/v1.0/me/mailFolders?%24top=100",
                request.url.toString()
            )
            assertEquals("Bearer $testAccessToken", request.headers[HttpHeaders.Authorization])
            // Respond with valid JSON
            respond(
                content = validFoldersJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        // Act
        val result = graphApiHelper.getMailFolders(testAccessToken)

        // Assert
        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertEquals(2, folders?.size)
        // Check mapping and sorting
        assertEquals("Inbox", folders?.get(0)?.displayName)
        assertEquals("id_inbox", folders?.get(0)?.id)
        assertEquals(10, folders?.get(0)?.unreadItemCount)
        assertEquals("Sent Items", folders?.get(1)?.displayName)
        assertEquals(0, folders?.get(1)?.unreadItemCount)
    }

    @Test
    fun `getMailFolders handles API error response`() = runTest {
        // Arrange
        val mockClient = createMockClient { request ->
            respond(
                content = errorJsonResponse,
                status = HttpStatusCode.Unauthorized, // Simulate an error status
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        // Act
        val result = graphApiHelper.getMailFolders(testAccessToken)

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException) // We wrap non-success in IOException currently
        assertTrue(exception?.message?.contains("Unauthorized") == true)
        assertTrue(exception?.message?.contains("Error fetching folders") == true)
    }

    @Test
    fun `getMailFolders handles network exception`() = runTest {
        // Arrange
        val mockClient = createMockClient { request ->
            // Simulate a network error during the request
            throw IOException("Network connection failed")
        }
        graphApiHelper = GraphApiHelper(mockClient)

        // Act
        val result = graphApiHelper.getMailFolders(testAccessToken)

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertEquals("Network connection failed", exception?.message)
    }

    // --- getMessagesForFolder Tests ---

    @Test
    fun `getMessagesForFolder success returns mapped messages`() = runTest {
        // Arrange
        val mockClient = createMockClient { request ->
            // Verify request URL, headers, and parameters
            val expectedUrl =
                "https://graph.microsoft.com/v1.0/me/mailFolders/$testFolderId/messages?%24select=id%2Csubject%2CreceivedDateTime%2Csender%2CisRead%2CbodyPreview&%24top=$testTop&%24orderby=receivedDateTime+desc"
            assertEquals(expectedUrl, request.url.toString())
            assertEquals("Bearer $testAccessToken", request.headers[HttpHeaders.Authorization])
            respond(
                content = validMessagesJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        // Act
        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        // Assert
        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertEquals(2, messages?.size)
        // Check mapping for first message
        assertEquals("msg1", messages?.get(0)?.id)
        assertEquals("Test 1", messages?.get(0)?.subject)
        assertEquals("Sender One", messages?.get(0)?.senderName)
        assertEquals("sender1@test.com", messages?.get(0)?.senderAddress)
        assertEquals(false, messages?.get(0)?.isRead)
        // Check mapping for second message (nulls)
        assertEquals("msg2", messages?.get(1)?.id)
        assertNull(messages?.get(1)?.subject)
        assertNull(messages?.get(1)?.senderName)
        assertEquals("sender2@test.com", messages?.get(1)?.senderAddress)
        assertEquals(true, messages?.get(1)?.isRead)
    }

    @Test
    fun `getMessagesForFolder handles API error response`() = runTest {
        // Arrange
        val mockClient = createMockClient { request ->
            respond(
                content = errorJsonResponse,
                status = HttpStatusCode.Forbidden, // Simulate an error status
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        // Act
        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertTrue(exception?.message?.contains("Forbidden") == true)
        assertTrue(exception?.message?.contains("Error fetching messages") == true)
    }

    @Test
    fun `getMessagesForFolder handles network exception`() = runTest {
        // Arrange
        val mockClient = createMockClient { request ->
            throw IOException("Timeout reading messages")
        }
        graphApiHelper = GraphApiHelper(mockClient)

        // Act
        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertEquals("Timeout reading messages", exception?.message)
    }

    // Add more tests for edge cases, empty responses, different parameters etc.
}
