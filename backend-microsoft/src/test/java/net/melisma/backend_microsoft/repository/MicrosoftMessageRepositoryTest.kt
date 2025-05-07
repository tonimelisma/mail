package net.melisma.backend_microsoft.repository

// import net.melisma.backend_microsoft.errors.ErrorMapper // OLD IMPORT
import android.app.Activity
import android.util.Log
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.core_common.errors.ErrorMapperService
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDataState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MicrosoftMessageRepositoryTest {

    // --- Mocks ---
    private lateinit var tokenProvider: TokenProvider
    private lateinit var graphApiHelper: GraphApiHelper
    private lateinit var mockErrorMapper: ErrorMapperService // Mock the INTERFACE
    private lateinit var mockActivity: Activity
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    // --- Class Under Test ---
    private lateinit var repository: MicrosoftMessageRepository

    // --- Test Data ---
    private val testAccount = Account("ms_id_123", "test@outlook.com", "MS")
    private val nonMsAccount = Account("goog_id_456", "test@gmail.com", "GOOG")
    private val testFolder = MailFolder("folder_inbox", "Inbox", 15, 10)
    private val testFolder2 = MailFolder("folder_sent", "Sent", 5, 0)
    private val testMessage1 = Message(
        "msg1",
        "2025-01-01T10:00:00Z",
        "Subject A",
        "Sender A",
        "senderA@test.com",
        "Body A...",
        false
    )
    private val testMessage2 = Message(
        "msg2",
        "2025-01-02T12:00:00Z",
        "Subject B",
        "Sender B",
        "senderB@test.com",
        "Body B...",
        true
    )
    private val testMessages = listOf(testMessage1, testMessage2)
    private val mailReadScope = listOf("Mail.Read")
    private val messageListFields =
        listOf("id", "receivedDateTime", "subject", "sender", "isRead", "bodyPreview")
    private val messageListPageSize = 25

    private val tokenNoActivity = "token-for-null-activity"
    private val tokenWithActivity = "token-for-mock-activity"
    private val tokenFolder2 = "token-for-folder2"
    private val messagesForFolder2 = listOf(testMessage2)


    @Before
    fun setUp() {
        tokenProvider = mockk()
        graphApiHelper = mockk()
        mockErrorMapper = mockk() // Mock the interface
        mockActivity = mockk()
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        // Mock token provider calls
        coEvery {
            tokenProvider.getAccessToken(
                eq(testAccount),
                eq(mailReadScope),
                isNull()
            )
        } returns Result.success(tokenNoActivity) andThen Result.success(tokenFolder2) // Sequence for multiple calls if needed
        coEvery {
            tokenProvider.getAccessToken(
                eq(testAccount),
                eq(mailReadScope),
                eq(mockActivity)
            )
        } returns Result.success(tokenWithActivity)

        // Mock Graph API calls
        coEvery {
            graphApiHelper.getMessagesForFolder(
                eq(tokenNoActivity),
                eq(testFolder.id),
                eq(messageListFields),
                eq(messageListPageSize)
            )
        } returns Result.success(testMessages)
        coEvery {
            graphApiHelper.getMessagesForFolder(
                eq(tokenWithActivity),
                eq(testFolder.id),
                eq(messageListFields),
                eq(messageListPageSize)
            )
        } returns Result.success(testMessages) // Default for refresh
        coEvery {
            graphApiHelper.getMessagesForFolder(
                eq(tokenFolder2),
                eq(testFolder2.id),
                eq(messageListFields),
                eq(messageListPageSize)
            )
        } returns Result.success(messagesForFolder2) // For folder 2

        // Mock the interface methods
        every { mockErrorMapper.mapAuthExceptionToUserMessage(any()) } answers {
            "Mapped Auth Error: " + ((args[0] as? Throwable)?.message ?: "Unknown Auth")
        }
        every { mockErrorMapper.mapNetworkOrApiException(any()) } answers {
            "Mapped Network/API Error: " + ((args[0] as? Throwable)?.message
                ?: "Unknown Network/API")
        }

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Pass the mocked INTERFACE to the constructor
        repository = MicrosoftMessageRepository(
            tokenProvider = tokenProvider,
            graphApiHelper = graphApiHelper,
            ioDispatcher = testDispatcher,
            externalScope = testScope,
            errorMapper = mockErrorMapper // Use the mocked interface
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkAll()
    }

    // --- Test Cases ---

    @Test
    fun `initial messageDataState is Initial`() = testScope.runTest {
        assertEquals(MessageDataState.Initial, repository.messageDataState.value)
    }

    @Test
    fun `setTargetFolder with null account clears state to Initial`() = testScope.runTest {
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success }

        repository.setTargetFolder(null, testFolder)
        assertEquals(MessageDataState.Initial, repository.messageDataState.value)
    }

    @Test
    fun `setTargetFolder with null folder clears state to Initial`() = testScope.runTest {
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success }

        repository.setTargetFolder(testAccount, null)
        assertEquals(MessageDataState.Initial, repository.messageDataState.value)
    }

    @Test
    fun `setTargetFolder with non MS account sets state to Initial`() = testScope.runTest {
        // Arrange: Set an initial MS target first
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success } // Consume success state

        repository.messageDataState.test {
            assertEquals(MessageDataState.Success(testMessages), awaitItem()) // Current state

            // Act: Set non-MS account
            repository.setTargetFolder(nonMsAccount, testFolder)

            // Assert: State becomes Initial immediately
            assertEquals(MessageDataState.Initial, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } // Only initial call
        coVerify(exactly = 0) { tokenProvider.getAccessToken(nonMsAccount, any(), any()) }
    }

    @Test
    fun `setTargetFolder triggers fetch and updates state on success`() = testScope.runTest {
        repository.messageDataState.test {
            assertEquals(MessageDataState.Initial, awaitItem())
            repository.setTargetFolder(testAccount, testFolder)
            assertEquals(MessageDataState.Loading, awaitItem())
            advanceUntilIdle()
            assertEquals(MessageDataState.Success(testMessages), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount, mailReadScope, null) }
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                tokenNoActivity,
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        }
    }

    @Test
    fun `setTargetFolder skips fetch if target is the same`() = testScope.runTest {
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success }

        repository.setTargetFolder(testAccount, testFolder) // Set same target again
        advanceUntilIdle()

        repository.messageDataState.test {
            assertEquals(MessageDataState.Success(testMessages), awaitItem())
            expectNoEvents()
            cancel()
        }
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } // Verify only called once
    }

    @Test
    fun `setTargetFolder handles token fetch failure`() = testScope.runTest {
        val tokenError = RuntimeException("Token fetch failed")
        coEvery {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } returns Result.failure(tokenError)
        // Mock specific error mapping
        every { mockErrorMapper.mapAuthExceptionToUserMessage(tokenError) } returns "Mapped Token Error"

        repository.messageDataState.test {
            assertEquals(MessageDataState.Initial, awaitItem())
            repository.setTargetFolder(testAccount, testFolder)
            assertEquals(MessageDataState.Loading, awaitItem())
            advanceUntilIdle()
            assertEquals(MessageDataState.Error("Mapped Token Error"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount, mailReadScope, null) }
        coVerify(exactly = 0) { graphApiHelper.getMessagesForFolder(any(), any(), any(), any()) }
        verify { mockErrorMapper.mapAuthExceptionToUserMessage(tokenError) } // Verify mapper call
    }

    @Test
    fun `setTargetFolder handles Graph API failure`() = testScope.runTest {
        val graphError = IOException("Graph network error")
        // Ensure token succeeds
        coEvery {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } returns Result.success(tokenNoActivity)
        // Mock API failure
        coEvery {
            graphApiHelper.getMessagesForFolder(
                tokenNoActivity,
                testFolder.id,
                any(),
                any()
            )
        } returns Result.failure(graphError)
        // Mock specific error mapping
        every { mockErrorMapper.mapNetworkOrApiException(graphError) } returns "Mapped Graph Error"


        repository.messageDataState.test {
            assertEquals(MessageDataState.Initial, awaitItem())
            repository.setTargetFolder(testAccount, testFolder)
            assertEquals(MessageDataState.Loading, awaitItem())
            advanceUntilIdle()
            assertEquals(MessageDataState.Error("Mapped Graph Error"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount, mailReadScope, null) }
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                tokenNoActivity,
                testFolder.id,
                any(),
                any()
            )
        }
        verify { mockErrorMapper.mapNetworkOrApiException(graphError) } // Verify mapper call
    }

    @Test
    fun `refreshMessages re-fetches for current target using activity`() = testScope.runTest {
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success } // Consume initial

        val refreshedMessages = listOf(testMessage1)
        coEvery {
            graphApiHelper.getMessagesForFolder(
                tokenWithActivity,
                testFolder.id,
                any(),
                any()
            )
        } returns Result.success(refreshedMessages)

        repository.messageDataState.test {
            assertEquals(MessageDataState.Success(testMessages), awaitItem()) // Current state
            repository.refreshMessages(mockActivity)
            assertEquals(MessageDataState.Loading, awaitItem())
            advanceUntilIdle()
            assertEquals(MessageDataState.Success(refreshedMessages), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } // Initial
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                mockActivity
            )
        } // Refresh
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                tokenNoActivity,
                testFolder.id,
                any(),
                any()
            )
        } // Initial
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                tokenWithActivity,
                testFolder.id,
                any(),
                any()
            )
        } // Refresh
    }


    @Test
    fun `refreshMessages does nothing if no target folder is set`() = testScope.runTest {
        assertEquals(MessageDataState.Initial, repository.messageDataState.value)
        repository.refreshMessages(mockActivity)
        advanceUntilIdle()
        repository.messageDataState.test {
            assertEquals(MessageDataState.Initial, awaitItem())
            expectNoEvents()
            cancel()
        }
        coVerify(exactly = 0) { tokenProvider.getAccessToken(any(), any(), any()) }
    }

    @Test
    fun `refreshMessages does nothing if already loading`() = testScope.runTest {
        val fetchDeferred = CompletableDeferred<Result<List<Message>>>()
        coEvery {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } returns Result.success(tokenNoActivity)
        coEvery {
            graphApiHelper.getMessagesForFolder(
                tokenNoActivity,
                testFolder.id,
                any(),
                any()
            )
        } coAnswers { fetchDeferred.await() }

        repository.setTargetFolder(testAccount, testFolder)
        assertEquals(
            MessageDataState.Loading,
            repository.messageDataState.first { it == MessageDataState.Loading })

        repository.refreshMessages(mockActivity) // Attempt refresh while loading
        advanceUntilIdle()

        assertEquals(MessageDataState.Loading, repository.messageDataState.value) // Still loading

        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } // Only initial token call
        coVerify(exactly = 0) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                mockActivity
            )
        } // No refresh token call

        fetchDeferred.complete(Result.success(testMessages)) // Allow test to finish
        advanceUntilIdle()
    }

    @Test
    fun `setTargetFolder cancels previous fetch if target changes`() = testScope.runTest {
        val fetch1Deferred = CompletableDeferred<Result<List<Message>>>()
        val fetch1Token = tokenNoActivity
        val fetch2Token = tokenFolder2

        // Mock token sequence for the two setTargetFolder calls
        coEvery {
            tokenProvider.getAccessToken(
                eq(testAccount),
                eq(mailReadScope),
                isNull()
            )
        } returns Result.success(fetch1Token) andThen Result.success(fetch2Token)
        // Mock API for first fetch (will hang)
        coEvery {
            graphApiHelper.getMessagesForFolder(
                fetch1Token,
                testFolder.id,
                any(),
                any()
            )
        } coAnswers { fetch1Deferred.await() }
        // Mock API for second fetch (should succeed)
        coEvery {
            graphApiHelper.getMessagesForFolder(
                fetch2Token,
                testFolder2.id,
                any(),
                any()
            )
        } returns Result.success(messagesForFolder2)


        repository.messageDataState.test {
            assertEquals(MessageDataState.Initial, awaitItem()) // 1. Initial

            repository.setTargetFolder(testAccount, testFolder) // Start fetch 1
            assertEquals(MessageDataState.Loading, awaitItem()) // 2. Loading (Fetch 1)

            repository.setTargetFolder(testAccount, testFolder2) // Cancel 1, Start fetch 2
            // Expect Loading again because the second fetch starts immediately
            assertEquals(MessageDataState.Loading, awaitItem()) // 3. Loading (Fetch 2)

            advanceUntilIdle() // Let fetch 2 complete

            // Expect Success from fetch 2
            assertEquals(
                MessageDataState.Success(messagesForFolder2),
                awaitItem()
            ) // 4. Success (Fetch 2)

            // Now complete the first deferred - should have no effect as job was cancelled
            fetch1Deferred.complete(Result.success(testMessages))
            advanceUntilIdle()

            expectNoEvents() // Assert no state change from completed cancelled job
            cancel()
        }

        coVerify(exactly = 2) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } // Token called twice
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                fetch1Token,
                testFolder.id,
                any(),
                any()
            )
        } // Fetch 1 attempted
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                fetch2Token,
                testFolder2.id,
                any(),
                any()
            )
        } // Fetch 2 completed
    }
}
