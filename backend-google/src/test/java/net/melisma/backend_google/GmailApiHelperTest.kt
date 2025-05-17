package net.melisma.backend_google

import android.util.Log
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.melisma.backend_google.errors.GoogleErrorMapper
import net.melisma.core_data.model.WellKnownFolderType
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GmailApiHelperTest {

    private lateinit var mockHttpClient: HttpClient
    private lateinit var mockErrorMapper: GoogleErrorMapper // Changed from ErrorMapperService
    private lateinit var gmailApiHelper: GmailApiHelper
    private lateinit var json: Json

    // --- Test Constants ---
    private val defaultFolderSelectFields =
        "id,name,messageListVisibility,type,messagesTotal,messagesUnread"
    private val defaultLabelListParams = "maxResults=500" // As per GmailApiHelper

    // Constants for getMessagesForFolder tests
    private val testFolderId_Messages = "INBOX" // Example folderId
    private val testSelectFields_Messages =
        "id,snippet,payload/headers,internalDate,labelIds" // Example fields for Gmail
    private val testMaxResults_Messages = 15
    private val defaultMessagesListParams =
        "maxResults=${testMaxResults_Messages}&q=label:${testFolderId_Messages}&fields=nextPageToken,messages(${testSelectFields_Messages})"

    // Constants for fetchMessageDetails tests
    private val testMessageId_Details = "msg1_gmail_detailed"

    // Example: format=FULL might imply all essential fields, or specific fields can be requested.
    // For simplicity, let's assume a common set of fields or that the API helper handles this.
    private val defaultMessageDetailsParams =
        "format=metadata&metadataHeaders=Subject,From,To,Date" // Example params

    // Constants for getMessagesForThread tests
    private val testThreadId_Messages = "thread1_gmail_active"
    // Assuming similar select fields and max results are applicable or handled by the API helper for threads.
    // The API for threads might be different, e.g. /threads/{threadId} which then contains messages.
    // For now, let's assume the helper abstracts this and we test its output of List<Message>.

    // Constants for message modification tests (mark read/unread, delete, move)
    private val testMessageId_Modify = "msg_gmail_tomodify"
    private val testDestinationFolderId_Move = "Label_ARCHIVE" // Example destination label ID
    private val testSourceFolderId_Move = "INBOX" // Example source label ID to remove

    // --- JSON Test Data ---
    private val validLabelListJsonResponse = """
    {
      "labels": [
        { "id": "INBOX", "name": "INBOX", "type": "system", "messageListVisibility": "show", "messagesTotal": 10, "messagesUnread": 2 },
        { "id": "SENT", "name": "SENT", "type": "system", "messageListVisibility": "show", "messagesTotal": 5, "messagesUnread": 0 },
        { "id": "TRASH", "name": "TRASH", "type": "system", "messageListVisibility": "hide", "messagesTotal": 3, "messagesUnread": 0 },
        { "id": "IMPORTANT", "name": "IMPORTANT", "type": "system", "messageListVisibility": "hide" },
        { "id": "Label_1", "name": "UserFolder1", "type": "user", "messageListVisibility": "show", "messagesTotal": 7, "messagesUnread": 1 },
        { "id": "STARRED", "name": "STARRED", "type": "system", "messageListVisibility": "hide" },
        { "id": "DRAFT", "name": "DRAFT", "type": "system", "messageListVisibility": "hide", "messagesTotal": 1, "messagesUnread": 1}
      ]
    }
    """.trimIndent()

    private val emptyLabelListJsonResponse = """
    {
      "labels": []
    }
    """.trimIndent()

    private fun gmailApiErrorJsonResponse(
        errorCode: Int = 401,
        errorMessage: String = "Invalid credentials"
    ) = """
    {
      "error": {
        "code": $errorCode,
        "message": "$errorMessage",
        "errors": [
          {
            "message": "$errorMessage",
            "domain": "global",
            "reason": "authError"
          }
        ],
        "status": "UNAUTHENTICATED"
      }
    }
    """.trimIndent()

    private val validMessagesListJsonResponse = """
    {
      "messages": [
        {
          "id": "msg1_gmail",
          "threadId": "thread1_gmail",
          "internalDate": "1700000000000", // Example: a long representing epoch milliseconds
          "snippet": "This is a snippet for message 1",
          "payload": {
            "headers": [
              { "name": "Subject", "value": "Gmail Test 1 Subject" },
              { "name": "From", "value": "Sender One <sender1@example.com>" },
              { "name": "To", "value": "recipient@example.com" }
            ]
          },
          "labelIds": ["INBOX", "UNREAD"]
        },
        {
          "id": "msg2_gmail",
          "threadId": "thread2_gmail",
          "internalDate": "1690000000000",
          "snippet": "Snippet for message 2, read.",
          "payload": {
            "headers": [
              { "name": "Subject", "value": "Gmail Test 2 Subject (null sender name)" },
              { "name": "From", "value": "sender2@example.com" }, // No name part
              { "name": "To", "value": "another@example.com" }
            ]
          },
          "labelIds": ["INBOX"]
        },
        {
          "id": "msg3_gmail",
          "threadId": "thread1_gmail", // Part of the same thread as msg1
          "internalDate": "1680000000000",
          "snippet": "Message 3, no specific subject header, but in payload.",
          "payload": {
            "headers": [
              { "name": "From", "value": "Sender Three <sender3@example.com>" }
              // No Subject header
            ]
          },
          "labelIds": ["INBOX", "UNREAD", "STARRED"]
        }
      ],
      "nextPageToken": "nextPageToken123"
    }
    """.trimIndent()

    private val emptyMessagesListJsonResponse = """
    {
      "messages": []
      // Potentially no nextPageToken or an empty one
    }
    """.trimIndent()

    // Reusing malformedJsonResponse from GraphApiHelperTest mentally, or define one if needed
    private val malformedMessagesJsonResponse = """{\"message\":[}""" // Simple malformed

    private val validMessageDetailsJsonResponse = """
    {
      "id": "$testMessageId_Details",
      "threadId": "thread1_gmail_detailed",
      "labelIds": ["INBOX", "UNREAD", "IMPORTANT"],
      "snippet": "Detailed snippet for message $testMessageId_Details.",
      "internalDate": "1710000000000",
      "payload": {
        "mimeType": "multipart/alternative",
        "filename": "",
        "headers": [
          { "name": "Subject", "value": "Detailed Subject: $testMessageId_Details" },
          { "name": "From", "value": "Detailed Sender <sender.detailed@example.com>" },
          { "name": "To", "value": "Recipient Detailed <recipient.detailed@example.com>" },
          { "name": "Date", "value": "Mon, 10 Mar 2025 10:00:00 +0000 (UTC)" },
          { "name": "Message-ID", "value": "<unique-id@example.com>" }
        ],
        "body": { "size": 0 }, // Body might be fetched separately or be part of a more complex payload
        "parts": [
            { 
                "partId": "0", "mimeType": "text/plain", "filename": "", 
                "body": { "size": 100, "data": "VGhpcyBpcyB0aGUgcGxhaW4gdGV4dCBib2R5IQ==" } // "This is the plain text body!"
            },
            { 
                "partId": "1", "mimeType": "text/html", "filename": "", 
                "body": { "size": 150, "data": "PGh0bWw-PGJvZHk-VGhpcyBpcyB0aGUgPGI-SFRNTDwvYj4gYm9keSE8L2JvZHk-PC9odG1sPg==" } // "<html><body>This is the <b>HTML</b> body!</body></html>"
            }
        ]
      },
      "sizeEstimate": 12345
    }
    """.trimIndent()

    companion object {
        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            // Mock Log statically for the entire test class
            mockkStatic(Log::class)
            every { Log.v(any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0 // Specific overload for String
            every { Log.w(any(), isNull<String>()) } returns 0 // Handle null messages for w
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            // Unmock Log after all tests in the class have run
            unmockkStatic(Log::class)
        }
    }

    @Before
    fun setUp() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true // Helpful for fields that might be missing vs. null
        }
        // mockHttpClient will be set in each test by createMockClient
        mockErrorMapper = GoogleErrorMapper() // Instantiate concrete class
        // gmailApiHelper will be set after mockHttpClient is ready
    }

    private fun createMockClient(handler: MockRequestHandler): HttpClient {
        val engine = MockEngine { request ->
            handler(request)
        }
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
            // No default User-Agent for tests to simplify header checks
        }
    }

    // --- getMailFolders (from labels) Tests ---
    @Test
    fun `getMailFolders success returns mapped, typed and filtered folders`() = runTest {
        mockHttpClient = createMockClient { request ->
            assertEquals(
                "https://www.googleapis.com/gmail/v1/users/me/labels?$defaultLabelListParams",
                request.url.toString()
            )
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            respond(
                content = validLabelListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)

        val result = gmailApiHelper.getMailFolders()

        assertTrue("API call should be successful", result.isSuccess)
        val folders = result.getOrNull()
        assertNotNull("Folders list should not be null", folders)
        assertEquals(
            "Incorrect number of mapped folders",
            3,
            folders?.size
        ) // INBOX, SENT, UserFolder1

        folders?.find { it.id == "INBOX" }?.let {
            assertEquals("INBOX", it.displayName) // Original name for now
            assertEquals(10, it.totalItemCount)
            assertEquals(2, it.unreadItemCount)
            assertEquals(WellKnownFolderType.INBOX, it.type)
        } ?: fail("Inbox not found or incorrect")

        folders?.find { it.id == "SENT" }?.let {
            assertEquals("SENT", it.displayName) // Original name
            assertEquals(5, it.totalItemCount)
            assertEquals(0, it.unreadItemCount)
            assertEquals(WellKnownFolderType.SENT_ITEMS, it.type)
        } ?: fail("Sent folder not found or incorrect")

        folders?.find { it.id == "Label_1" }?.let {
            assertEquals("UserFolder1", it.displayName)
            assertEquals(7, it.totalItemCount)
            assertEquals(1, it.unreadItemCount)
            assertEquals(WellKnownFolderType.USER_CREATED, it.type)
        } ?: fail("UserFolder1 not found or incorrect")

        assertNull("TRASH should be filtered out", folders?.find { it.id == "TRASH" })
        assertNull("IMPORTANT should be filtered out", folders?.find { it.id == "IMPORTANT" })
        assertNull("STARRED should be filtered out", folders?.find { it.id == "STARRED" })
        assertNull("DRAFT should be filtered out", folders?.find { it.id == "DRAFT" })
    }

    @Test
    fun `getMailFolders success with empty label list returns empty list`() = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = emptyLabelListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMailFolders()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.isEmpty() == true)
    }

    @Test
    fun `getMailFolders API error returns failure`() = runTest {
        val errorMessage = "Invalid request"
        val errorCode = 400
        mockHttpClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(errorCode, errorMessage),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery {
            mockErrorMapper.mapGmailException(
                any(),
                any()
            )
        } returns IOException(errorMessage) // Ensure mapper is used

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMailFolders()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException) // Assuming mapper converts to IOException
        assertTrue(exception?.message?.contains(errorMessage) == true)
    }

    @Test
    fun `getMailFolders network error returns failure`() = runTest {
        mockHttpClient = createMockClient {
            throw IOException("Network connection failed")
        }
        coEvery {
            mockErrorMapper.mapGmailException(
                any(),
                any()
            )
        } returns IOException("Network connection failed")

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMailFolders()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("Network connection failed", result.exceptionOrNull()?.message)
    }

    // TODO: Test for mapLabelToMailFolder (if it's public, or test indirectly via getMailFolders)

    // --- getMessagesForFolder Tests ---
    @Test
    fun `getMessagesForFolder success returns mapped messages`() = runTest {
        mockHttpClient = createMockClient { request ->
            "https://www.googleapis.com/gmail/v1/users/me/messages?maxResults=$testMaxResults_Messages&q=label%3A$testFolderId_Messages&fields=nextPageToken%2Cmessages(id%2Csnippet%2Cpayload%2Fheaders%2CinternalDate%2ClabelIds)"
            // Note: Actual URL encoding might differ slightly based on Ktor's client behavior, this is a best guess.
            // Specifically, fields parameter might be encoded more aggressively.
            assertTrue(
                "Request URL should start with expected base",
                request.url.toString()
                    .startsWith("https://www.googleapis.com/gmail/v1/users/me/messages")
            )
            assertEquals(testMaxResults_Messages.toString(), request.url.parameters["maxResults"])
            assertEquals("label:$testFolderId_Messages", request.url.parameters["q"])
            assertEquals(
                "nextPageToken,messages(id,snippet,payload/headers,internalDate,labelIds)",
                request.url.parameters["fields"]
            )
            assertEquals("application/json", request.headers[HttpHeaders.Accept])

            respond(
                content = validMessagesListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)

        val result = gmailApiHelper.getMessagesForFolder(
            folderId = testFolderId_Messages,
            selectFields = testSelectFields_Messages.split(","), // Assuming helper takes a list
            maxResults = testMaxResults_Messages,
            pageToken = null
        )

        assertTrue("API call should be successful", result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull("Messages list should not be null", messages)
        assertEquals("Incorrect number of messages returned", 3, messages?.size)

        messages?.find { it.id == "msg1_gmail" }?.let {
            assertEquals("Gmail Test 1 Subject", it.subject)
            assertEquals("Sender One", it.sender?.name)
            assertEquals("sender1@example.com", it.sender?.address)
            assertEquals(1700000000000L, it.receivedTimestampMs)
            assertTrue(it.isUnread)
            assertEquals("This is a snippet for message 1", it.bodyPreview)
        } ?: fail("Message msg1_gmail not found or incorrect")

        messages?.find { it.id == "msg2_gmail" }?.let {
            assertEquals("Gmail Test 2 Subject (null sender name)", it.subject)
            assertNull(
                "Sender name should be null",
                it.sender?.name
            ) // Example: from was just "sender2@example.com"
            assertEquals("sender2@example.com", it.sender?.address)
            assertFalse(it.isUnread)
        } ?: fail("Message msg2_gmail not found or incorrect")

        messages?.find { it.id == "msg3_gmail" }?.let {
            assertNull(
                "Subject should be null or empty if not in headers",
                it.subject
            ) // Assuming payload.headers was checked for 'Subject'
            assertEquals("Sender Three", it.sender?.name)
            assertEquals("sender3@example.com", it.sender?.address)
            assertTrue(it.isUnread)
        } ?: fail("Message msg3_gmail not found or incorrect")
    }

    @Test
    fun `getMessagesForFolder success with empty list returns empty list`() = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = emptyMessagesListJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(
            folderId = testFolderId_Messages,
            selectFields = testSelectFields_Messages.split(","),
            maxResults = testMaxResults_Messages,
            pageToken = null
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.isEmpty() == true)
    }

    private fun testGetMessagesForFolderApiError(
        statusCode: HttpStatusCode,
        apiErrorCode: Int,
        apiErrorMessage: String,
        expectedErrorSubstring: String
    ) = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        // Ensure the mapper is used and returns an IOException as per existing pattern
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            expectedErrorSubstring
        )

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(
            folderId = testFolderId_Messages,
            selectFields = testSelectFields_Messages.split(","),
            maxResults = testMaxResults_Messages,
            pageToken = null
        )

        assertTrue("API call should fail for $statusCode", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("Exception should not be null for $statusCode", exception)
        assertTrue("Exception should be IOException for $statusCode", exception is IOException)
        assertTrue(
            "Failure message '${exception?.message}' did not contain expected substring '$expectedErrorSubstring' for $statusCode",
            exception?.message?.contains(expectedErrorSubstring, ignoreCase = true) == true
        )
    }

    @Test
    fun `getMessagesForFolder API error 400 Bad Request`() =
        testGetMessagesForFolderApiError(
            HttpStatusCode.BadRequest,
            400,
            "Invalid Argument",
            "Invalid Argument"
        )

    @Test
    fun `getMessagesForFolder API error 401 Unauthorized`() =
        testGetMessagesForFolderApiError(
            HttpStatusCode.Unauthorized,
            401,
            "Invalid Credentials",
            "Invalid Credentials"
        )

    @Test
    fun `getMessagesForFolder API error 403 Forbidden`() =
        testGetMessagesForFolderApiError(
            HttpStatusCode.Forbidden,
            403,
            "Insufficient Permission",
            "Insufficient Permission"
        )

    @Test
    fun `getMessagesForFolder API error 404 Not Found`() =
        testGetMessagesForFolderApiError(
            HttpStatusCode.NotFound,
            404,
            "Requested entity was not found",
            "Requested entity was not found"
        )

    @Test
    fun `getMessagesForFolder API error 500 Internal Server Error`() =
        testGetMessagesForFolderApiError(
            HttpStatusCode.InternalServerError,
            500,
            "Backend Error",
            "Backend Error"
        )

    @Test
    fun `getMessagesForFolder network error returns failure`() = runTest {
        val networkErrorMessage = "No internet connection"
        mockHttpClient = createMockClient {
            throw IOException(networkErrorMessage)
        }
        // Ensure the mapper is used for network errors too, if that's the designed behavior
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            networkErrorMessage
        )

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(
            folderId = testFolderId_Messages,
            selectFields = testSelectFields_Messages.split(","),
            maxResults = testMaxResults_Messages,
            pageToken = null
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertEquals(networkErrorMessage, exception?.message)
    }

    @Test
    fun `getMessagesForFolder malformed JSON response returns failure`() = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = malformedMessagesJsonResponse,
                status = HttpStatusCode.OK, // API call is OK, but content is bad
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        // The ContentNegotiation plugin should throw SerializationException, which the helper might catch and map
        // Assuming the mapper would turn a SerializationException into an IOException with relevant message
        coEvery {
            mockErrorMapper.mapGmailException(
                any(),
                any()
            )
        } returns IOException("Failed to parse JSON response")

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForFolder(
            folderId = testFolderId_Messages,
            selectFields = testSelectFields_Messages.split(","),
            maxResults = testMaxResults_Messages,
            pageToken = null
        )

        assertTrue("API call should fail due to malformed JSON", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("Exception should not be null for malformed JSON", exception)
        assertTrue("Exception should be IOException for malformed JSON", exception is IOException)
        assertTrue(
            "Exception message should indicate JSON parsing issue",
            exception?.message?.contains("Failed to parse JSON response", ignoreCase = true) == true
        )
    }

    // --- fetchMessageDetails Tests ---
    @Test
    fun `fetchMessageDetails success returns mapped detailed message`() = runTest {
        mockHttpClient = createMockClient { request ->
            val expectedUrl =
                "https://www.googleapis.com/gmail/v1/users/me/messages/$testMessageId_Details?$defaultMessageDetailsParams"
            // This is an assumption on how params are passed; adjust if helper implementation differs.
            assertEquals(
                expectedUrl,
                request.url.toString().replace("%2C", ",")
            ) // Basic normalization for comma
            assertEquals("application/json", request.headers[HttpHeaders.Accept])

            respond(
                content = validMessageDetailsJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)

        val result = gmailApiHelper.fetchMessageDetails(testMessageId_Details)

        assertTrue("API call should be successful", result.isSuccess)
        val message = result.getOrNull()
        assertNotNull("Message should not be null", message)

        assertEquals(testMessageId_Details, message?.id)
        assertEquals("Detailed Subject: $testMessageId_Details", message?.subject)
        assertEquals("Detailed Sender", message?.sender?.name)
        assertEquals("sender.detailed@example.com", message?.sender?.address)
        assertEquals(1710000000000L, message?.receivedTimestampMs)
        assertTrue(message?.isUnread == true) // Based on "UNREAD" in labelIds
        assertTrue(message?.isImportant == true) // Based on "IMPORTANT" in labelIds
        assertEquals("Detailed snippet for message $testMessageId_Details.", message?.bodyPreview)

        // Check for body content (assuming helper maps from base64 parts)
        assertEquals("This is the plain text body!", message?.bodyText)
        assertEquals("<html><body>This is the <b>HTML</b> body!</body></html>", message?.bodyHtml)
        // Further checks for recipients, etc., can be added if the Message class supports them directly
    }

    private fun testFetchMessageDetailsApiError(
        statusCode: HttpStatusCode,
        apiErrorCode: Int,
        apiErrorMessage: String,
        expectedErrorSubstring: String
    ) = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            expectedErrorSubstring
        )

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.fetchMessageDetails(testMessageId_Details)

        assertTrue("API call should fail for $statusCode", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("Exception should not be null for $statusCode", exception)
        assertTrue("Exception should be IOException for $statusCode", exception is IOException)
        assertTrue(
            "Failure message '${exception?.message}' did not contain expected substring '$expectedErrorSubstring' for $statusCode",
            exception?.message?.contains(expectedErrorSubstring, ignoreCase = true) == true
        )
    }

    @Test
    fun `fetchMessageDetails not found error returns failure`() =
        testFetchMessageDetailsApiError(HttpStatusCode.NotFound, 404, "Not Found", "Not Found")

    @Test
    fun `fetchMessageDetails API error 401 Unauthorized`() =
        testFetchMessageDetailsApiError(
            HttpStatusCode.Unauthorized,
            401,
            "Invalid Credentials",
            "Invalid Credentials"
        )

    @Test
    fun `fetchMessageDetails API error 500 Internal Server Error`() =
        testFetchMessageDetailsApiError(
            HttpStatusCode.InternalServerError,
            500,
            "Backend Error",
            "Backend Error"
        )

    @Test
    fun `fetchMessageDetails network error returns failure`() = runTest {
        val networkErrorMessage = "Failed to connect to Gmail"
        mockHttpClient = createMockClient {
            throw IOException(networkErrorMessage)
        }
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            networkErrorMessage
        )

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.fetchMessageDetails(testMessageId_Details)
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertEquals(networkErrorMessage, exception?.message)
    }

    @Test
    fun `fetchMessageDetails malformed JSON response returns failure`() = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = malformedMessagesJsonResponse, // Can reuse this simple malformed JSON
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery {
            mockErrorMapper.mapGmailException(
                any(),
                any()
            )
        } returns IOException("Bad JSON in message details")

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.fetchMessageDetails(testMessageId_Details)

        assertTrue("API call should fail due to malformed JSON", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("Exception should not be null for malformed JSON", exception)
        assertTrue("Exception should be IOException for malformed JSON", exception is IOException)
        assertTrue(
            "Exception message should indicate JSON parsing issue",
            exception?.message?.contains("Bad JSON in message details", ignoreCase = true) == true
        )
    }

    // --- getMessagesForThread Tests ---
    @Test
    fun `getMessagesForThread success returns mapped messages`() = runTest {
        // Assuming the API call might be to /threads/{id} and then messages are extracted
        // Or it could be a search query `q=in:threadId` to messages endpoint.
        // For this test, we mock the helper's output directly if its internal API call is complex.
        // Let's assume it uses the /threads/{id} endpoint for fetching a thread, which contains messages.

        val threadSpecificMessagesResponse = """
        {
          "id": "$testThreadId_Messages",
          "historyId": "history123",
          "messages": [
            { "id": "msgT1_gmail", "threadId": "$testThreadId_Messages", "internalDate": "1700000000000", "snippet": "Thread Message 1", "payload": { "headers": [{ "name": "Subject", "value": "Thread Subject" },{ "name": "From", "value": "Thread Sender <thread@example.com>" }] }, "labelIds": ["INBOX"] },
            { "id": "msgT2_gmail", "threadId": "$testThreadId_Messages", "internalDate": "1700000001000", "snippet": "Thread Message 2", "payload": { "headers": [{ "name": "From", "value": "Thread Sender <thread@example.com>" }] }, "labelIds": ["INBOX", "UNREAD"] }
          ]
        }
        """.trimIndent()

        mockHttpClient = createMockClient { request ->
            assertTrue(
                "URL should contain /threads/$testThreadId_Messages",
                request.url.toString().contains("/threads/$testThreadId_Messages")
            )
            // Parameters might include fields for messages, e.g. fields=messages(id,snippet,payload/headers,internalDate,labelIds)
            // For simplicity, we're not asserting specific params for this complex call, focusing on the response.
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            respond(
                content = threadSpecificMessagesResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)

        val result = gmailApiHelper.getMessagesForThread(testThreadId_Messages)

        assertTrue("API call should be successful", result.isSuccess)
        val messages = result.getOrNull()
        assertNotNull("Messages list should not be null", messages)
        assertEquals("Incorrect number of messages in thread", 2, messages?.size)

        messages?.find { it.id == "msgT1_gmail" }?.let {
            assertEquals("Thread Subject", it.subject)
            assertTrue(it.isUnread == false) // No UNREAD label
        } ?: fail("Message msgT1_gmail not found")

        messages?.find { it.id == "msgT2_gmail" }?.let {
            assertTrue(it.isUnread == true) // Has UNREAD label
        } ?: fail("Message msgT2_gmail not found")
    }

    @Test
    fun `getMessagesForThread success with empty messages list in thread returns empty list`() =
        runTest {
            val emptyThreadMessagesResponse = """
        {
          "id": "$testThreadId_Messages",
          "historyId": "history124",
          "messages": []
        }
        """.trimIndent()
            mockHttpClient = createMockClient {
                respond(
                    content = emptyThreadMessagesResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json.toString()
                    )
                )
            }
            gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
            val result = gmailApiHelper.getMessagesForThread(testThreadId_Messages)
            assertTrue(result.isSuccess)
            assertTrue("Messages list should be empty", result.getOrNull()?.isEmpty() == true)
        }

    private fun testGetMessagesForThreadApiError(
        statusCode: HttpStatusCode,
        apiErrorCode: Int,
        apiErrorMessage: String,
        expectedErrorSubstring: String
    ) = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            expectedErrorSubstring
        )
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForThread(testThreadId_Messages)
        assertTrue("API call should fail for $statusCode", result.isFailure)
        result.exceptionOrNull()?.let {
            assertTrue("Exception should be IOException for $statusCode", it is IOException)
            assertTrue(
                "Failure message '${it.message}' did not contain '$expectedErrorSubstring' for $statusCode",
                it.message?.contains(expectedErrorSubstring, ignoreCase = true) == true
            )
        } ?: fail("Exception was null for $statusCode")
    }

    @Test
    fun `getMessagesForThread API error 404 Not Found`() =
        testGetMessagesForThreadApiError(
            HttpStatusCode.NotFound,
            404,
            "Thread not found",
            "Thread not found"
        )

    @Test
    fun `getMessagesForThread API error 401 Unauthorized`() =
        testGetMessagesForThreadApiError(
            HttpStatusCode.Unauthorized,
            401,
            "Auth issue",
            "Auth issue"
        )

    @Test
    fun `getMessagesForThread network error returns failure`() = runTest {
        val networkMsg = "Thread network fail"
        mockHttpClient = createMockClient { throw IOException(networkMsg) }
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(networkMsg)
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForThread(testThreadId_Messages)
        assertTrue(result.isFailure)
        assertEquals(networkMsg, result.exceptionOrNull()?.message)
    }

    @Test
    fun `getMessagesForThread malformed JSON returns failure`() = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = malformedMessagesJsonResponse, status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery {
            mockErrorMapper.mapGmailException(
                any(),
                any()
            )
        } returns IOException("Bad JSON for thread")
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.getMessagesForThread(testThreadId_Messages)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Bad JSON for thread") == true)
    }

    // --- markMessageRead/Unread Tests ---
    @Test
    fun `markMessageRead success returns true`() = runTest {
        mockHttpClient = createMockClient { request ->
            assertEquals("POST", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$testMessageId_Modify/modify"))
            val requestBody = request.body.toByteArray().decodeToString()
            // Gmail typically removes UNREAD to mark as read
            assertEquals("""{"removeLabelIds":["UNREAD"]}""", requestBody.trim())
            respond(
                content = "{}", // Minimal valid JSON response, content often not critical for modify
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.markMessageRead(testMessageId_Modify, true)
        assertTrue(
            "Marking message as read should be successful",
            result.isSuccess && result.getOrThrow()
        )
    }

    @Test
    fun `markMessageUnread success returns true`() = runTest {
        mockHttpClient = createMockClient { request ->
            assertEquals("POST", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$testMessageId_Modify/modify"))
            val requestBody = request.body.toByteArray().decodeToString()
            // Gmail typically adds UNREAD to mark as unread
            assertEquals("""{"addLabelIds":["UNREAD"]}""", requestBody.trim())
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result =
            gmailApiHelper.markMessageRead(testMessageId_Modify, false) // markRead(false) is unread
        assertTrue(
            "Marking message as unread should be successful",
            result.isSuccess && result.getOrThrow()
        )
    }

    private fun testMarkMessageReadUnreadError(
        isReadAttempt: Boolean, // true for markRead, false for markUnread
        statusCode: HttpStatusCode,
        apiErrorCode: Int,
        apiErrorMessage: String,
        expectedErrorSubstring: String
    ) = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            expectedErrorSubstring
        )

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.markMessageRead(testMessageId_Modify, isReadAttempt)

        assertTrue("API call should fail", result.isFailure)
        // For modify operations that return Result<Boolean>, a failure might also be represented by isSuccess && !result.getOrThrow()
        // However, the helper function for errors consistently maps to a Result.Failure with an IOException.
        val exception = result.exceptionOrNull()
        assertNotNull("Exception should not be null", exception)
        assertTrue("Exception should be an IOException", exception is IOException)
        assertTrue(
            "Failure message '${exception?.message}' did not contain '$expectedErrorSubstring'",
            exception?.message?.contains(expectedErrorSubstring, ignoreCase = true) == true
        )
    }

    @Test
    fun `markMessageRead API error returns failure`() =
        testMarkMessageReadUnreadError(
            true,
            HttpStatusCode.Forbidden,
            403,
            "Access Denied",
            "Access Denied"
        )

    @Test
    fun `markMessageUnread API error returns failure`() =
        testMarkMessageReadUnreadError(
            false,
            HttpStatusCode.InternalServerError,
            500,
            "Server Error",
            "Server Error"
        )

    // --- deleteMessage Tests ---
    @Test
    fun `deleteMessage success returns true`() = runTest {
        mockHttpClient = createMockClient { request ->
            // Gmail API uses POST to /trash endpoint for moving to trash
            assertEquals("POST", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$testMessageId_Modify/trash"))
            respond(
                // Successful trash usually returns the message resource, or can be 204 if it returns nothing.
                // Let's assume it returns some minimal message info or 204. For boolean result, 204 is fine.
                content = "{}", // Or empty string if 204 and no content expected by client
                status = HttpStatusCode.OK // Or NoContent (204). Gmail API for trash returns the message resource (200)
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.deleteMessage(testMessageId_Modify)
        assertTrue(
            "Deleting message (to trash) should be successful",
            result.isSuccess && result.getOrThrow()
        )
    }

    private fun testDeleteMessageError(
        statusCode: HttpStatusCode,
        apiErrorCode: Int,
        apiErrorMessage: String,
        expectedErrorSubstring: String
    ) = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            expectedErrorSubstring
        )

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.deleteMessage(testMessageId_Modify)
        assertTrue("API call should fail", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertTrue(exception?.message?.contains(expectedErrorSubstring, ignoreCase = true) == true)
    }

    @Test
    fun `deleteMessage API error returns failure`() =
        testDeleteMessageError(
            HttpStatusCode.Forbidden,
            403,
            "Cannot delete message",
            "Cannot delete message"
        )

    // --- moveMessage Tests ---
    @Test
    fun `moveMessage success returns new message data`() = runTest {
        // For Gmail, moving is modifying labels.
        // The response is the modified message resource.
        val movedMessageJsonResponse = """
        {
          "id": "$testMessageId_Modify",
          "threadId": "thread_moved_gmail",
          "labelIds": ["$testDestinationFolderId_Move", "IMPORTANT"] // Should no longer have sourceFolderId
        }
        """.trimIndent()

        mockHttpClient = createMockClient { request ->
            assertEquals("POST", request.method.value)
            assertTrue(request.url.toString().endsWith("/messages/$testMessageId_Modify/modify"))
            val requestBody = request.body.toByteArray().decodeToString()
            val expectedBody =
                """{"addLabelIds":["$testDestinationFolderId_Move"],"removeLabelIds":["$testSourceFolderId_Move"]}"""
            assertEquals(expectedBody, requestBody.trim())
            respond(
                content = movedMessageJsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.moveMessage(
            testMessageId_Modify,
            testDestinationFolderId_Move,
            testSourceFolderId_Move
        )

        assertTrue("Moving message should be successful", result.isSuccess)
        val movedMessage = result.getOrNull()
        assertNotNull("Moved message data should not be null", movedMessage)
        assertEquals(testMessageId_Modify, movedMessage?.id)
        assertTrue(
            "Moved message should have destination label",
            movedMessage?.labelIds?.contains(testDestinationFolderId_Move) == true
        )
        assertFalse(
            "Moved message should not have source label",
            movedMessage?.labelIds?.contains(testSourceFolderId_Move) == true
        )
    }

    private fun testMoveMessageError(
        statusCode: HttpStatusCode,
        apiErrorCode: Int,
        apiErrorMessage: String,
        expectedErrorSubstring: String
    ) = runTest {
        mockHttpClient = createMockClient {
            respond(
                content = gmailApiErrorJsonResponse(apiErrorCode, apiErrorMessage),
                status = statusCode,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        }
        coEvery { mockErrorMapper.mapGmailException(any(), any()) } returns IOException(
            expectedErrorSubstring
        )

        gmailApiHelper = GmailApiHelper(mockHttpClient, mockErrorMapper)
        val result = gmailApiHelper.moveMessage(
            testMessageId_Modify,
            testDestinationFolderId_Move,
            testSourceFolderId_Move
        )
        assertTrue("API call should fail", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IOException)
        assertTrue(exception?.message?.contains(expectedErrorSubstring, ignoreCase = true) == true)
        assertNull(
            "Moved message data should be null on failure",
            result.getOrNull()
        ) // For Result<Message?>
    }

    @Test
    fun `moveMessage API error returns failure`() =
        testMoveMessageError(
            HttpStatusCode.BadRequest,
            400,
            "Invalid label IDs",
            "Invalid label IDs"
        )

}