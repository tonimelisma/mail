package net.melisma.backend_microsoft.repository

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
// import net.melisma.backend_microsoft.errors.ErrorMapper // OLD IMPORT
import net.melisma.core_common.errors.ErrorMapperService // NEW INTERFACE IMPORT
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
    private lateinit var mockErrorMapper: ErrorMapperService // Mock the INTERFACE
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

    private val token1NoActivity = "token-acc1-no_activity"
    private val token1WithActivity = "token-acc1-with_activity"
    private val token2NoActivity = "token-acc2-no_activity"


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

        // Mock Graph API calls
        coEvery { graphApiHelper.getMailFolders(token1NoActivity) } returns Result.success(
            testFolders
        )
        coEvery { graphApiHelper.getMailFolders(token1WithActivity) } returns Result.success(
            updatedFolders
        )
        coEvery { graphApiHelper.getMailFolders(token2NoActivity) } returns Result.success(
            testFolders
        )

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
        repository = MicrosoftFolderRepository(
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
    fun `observeFoldersState starts empty`() = testScope.runTest {
        assertEquals(emptyMap<String, FolderFetchState>(), repository.observeFoldersState().first())
    }

    @Test
    fun `manageObservedAccounts ignores non MS accounts`() = testScope.runTest {
        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem())
            repository.manageObservedAccounts(listOf(testAccount1, nonMsAccount))
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 0) { tokenProvider.getAccessToken(nonMsAccount, any(), any()) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) }
    }

    @Test
    fun `manageObservedAccounts fetches folders for new MS accounts`() = testScope.runTest {
        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem()) // Initial

            repository.manageObservedAccounts(listOf(testAccount1)) // Add 1
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                awaitItem()
            )

            repository.manageObservedAccounts(listOf(testAccount1, testAccount2)) // Add 2
            assertEquals(
                mapOf(
                    testAccount1.id to FolderFetchState.Success(testFolders),
                    testAccount2.id to FolderFetchState.Loading
                ), awaitItem()
            )
            advanceUntilIdle()
            assertEquals(
                mapOf(
                    testAccount1.id to FolderFetchState.Success(testFolders),
                    testAccount2.id to FolderFetchState.Success(testFolders)
                ), awaitItem()
            )

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount2, mailReadScope, null) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token2NoActivity) }
    }

    @Test
    fun `manageObservedAccounts removes state and cancels job for removed accounts`() =
        testScope.runTest {
            repository.manageObservedAccounts(listOf(testAccount1, testAccount2))
            advanceUntilIdle()
            // Consume initial load states
            val initialState = repository.observeFoldersState()
                .first { it.size == 2 && it.values.all { v -> v is FolderFetchState.Success } }
            assertEquals(2, initialState.size)

            repository.observeFoldersState().test {
                assertEquals(
                    mapOf(
                        testAccount1.id to FolderFetchState.Success(testFolders),
                        testAccount2.id to FolderFetchState.Success(testFolders)
                    ), awaitItem()
                ) // Start state

                repository.manageObservedAccounts(listOf(testAccount2)) // Remove 1

                assertEquals(
                    mapOf(testAccount2.id to FolderFetchState.Success(testFolders)),
                    awaitItem()
                ) // State for Acc1 removed
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
        // Mock the specific error mapping call
        every { mockErrorMapper.mapAuthExceptionToUserMessage(tokenError) } returns "Mapped Auth Error: UI Required"

        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem())
            repository.manageObservedAccounts(listOf(testAccount1))
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error("Mapped Auth Error: UI Required")),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(any()) }
        verify { mockErrorMapper.mapAuthExceptionToUserMessage(tokenError) } // Verify mapper call
    }

    @Test
    fun `manageObservedAccounts handles Graph API failure`() = testScope.runTest {
        val graphError = IOException("Network Failed")
        coEvery { graphApiHelper.getMailFolders(token1NoActivity) } returns Result.failure(
            graphError
        )
        // Mock the specific error mapping call
        every { mockErrorMapper.mapNetworkOrApiException(graphError) } returns "Mapped Graph Error: Network Failed"

        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem())
            repository.manageObservedAccounts(listOf(testAccount1))
            assertEquals(mapOf(testAccount1.id to FolderFetchState.Loading), awaitItem())
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error("Mapped Graph Error: Network Failed")),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { tokenProvider.getAccessToken(testAccount1, mailReadScope, null) }
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) }
        verify { mockErrorMapper.mapNetworkOrApiException(graphError) } // Verify mapper call
    }

    @Test
    fun `refreshAllFolders re-fetches for observed accounts using activity for token`() =
        testScope.runTest {
            repository.manageObservedAccounts(listOf(testAccount1))
            advanceUntilIdle()
            // Consume initial load
            repository.observeFoldersState()
                .first { it.isNotEmpty() && it.values.first() is FolderFetchState.Success }

            repository.observeFoldersState().test {
                assertEquals(
                    mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                    awaitItem()
                ) // Initial success

                repository.refreshAllFolders(mockActivity)

                assertEquals(
                    mapOf(testAccount1.id to FolderFetchState.Loading),
                    awaitItem()
                ) // Refresh Loading
                advanceUntilIdle()
                assertEquals(
                    mapOf(testAccount1.id to FolderFetchState.Success(updatedFolders)),
                    awaitItem()
                ) // Refresh Success

                cancelAndIgnoreRemainingEvents()
            }
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
    fun `refreshAllFolders handles token failure during refresh`() = testScope.runTest {
        repository.manageObservedAccounts(listOf(testAccount1))
        advanceUntilIdle()
        // Consume initial load
        repository.observeFoldersState()
            .first { it.isNotEmpty() && it.values.first() is FolderFetchState.Success }

        val refreshError = MsalClientException("refresh_auth_fail", "Refresh Auth Failed")
        coEvery {
            tokenProvider.getAccessToken(
                testAccount1,
                mailReadScope,
                mockActivity
            )
        } returns Result.failure(refreshError)
        // Mock the specific error mapping call
        every { mockErrorMapper.mapAuthExceptionToUserMessage(refreshError) } returns "Mapped Refresh Auth Error"

        repository.observeFoldersState().test {
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Success(testFolders)),
                awaitItem()
            ) // Initial success

            repository.refreshAllFolders(mockActivity)

            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Loading),
                awaitItem()
            ) // Refresh Loading
            advanceUntilIdle()
            assertEquals(
                mapOf(testAccount1.id to FolderFetchState.Error("Mapped Refresh Auth Error")),
                awaitItem()
            ) // Refresh Error

            cancelAndIgnoreRemainingEvents()
        }
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
        } // Refresh (failed)
        coVerify(exactly = 1) { graphApiHelper.getMailFolders(token1NoActivity) } // Initial
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(token1WithActivity) } // No refresh API call
        verify { mockErrorMapper.mapAuthExceptionToUserMessage(refreshError) } // Verify mapper call
    }

    @Test
    fun `refreshAllFolders does nothing if no accounts are observed`() = testScope.runTest {
        assertEquals(emptyMap<String, FolderFetchState>(), repository.observeFoldersState().first())
        repository.refreshAllFolders(mockActivity)
        advanceUntilIdle()
        repository.observeFoldersState().test {
            assertEquals(emptyMap<String, FolderFetchState>(), awaitItem())
            expectNoEvents()
            cancel()
        }
        coVerify(exactly = 0) { tokenProvider.getAccessToken(any(), any(), any()) }
        coVerify(exactly = 0) { graphApiHelper.getMailFolders(any()) }
    }
}
