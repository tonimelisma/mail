package net.melisma.backend_microsoft.repository

import android.app.Activity
import app.cash.turbine.test
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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
        MailFolder(
            id = "folderId1",
            displayName = "Inbox",
            totalItemCount = 15,
            unreadItemCount = 10
        ),
        MailFolder(id = "folderId2", displayName = "Sent", totalItemCount = 20, unreadItemCount = 0)
    )
    private val updatedFolders = listOf(
        MailFolder(
            id = "folderId3",
            displayName = "Archive",
            totalItemCount = 5,
            unreadItemCount = 5
        )
    )
    private val mailReadScope = listOf("Mail.Read")

    @Before
    fun setUp() {
        tokenProvider = mockk()
        graphApiHelper = mockk()
        errorMapper = mockk(relaxed = true)
        mockActivity = mockk()
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        coEvery { tokenProvider.getAccessToken(any(), mailReadScope, null) } returns Result.success(
            "fake-token-no-activity"
        )
        coEvery {
            tokenProvider.getAccessToken(
                any(),
                mailReadScope,
                mockActivity
            )
        } returns Result.success("fake-token-with-activity")
        coEvery { graphApiHelper.getMailFolders(any()) } returns Result.success(testFolders)
        every { errorMapper.mapAuthExceptionToUserMessage(any()) } returns "Auth Error"
        every { errorMapper.mapGraphExceptionToUserMessage(any()) } returns "Graph API Error"

        repository = MicrosoftFolderRepository(
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
    fun `observeFoldersState starts empty`() = testScope.runTest {
        // Check initial state using first()
        assertEquals(emptyMap<String, FolderFetchState>(), repository.observeFoldersState().first())
    }

    @Test
    fun `manageObservedAccounts ignores non MS accounts`() = testScope.runTest {
        repository.manageObservedAccounts(listOf(testAccount1, nonMsAccount))
        advanceUntilIdle()

        // Use Turbine to check the sequence of emissions
        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem()) // Initial emission
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Loading),
                awaitItem()
            ) // Loading state
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                awaitItem()
            ) // Success state
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 0) { tokenProvider.getAccessToken(nonMsAccount, any(), any()) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders("fake-token-no-activity") }
    }

    @Test
    fun `manageObservedAccounts fetches folders for new MS accounts`() = testScope.runTest {
        repository.manageObservedAccounts(listOf(testAccount1))
        advanceUntilIdle()

        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem())
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                awaitItem()
            )

            // Add second account
            repository.manageObservedAccounts(listOf(testAccount1, testAccount2))
            // Don't advance here, let Turbine catch intermediate states

            assertEquals(
                mapOf(
                    testAccount1.id to FolderFetchState.Success(testFolders),
                    testAccount2.id to FolderFetchState.Loading // Account2 starts loading
                ), awaitItem()
            )

            advanceUntilIdle() // Now let account2 fetch complete

            assertEquals(
                mapOf(
                    testAccount1.id to FolderFetchState.Success(testFolders),
                    testAccount2.id to FolderFetchState.Success(testFolders) // Account2 success
                ), awaitItem()
            )

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount2, mailReadScope, null) }
        coVerify(exactly = 2) { graphApiHelper.getMailFolders("fake-token-no-activity") }
    }

    @Test
    fun `manageObservedAccounts removes state and cancels job for removed accounts`() =
        testScope.runTest {
            repository.manageObservedAccounts(listOf(testAccount1, testAccount2))
            advanceUntilIdle()
            // Ensure initial state is correct before proceeding
            val initialState = repository.observeFoldersState()
                .first { it.size == 2 && it.values.all { state -> state is FolderFetchState.Success } }
            assertEquals(2, initialState.size)

            repository.observeFoldersState().test {
                // Expect the initial state (with both accounts) if collected immediately after repo init + initial fetch
                assertEquals(initialState, awaitItem())

                // Act: Remove account1
                repository.manageObservedAccounts(listOf(testAccount2))
                // Don't advance here, let turbine see the immediate update

                // Assert: State for account1 is removed in the next emission
                assertEquals(
                    mapOf(
                        testAccount2.id to FolderFetchState.Success(testFolders)
                    ), awaitItem()
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `manageObservedAccounts handles token fetch failure`() = testScope.runTest {
        val tokenError = MsalUiRequiredException("auth_fail", "UI Required")
        coEvery {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                null
            )
        } returns Result.failure(tokenError)
        every { errorMapper.mapAuthExceptionToUserMessage(tokenError) } returns "Mapped Auth Error"

        repository.manageObservedAccounts(listOf(testAccount1))
        // Don't advance yet

        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem()) // Initial
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem()) // Loading
            // Now advance to let the error occur
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error("Mapped Auth Error")),
                awaitItem()
            ) // Error
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(any()) }
        verify { errorMapper.mapAuthExceptionToUserMessage(tokenError) }
    }

    @Test
    fun `manageObservedAccounts handles Graph API failure`() = testScope.runTest {
        val graphError = IOException("Network Failed")
        coEvery { graphApiHelper.getMailFolders("fake-token-no-activity") } returns Result.failure(
            graphError
        )
        every { errorMapper.mapGraphExceptionToUserMessage(graphError) } returns "Mapped Graph Error"

        repository.manageObservedAccounts(listOf(testAccount1))
        // Don't advance yet

        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem())
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())
            // Now advance to let the error occur
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error("Mapped Graph Error")),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders("fake-token-no-activity") }
        verify { errorMapper.mapGraphExceptionToUserMessage(graphError) }
    }

    @Test
    fun `refreshAllFolders re-fetches for observed accounts using activity for token`() =
        testScope.runTest {
            repository.manageObservedAccounts(listOf(testAccount1))
            advanceUntilIdle()
            // Consume initial emissions up to success
            val initialState = repository.observeFoldersState()
                .first { it.values.firstOrNull() is FolderFetchState.Success }
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                initialState
            )

            coEvery { graphApiHelper.getMailFolders("fake-token-with-activity") } returns Result.success(
                updatedFolders
            )

            repository.observeFoldersState().test {
                // Start collecting *after* initial fetch is done
                assertEquals(initialState, awaitItem()) // Should emit current success state first

                // Act: Trigger refresh
                repository.refreshAllFolders(mockActivity)
                // Assert: Should go to loading
                assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())

                // Let refresh complete
                advanceUntilIdle()
                // Assert: New success state
                assertEquals(
                    mapOf(testAccount1.id to FolderFetchState.Success(updatedFolders)),
                    awaitItem()
                )

                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) {
                tokenProvider.getAccessToken(
                    testAccount1,
                    mailReadScope,
                    null
                )
            }
            coVerify(exactly = 1) {
                tokenProvider.getAccessToken(
                    testAccount1,
                    mailReadScope,
                    mockActivity
                )
            }
            coVerify(exactly = 1) { graphApiHelper.getMailFolders("fake-token-no-activity") }
            coVerify(exactly = 1) { graphApiHelper.getMailFolders("fake-token-with-activity") }
        }

    @Test
    fun `refreshAllFolders handles token failure during refresh`() = testScope.runTest {
        repository.manageObservedAccounts(listOf(testAccount1))
        advanceUntilIdle()
        val initialState = repository.observeFoldersState()
            .first { it.values.firstOrNull() is FolderFetchState.Success }
        assertEquals(mapOf(testAccount1.id to FolderFetchState.Success(testFolders)), initialState)

        val refreshError = MsalClientException("refresh_auth_fail", "Refresh Auth Failed")
        coEvery {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                mockActivity
            )
        } returns Result.failure(refreshError)
        every { errorMapper.mapAuthExceptionToUserMessage(refreshError) } returns "Refresh Auth Error"

        repository.observeFoldersState().test {
            assertEquals(initialState, awaitItem()) // Emit current success

            // Act: Trigger refresh
            repository.refreshAllFolders(mockActivity)
            // Assert: Goes to loading
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())

            // Let refresh error occur
            advanceUntilIdle()
            // Assert: Goes to error
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error("Refresh Auth Error")),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 1) {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                mockActivity
            )
        }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders("fake-token-no-activity") }
        coVerify(exactly = 0) { graphApiHelper.getMailFolders("fake-token-with-activity") }
        verify { errorMapper.mapAuthExceptionToUserMessage(refreshError) }
    }

    @Test
    fun `refreshAllFolders does nothing if no accounts are observed`() = testScope.runTest {
        // Check initial state *after* @Before setup
        assertEquals(emptyMap<String, FolderFetchState>(), repository.observeFoldersState().first())

        repository.refreshAllFolders(mockActivity)
        advanceUntilIdle()

        // Check state again *after* action, it should still be the initial empty state
        assertEquals(emptyMap<String, FolderFetchState>(), repository.observeFoldersState().first())
        coVerify(exactly = 0) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(any()) }
    }
}