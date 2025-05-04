package net.melisma.backend_microsoft.repository

import android.app.Activity
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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
import net.melisma.backend_microsoft.errors.ErrorMapper
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDataState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MicrosoftMessageRepositoryTest {

    // --- Mocks ---
    private lateinit var tokenProvider: TokenProvider
    private lateinit var graphApiHelper: GraphApiHelper
    private lateinit var errorMapper: ErrorMapper
    private lateinit var mockActivity: Activity
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    // --- Class Under Test ---
    private lateinit var repository: MicrosoftMessageRepository

    // --- Test Data ---
    private val testAccount = Account("ms_id_123", "test@outlook.com", "MS")
    private val nonMsAccount = Account("goog_id_456", "test@gmail.com", "GOOG")
    private val testFolder =
        MailFolder("folder_inbox", "Inbox", totalItemCount = 15, unreadItemCount = 10)
    private val testFolder2 =
        MailFolder("folder_sent", "Sent", totalItemCount = 5, unreadItemCount = 0)
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

    @Before
    fun setUp() {
        tokenProvider = mockk()
        graphApiHelper = mockk()
        errorMapper = mockk(relaxed = true)
        mockActivity = mockk()
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        coEvery {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                null
            )
        } returns Result.success("token-no-activity")
        coEvery {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                mockActivity
            )
        } returns Result.success("token-with-activity")
        coEvery {
            graphApiHelper.getMessagesForFolder(
                any(),
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        } returns Result.success(testMessages)
        coEvery {
            graphApiHelper.getMessagesForFolder(
                any(),
                testFolder2.id,
                messageListFields,
                messageListPageSize
            )
        } returns Result.success(listOf(testMessage2))
        every { errorMapper.mapAuthExceptionToUserMessage(any()) } returns "Auth Error"
        every { errorMapper.mapGraphExceptionToUserMessage(any()) } returns "Graph API Error"

        repository = MicrosoftMessageRepository(
            tokenProvider = tokenProvider,
            graphApiHelper = graphApiHelper,
            ioDispatcher = testDispatcher,
            externalScope = testScope,
            errorMapper = errorMapper
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initial messageDataState is Initial`() = testScope.runTest {
        repository.messageDataState.test {
            assertEquals(MessageDataState.Initial, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setTargetFolder with null account clears state to Initial`() = testScope.runTest {
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success } // Use first with predicate

        repository.setTargetFolder(null, testFolder)
        advanceUntilIdle()

        assertEquals(MessageDataState.Initial, repository.messageDataState.value)
        coVerify(exactly = 1) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 1) { graphApiHelper.getMessagesForFolder(any(), any(), any(), any()) }
    }

    @Test
    fun `setTargetFolder with null folder clears state to Initial`() = testScope.runTest {
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success }

        repository.setTargetFolder(testAccount, null)
        advanceUntilIdle()

        assertEquals(MessageDataState.Initial, repository.messageDataState.value)
        coVerify(exactly = 1) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 1) { graphApiHelper.getMessagesForFolder(any(), any(), any(), any()) }
    }

    @Test
    fun `setTargetFolder with non MS account sets error state`() = testScope.runTest {
        repository.setTargetFolder(nonMsAccount, testFolder)
        advanceUntilIdle()

        val state = repository.messageDataState.value
        assertTrue(state is MessageDataState.Error)
        assertEquals("Unsupported account type.", (state as MessageDataState.Error).error)
        coVerify(exactly = 0) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 0) { graphApiHelper.getMessagesForFolder(any(), any(), any(), any()) }
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
                "token-no-activity",
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

        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()

        repository.messageDataState.test {
            assertEquals(MessageDataState.Success(testMessages), awaitItem())
            expectNoEvents() // Expect no change
            cancel()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 1) { graphApiHelper.getMessagesForFolder(any(), any(), any(), any()) }
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
        every { errorMapper.mapAuthExceptionToUserMessage(tokenError) } returns "Mapped Token Error"

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
        verify { errorMapper.mapAuthExceptionToUserMessage(tokenError) }
    }

    @Test
    fun `setTargetFolder handles Graph API failure`() = testScope.runTest {
        val graphError = IOException("Graph network error")
        coEvery {
            graphApiHelper.getMessagesForFolder(
                "token-no-activity",
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        } returns Result.failure(graphError)
        every { errorMapper.mapGraphExceptionToUserMessage(graphError) } returns "Mapped Graph Error"

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
                "token-no-activity",
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        }
        verify { errorMapper.mapGraphExceptionToUserMessage(graphError) }
    }

    @Test
    fun `refreshMessages re-fetches for current target using activity`() = testScope.runTest {
        repository.setTargetFolder(testAccount, testFolder)
        advanceUntilIdle()
        repository.messageDataState.first { it is MessageDataState.Success } // Use first with predicate

        val refreshedMessages = listOf(testMessage1)
        coEvery {
            graphApiHelper.getMessagesForFolder(
                "token-with-activity",
                testFolder.id,
                messageListFields,
                messageListPageSize
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
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount, mailReadScope, null) }
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                mockActivity
            )
        }
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                "token-no-activity",
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        }
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                "token-with-activity",
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        }
    }

    @Test
    fun `refreshMessages does nothing if no target folder is set`() = testScope.runTest {
        assertEquals(MessageDataState.Initial, repository.messageDataState.value)

        repository.refreshMessages(mockActivity)
        advanceUntilIdle()

        assertEquals(MessageDataState.Initial, repository.messageDataState.value)
        coVerify(exactly = 0) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 0) { graphApiHelper.getMessagesForFolder(any(), any(), any(), any()) }
    }

    @Test
    fun `refreshMessages does nothing if already loading`() = testScope.runTest {
        val fetchDeferred = CompletableDeferred<Result<List<Message>>>()
        coEvery {
            graphApiHelper.getMessagesForFolder(
                any(),
                testFolder.id,
                any(),
                any()
            )
        } coAnswers { fetchDeferred.await() }
        repository.setTargetFolder(testAccount, testFolder)
        assertEquals(
            MessageDataState.Loading,
            repository.messageDataState.first { it == MessageDataState.Loading }) // Use first with predicate

        repository.refreshMessages(mockActivity)
        advanceUntilIdle()

        assertEquals(MessageDataState.Loading, repository.messageDataState.value)
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount, mailReadScope, null) }
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                "token-no-activity",
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        }
        coVerify(exactly = 0) {
            tokenProvider.getAccessToken(
                testAccount,
                mailReadScope,
                mockActivity
            )
        }

        fetchDeferred.complete(Result.success(testMessages)) // Cleanup
    }

    @Test
    fun `setTargetFolder cancels previous fetch if target changes`() = testScope.runTest {
        val fetch1Deferred = CompletableDeferred<Result<List<Message>>>()
        coEvery {
            graphApiHelper.getMessagesForFolder(
                "token-no-activity",
                testFolder.id,
                any(),
                any()
            )
        } coAnswers { fetch1Deferred.await() }

        repository.messageDataState.test {
            assertEquals(MessageDataState.Initial, awaitItem())
            repository.setTargetFolder(testAccount, testFolder)
            assertEquals(MessageDataState.Loading, awaitItem())

            repository.setTargetFolder(testAccount, testFolder2)
            assertEquals(MessageDataState.Loading, awaitItem())

            advanceUntilIdle()

            assertEquals(MessageDataState.Success(listOf(testMessage2)), awaitItem())

            fetch1Deferred.complete(Result.success(testMessages))
            advanceUntilIdle()

            expectNoEvents()
            cancel()
        }
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                "token-no-activity",
                testFolder.id,
                messageListFields,
                messageListPageSize
            )
        }
        coVerify(exactly = 1) {
            graphApiHelper.getMessagesForFolder(
                "token-no-activity",
                testFolder2.id,
                messageListFields,
                messageListPageSize
            )
        }
    }
}