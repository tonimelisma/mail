package net.melisma.backend_microsoft

// REMOVED: import io.ktor.client.engine.mock.HttpResponseData // This was the problematic import
// Assuming HttpRequestData is needed for the handler signature

// TRY IMPORTING THE TYPEALIAS

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
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GraphApiHelperTest {

    // --- Test Constants ---
    private val testFolderId = "folder_inbox_id"
    private val testSelectFields =
        listOf("id", "subject", "receivedDateTime", "sender", "isRead", "bodyPreview")
    private val testTop = 25
    private val defaultFoldersTop = 100 // As defined in GraphApiHelper

    // --- JSON Test Data ---
    private val validFoldersJsonResponse = """
        {
          "value": [
            { "id": "id_sent", "displayName": "Sent Items", "totalItemCount": 50, "unreadItemCount": 0 },
            { "id": "id_inbox", "displayName": "Inbox", "totalItemCount": 100, "unreadItemCount": 10 },
            { "id": "id_archive", "displayName": "Archive", "totalItemCount": 20, "unreadItemCount": 1 }
          ]
        }
    """.trimIndent()

    private val validMessagesJsonResponse = """
        {
          "value": [
            { "id": "msg1", "receivedDateTime": "2025-05-04T10:00:00Z", "subject": "Test 1 Subject", "bodyPreview": "Preview 1...", "isRead": false, "sender": { "emailAddress": { "name": "Sender One", "address": "sender1@test.com" } } },
            { "id": "msg2", "receivedDateTime": "2025-05-03T11:30:00Z", "subject": null, "bodyPreview": "", "isRead": true, "sender": { "emailAddress": { "name": null, "address": "sender2@test.com" } } },
            { "id": "msg3", "receivedDateTime": "2025-05-02T09:00:00Z", "subject": "Test 3 No Sender", "bodyPreview": "Preview 3", "isRead": false, "sender": null }
          ]
        }
    """.trimIndent()

    private val emptyListJsonResponse = """
        {
          "value": []
        }
    """.trimIndent()

    private fun graphApiErrorJsonResponse(
        errorCode: String = "InvalidAuthenticationToken",
        errorMessage: String = "Access token has expired."
    ) = """
        {
          "error": {
            "code": "$errorCode",
            "message": "$errorMessage",
            "innerError": {
              "date": "2025-05-05T12:00:00",
              "request-id": "some-guid",
              "client-request-id": "some-other-guid"
            }
          }
        }
    """.trimIndent()

    private val malformedJsonResponse = """
        {
          "valu": [
            { "id": "id_inbox", "displayName": "Inbox" }
          ]
        }
    """.trimIndent()

    private lateinit var json: Json
    private lateinit var graphApiHelper: GraphApiHelper
    private lateinit var mockErrorMapper: MicrosoftErrorMapper

    @Before
    fun setUp() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
        }
        mockErrorMapper = MicrosoftErrorMapper()
    }

    // Use Ktor's MockRequestHandler typealias for the handler
    // If MockRequestHandler is also unresolved, use:
    // private fun createMockClient(handler: suspend MockRequestHandleScope.(request: HttpRequestData) -> Any): HttpClient {
    private fun createMockClient(handler: MockRequestHandler): HttpClient {
        val mockEngine = MockEngine { request -> // 'request' here is HttpRequestData
            // 'this' inside this lambda is MockRequestHandleScope
            // The handler is an extension function on MockRequestHandleScope
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
    fun `getMailFolders success returns mapped and sorted folders`() = runTest {
        val mockClient =
            createMockClient { request -> // 'this' in this lambda is MockRequestHandleScope
            assertEquals(
                "https://graph.microsoft.com/v1.0/me/mailFolders?%24top=${defaultFoldersTop}",
                request.url.toString().replace("%24", "\$")
            )
            assertEquals(
                ContentType.Application.Json.toString(),
                request.headers[HttpHeaders.Accept]
            )
                // Use 'this.respond' or just 'respond' as it's an extension on MockRequestHandleScope
                this.respond(
                content = validFoldersJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMailFolders()

        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertEquals(3, folders?.size)
        assertEquals("Archive", folders?.get(0)?.displayName)
        assertEquals("Inbox", folders?.get(1)?.displayName)
        assertEquals("Sent Items", folders?.get(2)?.displayName)
    }

    @Test
    fun `getMailFolders success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = emptyListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMailFolders()

        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertTrue(folders?.isEmpty() == true)
    }

    private fun testGetMailFoldersApiError(
        statusCode: HttpStatusCode,
        expectedErrorInMessageSubstring: String,
        apiErrorCode: String = "SomeApiError",
        apiErrorMessage: String = "Something went wrong"
    ) = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = graphApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMailFolders()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            "Failure message: ${exception?.message} did not contain expected substring '$expectedErrorInMessageSubstring'",
            exception?.message?.contains(expectedErrorInMessageSubstring, ignoreCase = true) == true
        )
    }

    @Test
    fun `getMailFolders handles API error 401 Unauthorized`() =
        testGetMailFoldersApiError(
            HttpStatusCode.Unauthorized,
            "Access token has expired."
        )

    @Test
    fun `getMailFolders handles API error 403 Forbidden`() =
        testGetMailFoldersApiError(
            HttpStatusCode.Forbidden,
            "User not allowed.", // Ensure your error mapper or response produces this
            apiErrorMessage = "User not allowed." // Example for graphApiErrorJsonResponse
        )

    @Test
    fun `getMailFolders handles API error 404 Not Found`() =
        testGetMailFoldersApiError(
            HttpStatusCode.NotFound,
            "Resource not found.",
            apiErrorMessage = "Resource not found."
        )

    @Test
    fun `getMailFolders handles API error 500 InternalServerError`() =
        testGetMailFoldersApiError(
            HttpStatusCode.InternalServerError,
            "Server is down.",
            apiErrorMessage = "Server is down."
        )

    @Test
    fun `getMailFolders handles network exception`() = runTest {
        val networkErrorMessage = "Network connection failed"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMailFolders()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception?.message?.contains(networkErrorMessage) == true)
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
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMailFolders()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            "Exception type was ${exception?.javaClass?.simpleName}",
            exception is IOException || exception is SerializationException
        )
    }

    // --- getMessagesForFolder Tests ---

    @Test
    fun `getMessagesForFolder success returns mapped messages`() = runTest {
        val mockClient = createMockClient { request ->
            val expectedUrlPath =
                "https://graph.microsoft.com/v1.0/me/mailFolders/$testFolderId/messages"
            val expectedSelect = testSelectFields.joinToString(",")

            assertTrue(
                "Request URL path does not match",
                request.url.toString().startsWith(expectedUrlPath)
            )
            assertEquals(expectedSelect, request.url.parameters["\$select"])
            assertEquals(testTop.toString(), request.url.parameters["\$top"])
            assertEquals("receivedDateTime desc", request.url.parameters["\$orderby"])
            assertEquals(
                ContentType.Application.Json.toString(),
                request.headers[HttpHeaders.Accept]
            )
            this.respond(
                content = validMessagesJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMessagesForFolder(
            folderId = testFolderId,
            selectFields = testSelectFields,
            maxResults = testTop
        )

        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertEquals(3, messages?.size)
        messages?.get(0)?.let { message ->
            assertEquals("msg1", message.id)
            assertEquals("Test 1 Subject", message.subject)
        } ?: fail("Message 0 is null")
    }

    @Test
    fun `getMessagesForFolder success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = emptyListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.getMessagesForFolder(testFolderId, testSelectFields, testTop)

        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertTrue(messages?.isEmpty() == true)
    }

    private fun testGetMessagesApiError(
        statusCode: HttpStatusCode,
        expectedErrorInMessageSubstring: String,
        apiErrorCode: String = "SomeApiError",
        apiErrorMessage: String = "Something went wrong"
    ) = runTest {
        val mockClient = createMockClient {
            this.respond(
                content = graphApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMessagesForFolder(testFolderId, testSelectFields, testTop)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            "Failure message: ${exception?.message} did not contain expected substring '$expectedErrorInMessageSubstring'",
            exception?.message?.contains(expectedErrorInMessageSubstring, ignoreCase = true) == true
        )
    }


    @Test
    fun `getMessagesForFolder handles API error 401 Unauthorized`() =
        testGetMessagesApiError(
            HttpStatusCode.Unauthorized,
            "Access token has expired."
        )

    @Test
    fun `getMessagesForFolder handles network exception`() = runTest {
        val networkErrorMessage = "Timeout reading messages"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMessagesForFolder(testFolderId, testSelectFields, testTop)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception?.message?.contains(networkErrorMessage) == true)
    }

    @Test
    fun `getMessagesForFolder handles malformed JSON response`() = runTest {
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
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)

        val result = graphApiHelper.getMessagesForFolder(testFolderId, testSelectFields, testTop)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is SerializationException || exception is IOException)
    }
}