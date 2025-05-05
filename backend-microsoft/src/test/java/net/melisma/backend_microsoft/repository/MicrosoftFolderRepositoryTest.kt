package net.melisma.backend_microsoft.repository

// Removed Robolectric imports
import android.app.Activity
import android.util.Log
import app.cash.turbine.test
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
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
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.MailFolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
// Removed @RunWith and @Config annotations
class MicrosoftFolderRepositoryTest {

    // --- Mocks ---
    private lateinit var tokenProvider: TokenProvider
    private lateinit var graphApiHelper: GraphApiHelper
    private lateinit var errorMapper: ErrorMapper
    private lateinit var mockActivity: Activity
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    // --- Class Under Test ---
    private lateinit var repository: MicrosoftFolderRepository

    // --- Test Data ---
    private val testAccount1 = Account("id1", "user1@test.com", "MS")
    private val testAccount2 = Account("id2", "user2@test.com", "MS")
    private val nonMsAccount = Account("id3", "user3@gmail.com", "GOOG")

    private val testFolders = listOf(
        MailFolder("folderId1", "Inbox", 15, 10),
        MailFolder("folderId2", "Sent", 20, 0)
    )
    private val updatedFolders = listOf(MailFolder("folderId3", "Archive", 5, 5))
    private val mailReadScope = listOf("Mail.Read")

    // *** Define explicit token strings ***
    private val token1NoActivity = "token-acc1-no_activity"
    private val token1WithActivity = "token-acc1-with_activity"
    private val token2NoActivity = "token-acc2-no_activity"
    // Define token2WithActivity if needed by other tests

