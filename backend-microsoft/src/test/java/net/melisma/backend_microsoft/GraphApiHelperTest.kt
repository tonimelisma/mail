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
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.model.WellKnownFolderType // Added import
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull // Added import
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

    // Updated to include wellKnownName in the select parameter for fetching folders
    private val defaultFoldersParams =
        "\$select=id,displayName,totalItemCount,unreadItemCount,wellKnownName&\$top=100"


    // --- JSON Test Data ---
    private val validFoldersJsonResponse = """
        {
          "value": [
            { "id": "id_sent", "displayName": "Sent Items", "totalItemCount": 50, "unreadItemCount": 0, "wellKnownName": "sentitems" },
            { "id": "id_inbox", "displayName": "Inbox", "totalItemCount": 100, "unreadItemCount": 10, "wellKnownName": "inbox" },
            { "id": "id_archive", "displayName": "Archive", "totalItemCount": 20, "unreadItemCount": 1, "wellKnownName": "archive" },
            { "id": "id_deleted", "displayName": "Deleted Items", "totalItemCount": 30, "unreadItemCount": 0, "wellKnownName": "deleteditems" },
            { "id": "id_drafts", "displayName": "Drafts", "totalItemCount": 5, "unreadItemCount": 5, "wellKnownName": "drafts" },
            { "id": "id_junk", "displayName": "Junk E-mail", "totalItemCount": 40, "unreadItemCount": 40, "wellKnownName": "junkemail" },
            { "id": "id_notes", "displayName": "Notes", "totalItemCount": 3, "unreadItemCount": 0, "wellKnownName": "notes" },
            { "id": "id_sync_issues", "displayName": "Sync Issues", "totalItemCount": 0, "unreadItemCount": 0, "wellKnownName": "syncissues" },
            { "id": "id_user_folder", "displayName": "My Custom Folder", "totalItemCount": 10, "unreadItemCount": 2, "wellKnownName": null }
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
    fun `getMailFolders success returns mapped, typed and sorted folders, filtering hidden ones`() =
        runTest {
            val mockClient = createMockClient { request ->
                // Check if the URL includes the $select and $top parameters correctly
                val expectedUrl =
                    "https://graph.microsoft.com/v1.0/me/mailFolders?$defaultFoldersParams".replace(
                        "%24",
                        "$"
                    )
                assertEquals(expectedUrl, request.url.toString())
                assertEquals(
                    ContentType.Application.Json.toString(),
                    request.headers[HttpHeaders.Accept]
                )
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

            assertTrue("API call should be successful", result.isSuccess)
        val folders = result.getOrNull()
            assertNotNull("Folders list should not be null", folders)

            // Expected visible: Archive, Deleted Items, Drafts, Inbox, Junk E-mail, My Custom Folder, Sent Items
            // Hidden: Notes, Sync Issues
            assertEquals("Incorrect number of visible folders", 7, folders?.size)

            // Check sorting and mapping for a few key folders
            folders?.find { it.id == "id_inbox" }?.let {
                assertEquals("Inbox", it.displayName)
                assertEquals(WellKnownFolderType.INBOX, it.type)
            } ?: fail("Inbox not found or incorrect")

            folders?.find { it.id == "id_sent" }?.let {
                assertEquals("Sent Items", it.displayName)
                assertEquals(WellKnownFolderType.SENT_ITEMS, it.type)
            } ?: fail("Sent Items not found or incorrect")

            folders?.find { it.id == "id_archive" }?.let {
                assertEquals("Archive", it.displayName)
                assertEquals(WellKnownFolderType.ARCHIVE, it.type)
            } ?: fail("Archive not found or incorrect")

            folders?.find { it.id == "id_deleted" }?.let {
                assertEquals("Trash", it.displayName) // Standardized name
                assertEquals(WellKnownFolderType.TRASH, it.type)
            } ?: fail("Trash (Deleted Items) not found or incorrect")

            folders?.find { it.id == "id_junk" }?.let {
                assertEquals("Spam", it.displayName) // Standardized name
                assertEquals(WellKnownFolderType.SPAM, it.type)
            } ?: fail("Spam (Junk E-mail) not found or incorrect")

            folders?.find { it.id == "id_user_folder" }?.let {
                assertEquals("My Custom Folder", it.displayName)
                assertEquals(WellKnownFolderType.USER_CREATED, it.type)
            } ?: fail("Custom folder not found or incorrect")

            // Verify alphabetical sorting by displayName
        assertEquals("Archive", folders?.get(0)?.displayName)
            assertEquals("Drafts", folders?.get(1)?.displayName)
            // ... (can add more checks for full sort order if critical)
            assertEquals("Sent Items", folders?.get(6)?.displayName)


            // Assert that hidden folders are not present
            assertNull("Notes folder should be hidden", folders?.find { it.id == "id_notes" })
            assertNull(
                "Sync Issues folder should be hidden",
                folders?.find { it.id == "id_sync_issues" })
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
        testGetMailFoldersApiError(HttpStatusCode.Unauthorized, "Access token has expired.")

    @Test
    fun `getMailFolders handles API error 403 Forbidden`() =
        testGetMailFoldersApiError(
            HttpStatusCode.Forbidden,
            "User not allowed.",
            apiErrorMessage = "User not allowed."
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
        assertTrue(exception is SerializationException) // Ktor throws SerializationException for malformed JSON
    }

    // --- getMessagesForFolder Tests ---
    // (These tests remain largely the same as they don't depend on folder type mapping,
    // but ensure they still pass with any refactoring in GraphApiHelper if it occurred)

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
        testGetMessagesApiError(HttpStatusCode.Unauthorized, "Access token has expired.")

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
        assertTrue(exception is SerializationException)
    }

    // --- getMessageById Tests (assuming similar structure to getMessagesForFolder) ---
    @Test
    fun `getMessageById success returns mapped message`() = runTest {
        val messageId = "testMsgId123"
        val singleMessageJsonResponse = """
        {
          "id": "$messageId",
          "receivedDateTime": "2023-03-01T12:00:00Z",
          "subject": "Single Message Subject",
          "bodyPreview": "Body preview for single message...",
          "isRead": false,
          "sender": { "emailAddress": { "name": "Sender Name", "address": "sender@example.com" } }
        }
        """.trimIndent()

        val mockClient = createMockClient { request ->
            assertTrue(
                "URL should contain /messages/$messageId",
                request.url.toString().contains("/messages/$messageId")
            )
            assertEquals(
                ContentType.Application.Json.toString(),
                request.headers[HttpHeaders.Accept]
            )
            respond(
                content = singleMessageJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.getMessageById(messageId)

        assertTrue("API call should be successful", result.isSuccess)
        val message = result.getOrNull()
        assertNotNull("Message should not be null", message)
        assertEquals(messageId, message?.id)
        assertEquals("Single Message Subject", message?.subject)
    }

    @Test
    fun `getMessageById not found error returns failure`() = runTest {
        val messageId = "nonExistentMsgId"
        val mockClient = createMockClient {
            respond(
                content = graphApiErrorJsonResponse(
                    "ErrorItemNotFound",
                    "The specified object was not found in the store."
                ),
                status = HttpStatusCode.NotFound,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.getMessageById(messageId)

        assertTrue("API call should fail", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("Exception should not be null", exception)
        assertTrue(
            "Exception message should indicate item not found",
            exception?.message?.contains("Item not found", ignoreCase = true) == true
        )
    }

    // --- markMessageRead/Unread Tests ---
    @Test
    fun `markMessageRead success returns true`() = runTest {
        val messageId = "testMsgIdToMarkRead"
        val mockClient = createMockClient { request ->
            assertEquals("PATCH", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$messageId"))
            // Check request body for {"isRead":true}
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals("""{"isRead":true}""", requestBody.trim())
            respond(
                content = "", // Typically no content for successful PATCH
                status = HttpStatusCode.OK
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.markMessageRead(messageId, true)
        assertTrue(
            "Marking message as read should be successful",
            result.isSuccess && result.getOrThrow()
        )
    }

    @Test
    fun `markMessageUnread success returns true`() = runTest {
        val messageId = "testMsgIdToMarkUnread"
        val mockClient = createMockClient { request ->
            assertEquals("PATCH", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$messageId"))
            // Check request body for {"isRead":false}
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals("""{"isRead":false}""", requestBody.trim())
            respond(
                content = "", // Typically no content for successful PATCH
                status = HttpStatusCode.OK
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.markMessageRead(messageId, false) // markRead(false) is unread
        assertTrue(
            "Marking message as unread should be successful",
            result.isSuccess && result.getOrThrow()
        )
    }

    @Test
    fun `markMessageRead API error returns false`() = runTest {
        val messageId = "testMsgIdApiError"
        val mockClient = createMockClient {
            respond(
                content = graphApiErrorJsonResponse(
                    "ErrorInternalServerError",
                    "Server error on PATCH"
                ),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.markMessageRead(messageId, true)
        assertTrue(
            "Marking message as read should fail",
            result.isFailure || (result.isSuccess && !result.getOrThrow())
        )
    }

    // --- deleteMessage Tests ---
    @Test
    fun `deleteMessage success returns true`() = runTest {
        val messageId = "testMsgIdToDelete"
        val mockClient = createMockClient { request ->
            assertEquals("DELETE", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$messageId"))
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.deleteMessage(messageId)
        assertTrue("Deleting message should be successful", result.isSuccess && result.getOrThrow())
    }

    @Test
    fun `deleteMessage API error returns false`() = runTest {
        val messageId = "testMsgIdDeleteError"
        val mockClient = createMockClient {
            respond(
                content = graphApiErrorJsonResponse("ErrorAccessDenied", "Cannot delete message"),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.deleteMessage(messageId)
        assertTrue(
            "Deleting message should fail",
            result.isFailure || (result.isSuccess && !result.getOrThrow())
        )
    }

    // --- moveMessage Tests ---
    @Test
    fun `moveMessage success returns new messageId and true`() = runTest {
        val messageId = "testMsgIdToMove"
        val destinationFolderId = "archiveFolderId"
        val movedMessageId = "movedMsgId_123"
        // Example response for a successful move, includes the new message item
        val moveSuccessJsonResponse = """
        {
          "id": "$movedMessageId",
          "subject": "Moved Message Subject"
        }
        """.trimIndent()

        val mockClient = createMockClient { request ->
            assertEquals("POST", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$messageId/move"))
            // Check request body for {"destinationId":"archiveFolderId"}
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals("""{"destinationId":"$destinationFolderId"}""", requestBody.trim())
            respond(
                content = moveSuccessJsonResponse,
                status = HttpStatusCode.Created, // Or OK depending on API
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.moveMessage(messageId, destinationFolderId)
        assertTrue("Moving message should be successful", result.isSuccess)
        val movedMessage = result.getOrNull()
        assertNotNull("Moved message data should not be null", movedMessage)
        assertEquals(movedMessageId, movedMessage?.id)
    }

    @Test
    fun `moveMessage API error returns failure`() = runTest {
        val messageId = "testMsgIdMoveError"
        val destinationFolderId = "archiveFolderId"
        val mockClient = createMockClient {
            respond(
                content = graphApiErrorJsonResponse(
                    "ErrorInvalidDestination",
                    "Destination folder not found"
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        graphApiHelper = GraphApiHelper(mockClient, mockErrorMapper)
        val result = graphApiHelper.moveMessage(messageId, destinationFolderId)
        assertTrue("Moving message should fail", result.isFailure)
        assertNull("Moved message data should be null on failure", result.getOrNull())
    }
}