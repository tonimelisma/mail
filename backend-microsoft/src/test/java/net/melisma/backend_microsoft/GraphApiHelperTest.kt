package net.melisma.backend_microsoft

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GraphApiHelperTest {

    // --- Test Constants ---
    private val testAccessToken = "DUMMY_ACCESS_TOKEN"
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
    """.trimIndent() // Deliberately malformed "value" key

    private lateinit var json: Json
    private lateinit var graphApiHelper: GraphApiHelper

    @Before
    fun setUp() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint =
                false // For production-like serialization in tests if needed, though GraphApiHelper uses its own.
        }
    }

    private fun createMockClient(handler: MockRequestHandler): HttpClient {
        val mockEngine = MockEngine { request -> handler(request) }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json) // Use the same Json instance for consistency
            }
        }
    }

    // --- getMailFolders Tests ---

    @Test
    fun `getMailFolders success returns mapped and sorted folders`() = runTest {
        val mockClient = createMockClient { request ->
            assertEquals(
                "https://graph.microsoft.com/v1.0/me/mailFolders?%24top=${defaultFoldersTop}",
                request.url.toString()
            )
            assertEquals("Bearer $testAccessToken", request.headers[HttpHeaders.Authorization])
            assertEquals(
                ContentType.Application.Json.toString(),
                request.headers[HttpHeaders.Accept]
            )
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

        val result = graphApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertEquals(3, folders?.size)
        // Verify sorting by displayName (as implemented in GraphApiHelper)
        assertEquals("Archive", folders?.get(0)?.displayName)
        assertEquals("id_archive", folders?.get(0)?.id)
        assertEquals(20, folders?.get(0)?.totalItemCount)
        assertEquals(1, folders?.get(0)?.unreadItemCount)

        assertEquals("Inbox", folders?.get(1)?.displayName)
        assertEquals("id_inbox", folders?.get(1)?.id)
        assertEquals(100, folders?.get(1)?.totalItemCount)
        assertEquals(10, folders?.get(1)?.unreadItemCount)

        assertEquals("Sent Items", folders?.get(2)?.displayName)
        assertEquals("id_sent", folders?.get(2)?.id)
        assertEquals(50, folders?.get(2)?.totalItemCount)
        assertEquals(0, folders?.get(2)?.unreadItemCount)
    }

    @Test
    fun `getMailFolders success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            respond(
                content = emptyListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull(folders)
        assertTrue(folders?.isEmpty() == true)
    }

    private fun testGetMailFoldersApiError(
        statusCode: HttpStatusCode,
        expectedErrorInMessage: String,
        apiErrorCode: String = "SomeApiError",
        apiErrorMessage: String = "Something went wrong"
    ) = runTest {
        val mockClient = createMockClient {
            respond(
                content = graphApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertTrue(
            "Failure: $exception",
            exception?.message?.contains("Error fetching folders: ${statusCode}") == true
        )
        assertTrue(
            "Failure: $exception",
            exception?.message?.contains(expectedErrorInMessage) == true
        )
    }

    @Test
    fun `getMailFolders handles API error 401 Unauthorized`() =
        testGetMailFoldersApiError(
            HttpStatusCode.Unauthorized,
            "Access token has expired.",
            "InvalidAuthenticationToken",
            "Access token has expired."
        )

    @Test
    fun `getMailFolders handles API error 403 Forbidden`() =
        testGetMailFoldersApiError(
            HttpStatusCode.Forbidden,
            "User not allowed.",
            "Authorization_RequestDenied",
            "User not allowed."
        )

    @Test
    fun `getMailFolders handles API error 404 Not Found`() =
        testGetMailFoldersApiError(
            HttpStatusCode.NotFound,
            "Resource not found.",
            "ItemNotFound",
            "Resource not found."
        )

    @Test
    fun `getMailFolders handles API error 500 InternalServerError`() =
        testGetMailFoldersApiError(
            HttpStatusCode.InternalServerError,
            "Server is down.",
            "InternalError",
            "Server is down."
        )

    @Test
    fun `getMailFolders handles network exception`() = runTest {
        val networkErrorMessage = "Network connection failed"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMailFolders(testAccessToken)

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
                content = malformedJsonResponse, // Malformed key
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMailFolders(testAccessToken)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is SerializationException) // Ktor's default behavior
    }

    // --- getMessagesForFolder Tests ---

    @Test
    fun `getMessagesForFolder success returns mapped messages`() = runTest {
        val mockClient = createMockClient { request ->
            val expectedUrl =
                "https://graph.microsoft.com/v1.0/me/mailFolders/$testFolderId/messages" +
                        "?%24select=${testSelectFields.joinToString(",")}" +
                        "&%24top=$testTop" +
                        "&%24orderby=receivedDateTime+desc"
            assertEquals(
                expectedUrl,
                request.url.toString().replace("%2524", "%24")
            ) // Handle potential double encoding in mock
            assertEquals("Bearer $testAccessToken", request.headers[HttpHeaders.Authorization])
            assertEquals(
                ContentType.Application.Json.toString(),
                request.headers[HttpHeaders.Accept]
            )
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

        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertEquals(3, messages?.size)

        // Message 1 (All fields present)
        messages?.get(0)?.let {
            assertEquals("msg1", it.id)
            assertEquals("2025-05-04T10:00:00Z", it.receivedDateTime)
            assertEquals("Test 1 Subject", it.subject)
            assertEquals("Sender One", it.senderName)
            assertEquals("sender1@test.com", it.senderAddress)
            assertEquals("Preview 1...", it.bodyPreview)
            assertFalse(it.isRead)
        }

        // Message 2 (Null subject, null sender name)
        messages?.get(1)?.let {
            assertEquals("msg2", it.id)
            assertEquals("2025-05-03T11:30:00Z", it.receivedDateTime)
            assertNull(it.subject)
            assertNull(it.senderName)
            assertEquals("sender2@test.com", it.senderAddress)
            assertEquals("", it.bodyPreview)
            assertTrue(it.isRead)
        }

        // Message 3 (Null sender object)
        messages?.get(2)?.let {
            assertEquals("msg3", it.id)
            assertEquals("2025-05-02T09:00:00Z", it.receivedDateTime)
            assertEquals("Test 3 No Sender", it.subject)
            assertNull(it.senderName)
            assertNull(it.senderAddress)
            assertEquals("Preview 3", it.bodyPreview)
            assertFalse(it.isRead)
        }
    }

    @Test
    fun `getMessagesForFolder success with empty list returns empty list`() = runTest {
        val mockClient = createMockClient {
            respond(
                content = emptyListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        assertTrue(result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull(messages)
        assertTrue(messages?.isEmpty() == true)
    }

    private fun testGetMessagesApiError(
        statusCode: HttpStatusCode,
        expectedErrorInMessage: String,
        apiErrorCode: String = "SomeApiError",
        apiErrorMessage: String = "Something went wrong"
    ) = runTest {
        val mockClient = createMockClient {
            respond(
                content = graphApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertTrue(
            "Failure: $exception",
            exception?.message?.contains("Error fetching messages for $testFolderId: ${statusCode}") == true
        )
        assertTrue(
            "Failure: $exception",
            exception?.message?.contains(expectedErrorInMessage) == true
        )
    }

    @Test
    fun `getMessagesForFolder handles API error 401 Unauthorized`() =
        testGetMessagesApiError(
            HttpStatusCode.Unauthorized,
            "Access token has expired.",
            "InvalidAuthenticationToken",
            "Access token has expired."
        )

    @Test
    fun `getMessagesForFolder handles API error 403 Forbidden`() =
        testGetMessagesApiError(
            HttpStatusCode.Forbidden,
            "User not allowed.",
            "Authorization_RequestDenied",
            "User not allowed."
        )

    @Test
    fun `getMessagesForFolder handles API error 404 Not Found for folder`() =
        testGetMessagesApiError(
            HttpStatusCode.NotFound,
            "Folder not found.",
            "ItemNotFound",
            "Folder not found."
        )

    @Test
    fun `getMessagesForFolder handles API error 500 InternalServerError`() =
        testGetMessagesApiError(
            HttpStatusCode.InternalServerError,
            "Server is down.",
            "InternalError",
            "Server is down."
        )


    @Test
    fun `getMessagesForFolder handles network exception`() = runTest {
        val networkErrorMessage = "Timeout reading messages"
        val mockClient = createMockClient { throw IOException(networkErrorMessage) }
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertEquals(networkErrorMessage, exception?.message)
    }

    @Test
    fun `getMessagesForFolder handles malformed JSON response`() = runTest {
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
        graphApiHelper = GraphApiHelper(mockClient)

        val result = graphApiHelper.getMessagesForFolder(
            testAccessToken,
            testFolderId,
            testSelectFields,
            testTop
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is SerializationException)
    }
}