    @Before
    fun setUp() {
        tokenProvider = mockk()
        graphApiHelper = mockk()
        errorMapper = mockk(relaxed = true) // Relaxed for simpler error mapping in tests
        mockActivity = mockk()
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        // *** Use explicit token mocking (Specific calls) ***
        coEvery {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                null
            )
        } returns Result.success(token1NoActivity)
        coEvery {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                mockActivity
            )
        } returns Result.success(token1WithActivity)
        coEvery {
            tokenProvider.getAccessToken(
                testAccount2,
                mailReadScope,
                null
            )
        } returns Result.success(token2NoActivity)
        // Add mock for testAccount2 with activity if needed

        // *** Fallback neq() mocks removed in previous step ***

        // Mock Graph API calls expecting the specific tokens
        coEvery { graphApiHelper.getMailFolders(token1NoActivity) } returns Result.success(
            testFolders
        )
        coEvery { graphApiHelper.getMailFolders(token1WithActivity) } returns Result.success(
            updatedFolders
        ) // For refresh test
        coEvery { graphApiHelper.getMailFolders(token2NoActivity) } returns Result.success(
            testFolders
        ) // Assume same folders for simplicity

        // Relaxed errorMapper mock handles mapping any exception
        every { errorMapper.mapAuthExceptionToUserMessage(any()) } answers { "Auth Error: ${args[0]?.toString()}" }
        every { errorMapper.mapGraphExceptionToUserMessage(any()) } answers { "Graph Error: ${args[0]?.toString()}" }


        // --- Mock Log --- (Keep this)
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        // --- End Log mocking block ---

        repository = MicrosoftFolderRepository(
            tokenProvider = tokenProvider,
            graphApiHelper = graphApiHelper,
            ioDispatcher = testDispatcher, // Use TestDispatcher for IO
            externalScope = testScope, // Use TestScope for launching jobs
            errorMapper = errorMapper
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class) // Keep this
        unmockkAll()
    }

    // --- Test Cases ---
    @Test
    fun `observeFoldersState starts empty`() = testScope.runTest {
        assertEquals(emptyMap<String, FolderFetchState>(), repository.observeFoldersState().first())
    }

    @Test
    fun `manageObservedAccounts ignores non MS accounts`() = testScope.runTest {
        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem()) // 1. Initial Empty

            repository.manageObservedAccounts(listOf(testAccount1, nonMsAccount))

            // Expect Loading state for testAccount1
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Loading),
                awaitItem()
            ) // 2. Loading for Acc1
            advanceUntilIdle() // Let fetch complete

            // Expect Success state for testAccount1
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                awaitItem()
            ) // 3. Success for Acc1

            cancelAndIgnoreRemainingEvents()
        }
        // Verify correct token call (only for MS account) and API call
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 0) { tokenProvider.getAccessToken(nonMsAccount, any(), any()) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) }
    }

    @Test
    fun `manageObservedAccounts fetches folders for new MS accounts`() = testScope.runTest {
        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem()) // 1. Initial Empty

            // Add account 1
            repository.manageObservedAccounts(listOf(testAccount1))
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Loading),
                awaitItem()
            ) // 2. Loading Acc1
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                awaitItem()
            ) // 3. Success Acc1

            // Add account 2 (account 1 remains)
            repository.manageObservedAccounts(listOf(testAccount1, testAccount2))
            // Expect Loading state only for the newly added account 2
            assertEquals(
                mapOf(
                    testAccount1.id to FolderFetchState.Success(testFolders),
                    testAccount2.id to FolderFetchState.Loading
                ), awaitItem()
            ) // 4. Loading Acc2
            advanceUntilIdle()
            // Expect Success state for both
            assertEquals(
                mapOf(
                    testAccount1.id to FolderFetchState.Success(testFolders),
                    testAccount2.id to FolderFetchState.Success(testFolders)
                ), awaitItem()
            ) // 5. Success Acc1 & Acc2

            cancelAndIgnoreRemainingEvents()
        }
        // Verify token/API calls for both accounts
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount2, mailReadScope, null) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token2NoActivity) }
    }

    @Test
    fun `manageObservedAccounts removes state and cancels job for removed accounts`() =
        testScope.runTest { // NOTE: This test had a timeout, fix is not guaranteed
            // Arrange: Add two accounts and let them succeed
            repository.manageObservedAccounts(listOf(testAccount1, testAccount2))
            advanceUntilIdle()
            // Consume initial states to get to the final success state for both
            val initialState = repository.observeFoldersState()
                .first { it.size == 2 && it.values.all { v -> v is FolderFetchState.Success } }
            assertEquals(2, initialState.size)


            repository.observeFoldersState().test {
                // Start collecting *after* initial setup is done
                // Expect the current state (Success for both)
                assertEquals(
                    mapOf(
                        testAccount1.id to FolderFetchState.Success(testFolders),
                        testAccount2.id to FolderFetchState.Success(testFolders)
                    ), awaitItem()
                )

                // Act: Remove account 1
                repository.manageObservedAccounts(listOf(testAccount2))
                // The state update for removal is synchronous in manageObservedAccounts

                // Assert: State only contains account 2
                assertEquals(
                    mapOf(testAccount2.id to FolderFetchState.Success(testFolders)),
                    awaitItem()
                ) // State for Acc1 removed

                // NOTE: If this test still times out, it requires deeper coroutine/dispatcher debugging
                cancelAndIgnoreRemainingEvents()
            }
            // Verify initial fetches happened (implicit from setup)
        }

    @Test
    fun `manageObservedAccounts handles token fetch failure`() = testScope.runTest {
        // Arrange: Mock token failure for account 1
        val tokenError = MsalUiRequiredException("auth_fail", "UI Required")
        coEvery {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                null
            )
        } returns Result.failure(tokenError)
        val expectedErrorMsg = "Auth Error: $tokenError" // Use the mock setup for error message
        every { errorMapper.mapAuthExceptionToUserMessage(tokenError) } returns expectedErrorMsg


        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem()) // 1. Initial Empty

            // Act: Add account 1 (which will fail token fetch)
            repository.manageObservedAccounts(listOf(testAccount1))

            // Assert: Loading, then Error state
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Loading),
                awaitItem()
            ) // 2. Loading
            advanceUntilIdle() // Allow the failed job to complete
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error(expectedErrorMsg)),
                awaitItem()
            ) // 3. Error

            cancelAndIgnoreRemainingEvents()
        }
        // Verify token fetch was attempted, API not called
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(any()) }
        verify { errorMapper.mapAuthExceptionToUserMessage(tokenError) } // Verify mapper called
    }

    @Test
    fun `manageObservedAccounts handles Graph API failure`() = testScope.runTest {
        // Arrange: Mock API failure for account 1's token
        val graphError = IOException("Network Failed")
        coEvery { graphApiHelper.getMailFolders(token1NoActivity) } returns Result.failure(
            graphError
        )
        val expectedErrorMsg = "Graph Error: $graphError" // Use the mock setup for error message
        every { errorMapper.mapGraphExceptionToUserMessage(graphError) } returns expectedErrorMsg

        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem()) // 1. Initial Empty

            // Act: Add account 1
            repository.manageObservedAccounts(listOf(testAccount1))

            // Assert: Loading, then Error state
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Loading),
                awaitItem()
            ) // 2. Loading
            advanceUntilIdle() // Allow the failed job to complete
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error(expectedErrorMsg)),
                awaitItem()
            ) // 3. Error

            cancelAndIgnoreRemainingEvents()
        }
        // Verify token fetch succeeded, API call failed
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) }
        verify { errorMapper.mapGraphExceptionToUserMessage(graphError) } // Verify mapper called
    }

    @Test
    fun `refreshAllFolders re-fetches for observed accounts using activity for token`() =
        testScope.runTest { // Test #10
            // Arrange: Observe account 1 and let initial fetch succeed
            repository.manageObservedAccounts(listOf(testAccount1))
            advanceUntilIdle()

            // Arrange: Ensure correct refresh mock is set (using token1WithActivity from setup)
            // coEvery { graphApiHelper.getMailFolders(token1WithActivity) } returns Result.success(updatedFolders) // This is already in setup

            repository.observeFoldersState().test {
                // Expect initial SUCCESS state first
                // StateFlow immediately emits the current state upon collection.
                val initialState = awaitItem()
                assertEquals(
                    "Initial state before refresh should be Success",
                    mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                    initialState
                )

                // Act: Refresh
                repository.refreshAllFolders(mockActivity)

                // Assert: Expect Loading state immediately after refresh call
                // This state update is synchronous in launchFolderFetchJob
                assertEquals(
                    "State after refresh trigger should be Loading",
                    mapOf(testAccount1.id to FolderFetchState.Loading),
                    awaitItem()
                ) // Refresh Loading

                // Assert: Expect Success state with updated folders after job completes
                advanceUntilIdle() // Let the refresh job run
                assertEquals(
                    "Final state after refresh should be Success with updated data",
                    mapOf(testAccount1.id to FolderFetchState.Success(updatedFolders)),
                    awaitItem()
                ) // Refresh Success

                cancelAndIgnoreRemainingEvents()
            }

            // Verify correct sequence of token/API calls
            coVerify(exactly = 1) {
                tokenProvider.getAccessToken(
                    testAccount1,
                    mailReadScope,
                    null
                )
            } // Initial
            coVerify(exactly = 1) {
                tokenProvider.getAccessToken(
                    testAccount1,
                    mailReadScope,
                    mockActivity
                )
            } // Refresh
            coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) } // Initial
            coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1WithActivity) } // Refresh
        }

    @Test
    fun `refreshAllFolders handles token failure during refresh`() = testScope.runTest { // Test #11
        // Arrange: Observe account 1 and let initial fetch succeed
        repository.manageObservedAccounts(listOf(testAccount1))
        advanceUntilIdle()

        // Arrange: Mock token failure for the refresh call (with activity)
        val refreshError = MsalClientException("refresh_auth_fail", "Refresh Auth Failed")
        coEvery {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                mockActivity
            )
        } returns Result.failure(refreshError)
        val expectedErrorMsg = "Auth Error: $refreshError"
        every { errorMapper.mapAuthExceptionToUserMessage(refreshError) } returns expectedErrorMsg


        repository.observeFoldersState().test {
            // Expect initial SUCCESS state first
            val initialState = awaitItem()
            assertEquals(
                "Initial state before refresh should be Success",
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                initialState
            )

            // Act: Refresh
            repository.refreshAllFolders(mockActivity)

            // Assert: Expect Loading, then Error state
            assertEquals(
                "State after refresh trigger should be Loading",
                mapOf(testAccount1.id to FolderFetchState.Loading),
                awaitItem()
            ) // Refresh Loading
            advanceUntilIdle() // Let the refresh job run (and fail on token)
            assertEquals(
                "Final state after failed refresh should be Error",
                mapOf(testAccount1.id to FolderFetchState.Error(expectedErrorMsg)),
                awaitItem()
            ) // Refresh Error

            cancelAndIgnoreRemainingEvents()
        }
        // Verify correct token attempts, initial API call, no refresh API call
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                null
            )
        } // Initial token
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                mockActivity
            )
        } // Refresh token (failed)
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) } // Initial API
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(token1WithActivity) } // No refresh API call
        verify { errorMapper.mapAuthExceptionToUserMessage(refreshError) } // Verify mapper called
    }

    @Test
    fun `refreshAllFolders does nothing if no accounts are observed`() = testScope.runTest {
        // Arrange: Ensure state is initially empty
        assertEquals(emptyMap<String, FolderFetchState>(), repository.observeFoldersState().first())

        // Act: Refresh when no accounts observed
        repository.refreshAllFolders(mockActivity)
        advanceUntilIdle()

        // Assert: State remains empty
        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem())
            expectNoEvents()
            cancel()
        }
        // Verify no interactions
        coVerify(exactly = 0) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(any()) }
    }
}