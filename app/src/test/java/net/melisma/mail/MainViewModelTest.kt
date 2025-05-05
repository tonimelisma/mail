package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter


// Rule to set the Main dispatcher to a TestDispatcher
@OptIn(ExperimentalCoroutinesApi::class)
class MainCoroutineRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description?) {
        super.starting(description)
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description?) {
        super.finished(description)
        Dispatchers.resetMain()
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    // --- Mocks ---
    private lateinit var mockContext: Context
    private lateinit var mockConnectivityManager: ConnectivityManager
    private lateinit var mockNetworkCapabilities: NetworkCapabilities
    private lateinit var mockActivity: Activity
    private lateinit var accountRepository: AccountRepository
    private lateinit var folderRepository: FolderRepository
    private lateinit var messageRepository: MessageRepository

    // --- Flows for Mocks ---
    private lateinit var authStateFlow: MutableStateFlow<AuthState>
    private lateinit var accountsFlow: MutableStateFlow<List<Account>>
    private lateinit var isLoadingAccountActionFlow: MutableStateFlow<Boolean>
    private lateinit var accountActionMessageFlow: MutableSharedFlow<String?> // Use SharedFlow for transient messages
    private lateinit var folderStatesFlow: MutableStateFlow<Map<String, FolderFetchState>>
    private lateinit var messageDataStateFlow: MutableStateFlow<MessageDataState>

    // --- Class Under Test ---
    private lateinit var viewModel: MainViewModel

    // --- Test Data ---
    private val testAccount1 = Account(id = "id1", username = "user1@test.com", providerType = "MS")
    private val testInbox =
        MailFolder(id = "inboxId1", displayName = "Inbox", totalItemCount = 10, unreadItemCount = 2)
    private val testSent =
        MailFolder(id = "sentId1", displayName = "Sent", totalItemCount = 5, unreadItemCount = 0)
    private val testFoldersAcc1 = listOf(testInbox, testSent)
    private val testDateTimeString: String =
        OffsetDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    private val testMessage1 = Message(
        id = "msg1",
        receivedDateTime = testDateTimeString,
        subject = "Test Subject 1",
        senderName = "Sender Name",
        senderAddress = "sender@example.com",
        bodyPreview = "This is a snippet...",
        isRead = false
    )
    private val testMessages = listOf(testMessage1)


    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockConnectivityManager = mockk(relaxed = true)
        mockNetworkCapabilities = mockk(relaxed = true)
        mockActivity = mockk(relaxed = true)
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager
        every { mockConnectivityManager.activeNetwork } returns mockk()
        every { mockConnectivityManager.getNetworkCapabilities(any()) } returns mockNetworkCapabilities
        every { mockNetworkCapabilities.hasTransport(any()) } returns true

        accountRepository = mockk(relaxUnitFun = true)
        folderRepository = mockk(relaxUnitFun = true)
        messageRepository = mockk(relaxUnitFun = true)

        authStateFlow = MutableStateFlow(AuthState.Initializing)
        accountsFlow = MutableStateFlow(emptyList())
        isLoadingAccountActionFlow = MutableStateFlow(false)
        accountActionMessageFlow = MutableSharedFlow(replay = 0, extraBufferCapacity = 1)
        folderStatesFlow = MutableStateFlow(emptyMap())
        messageDataStateFlow = MutableStateFlow(MessageDataState.Initial)

        every { accountRepository.authState } returns authStateFlow.asStateFlow()
        every { accountRepository.accounts } returns accountsFlow.asStateFlow()
        every { accountRepository.isLoadingAccountAction } returns isLoadingAccountActionFlow.asStateFlow()
        every { accountRepository.accountActionMessage } returns accountActionMessageFlow.asSharedFlow()
        every { folderRepository.observeFoldersState() } returns folderStatesFlow.asStateFlow()
        every { messageRepository.messageDataState } returns messageDataStateFlow.asStateFlow()

        coEvery { accountRepository.addAccount(any(), any()) } just runs
        coEvery { accountRepository.removeAccount(any()) } just runs
        coEvery { folderRepository.refreshAllFolders(any()) } just runs
        coEvery { folderRepository.manageObservedAccounts(any()) } just runs
        coEvery { messageRepository.refreshMessages(any()) } just runs
        coEvery { messageRepository.setTargetFolder(any(), any()) } just runs

        viewModel = MainViewModel(
            applicationContext = mockContext,
            accountRepository = accountRepository,
            folderRepository = folderRepository,
            messageRepository = messageRepository
        )

        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        // No cleanup needed
    }

    @Test
    fun `initial state is correct`() = runTest {
        viewModel.uiState.test {
            val initialState = awaitItem()
            assertEquals(AuthState.Initializing, initialState.authState)
            assertTrue(initialState.accounts.isEmpty())
            assertFalse(initialState.isLoadingAccountAction)
            assertTrue(initialState.foldersByAccountId.isEmpty())
            assertNull(initialState.selectedFolder)
            assertEquals(MessageDataState.Initial, initialState.messageDataState)
            assertNull(initialState.toastMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state reflects authentication success and account arrival`() = runTest {
        viewModel.uiState.test {
            assertEquals(AuthState.Initializing, awaitItem().authState) // Initial state

            // --- Arrange ---
            authStateFlow.value = AuthState.Initialized
            accountsFlow.value = listOf(testAccount1)
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Let observers react

            // --- Assert ---
            val stateAfterAuth = expectMostRecentItem() // Get the latest state after updates
            assertEquals(AuthState.Initialized, stateAfterAuth.authState)
            assertEquals(listOf(testAccount1), stateAfterAuth.accounts)

            // Verify FolderRepository was informed about the new accounts
            coVerify { folderRepository.manageObservedAccounts(listOf(testAccount1)) }

            cancelAndIgnoreRemainingEvents()
        }
    }


    // CORRECTED VERSION OF THIS TEST - Only one definition should exist
    @Test
    fun `state reflects folder loading and success with default selection`() = runTest {
        // Pre-condition: Authenticated with an account
        authStateFlow.value = AuthState.Initialized
        accountsFlow.value = listOf(testAccount1)
        coEvery { folderRepository.manageObservedAccounts(any()) } just runs // Make sure this is stubbed
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // Consume initial state

            // --- Arrange ---
            // Simulate folder loading
            folderStatesFlow.value = mapOf(testAccount1.id to FolderFetchState.Loading)
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // --- Assert Loading ---
            val loadingState = awaitItem()
            assertEquals(FolderFetchState.Loading, loadingState.foldersByAccountId[testAccount1.id])
            assertTrue(loadingState.isAnyFolderLoading)
            assertNull("SelectedFolder should be null during Loading", loadingState.selectedFolder)

            // --- Arrange ---
            // Simulate folder success
            folderStatesFlow.value =
                mapOf(testAccount1.id to FolderFetchState.Success(testFoldersAcc1))
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Let selectDefaultFolderIfNeeded run

            // --- Assert Success ---
            // Consume the state emission from the folder map update
            awaitItem()
            // Consume the NEXT emission, which contains the default selection update
            val stateAfterDefaultSelection = awaitItem()

            // Assert on the state AFTER the default selection should have happened
            val folderState = stateAfterDefaultSelection.foldersByAccountId[testAccount1.id]
            assertTrue(folderState is FolderFetchState.Success)
            assertEquals(testFoldersAcc1, (folderState as FolderFetchState.Success).folders)
            assertFalse(
                "isAnyFolderLoading should be false after success",
                stateAfterDefaultSelection.isAnyFolderLoading
            )

            // Verify default folder selection (Inbox) - Assert on the correct state
            assertEquals(
                "Default folder 'Inbox' was not selected",
                testInbox,
                stateAfterDefaultSelection.selectedFolder
            )
            assertEquals(testAccount1.id, stateAfterDefaultSelection.selectedFolderAccountId)

            // Verify MessageRepository was notified of selection
            // Use atLeast = 1 as it might be called during init/account observation too if selection was null then
            coVerify(atLeast = 1) { messageRepository.setTargetFolder(testAccount1, testInbox) }

            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test
    fun `selectFolder updates state and notifies MessageRepository`() = runTest {
        // Pre-condition: Authenticated, folders loaded, Inbox selected by default
        authStateFlow.value = AuthState.Initialized
        accountsFlow.value = listOf(testAccount1)
        folderStatesFlow.value = mapOf(testAccount1.id to FolderFetchState.Success(testFoldersAcc1))
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Let init and default selection run

        viewModel.uiState.test {
            awaitItem() // Skip previous states (initial, auth, folder load, default select)

            // --- Act ---
            viewModel.selectFolder(testSent, testAccount1) // Select "Sent"
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // --- Assert ---
            val newState = awaitItem()
            assertEquals(testSent, newState.selectedFolder)
            assertEquals(testAccount1.id, newState.selectedFolderAccountId)

            // Verify MessageRepository was notified with the *new* folder ("Sent")
            coVerify { messageRepository.setTargetFolder(testAccount1, testSent) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state reflects message loading and success`() = runTest {
        // Pre-conditions: auth, account, folder selected (e.g., Inbox)
        authStateFlow.value = AuthState.Initialized
        accountsFlow.value = listOf(testAccount1)
        folderStatesFlow.value = mapOf(testAccount1.id to FolderFetchState.Success(testFoldersAcc1))
        // Ensure default selection or explicitly select
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Allow default selection
        // Verify default selection happened before proceeding
        if (viewModel.uiState.value.selectedFolder == null) {
            // If default didn't happen (e.g. timing), explicitly select
            viewModel.selectFolder(testInbox, testAccount1)
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Let selection propagate
        }
        assertEquals(
            testInbox,
            viewModel.uiState.value.selectedFolder
        ) // Confirm selection before testing messages


        viewModel.uiState.test {
            awaitItem() // Skip prior states

            // --- Arrange ---
            messageDataStateFlow.value = MessageDataState.Loading
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // --- Assert ---
            val loadingState = awaitItem()
            assertEquals(MessageDataState.Loading, loadingState.messageDataState)
            assertTrue(loadingState.isMessageLoading)

            // --- Arrange ---
            messageDataStateFlow.value = MessageDataState.Success(testMessages)
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // --- Assert ---
            val successState = awaitItem()
            assertEquals(MessageDataState.Success(testMessages), successState.messageDataState)
            assertFalse(successState.isMessageLoading)
            assertEquals(testMessages, successState.messages) // Check messages list helper

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshMessages calls repository when online`() = runTest {
        // Arrange: Online, folder selected
        every { mockNetworkCapabilities.hasTransport(any()) } returns true
        authStateFlow.value = AuthState.Initialized
        accountsFlow.value = listOf(testAccount1)
        folderStatesFlow.value = mapOf(testAccount1.id to FolderFetchState.Success(testFoldersAcc1))
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Ensure state is stable
        viewModel.selectFolder(testInbox, testAccount1) // Explicitly select folder
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Ensure selection processed

        // Act
        viewModel.refreshMessages(mockActivity) // Pass mock activity
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        coVerify { messageRepository.refreshMessages(mockActivity) }
    }

    @Test
    fun `refreshMessages does not call repository and shows toast when offline`() = runTest {
        // Arrange: Offline, folder selected
        every { mockNetworkCapabilities.hasTransport(any()) } returns false // Simulate offline
        authStateFlow.value = AuthState.Initialized
        accountsFlow.value = listOf(testAccount1)
        folderStatesFlow.value = mapOf(testAccount1.id to FolderFetchState.Success(testFoldersAcc1))
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        viewModel.selectFolder(testInbox, testAccount1)
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // Skip initial/setup states

            // Act
            viewModel.refreshMessages(mockActivity)
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // Assert: Check for toast message in UI state
            val offlineState = awaitItem()
            assertEquals("No internet connection.", offlineState.toastMessage)

            // Assert repository was NOT called
            coVerify(exactly = 0) { messageRepository.refreshMessages(any()) }

            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test
    fun `removing the only account clears selected folder and notifies MessageRepository`() =
        runTest {
            // Arrange: Initialized, one account, folder selected
            authStateFlow.value = AuthState.Initialized
            accountsFlow.value = listOf(testAccount1)
            folderStatesFlow.value =
                mapOf(testAccount1.id to FolderFetchState.Success(testFoldersAcc1))
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Allow default select
            // Verify default selection happened before proceeding
            if (viewModel.uiState.value.selectedFolder == null) {
                viewModel.selectFolder(testInbox, testAccount1)
                mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
            }
            assertEquals(testInbox, viewModel.uiState.value.selectedFolder)


            viewModel.uiState.test {
                val initialState = awaitItem() // Consume state after selection
                assertEquals(testInbox, initialState.selectedFolder)
                assertEquals(testAccount1.id, initialState.selectedFolderAccountId)

                // --- Act: Simulate account removal by emitting empty list ---
                accountsFlow.value = emptyList()
                mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Let ViewModel react

                // --- Assert ---
                val finalState = awaitItem() // Get state after account list changed
                assertEquals(
                    AuthState.Initialized,
                    finalState.authState
                ) // Auth state itself might not change
                assertTrue(finalState.accounts.isEmpty())
                assertNull(finalState.selectedFolder) // Selection should be cleared
                assertNull(finalState.selectedFolderAccountId)

                // Verify message repo told to clear target due to selection change
                coVerify(atLeast = 1) { messageRepository.setTargetFolder(null, null) }

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `addAccount calls repository`() = runTest {
        // Arrange
        authStateFlow.value = AuthState.Initialized // Ensure initialized
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Act
        viewModel.addAccount(mockActivity)
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        coVerify { accountRepository.addAccount(mockActivity, listOf("User.Read", "Mail.Read")) }
    }

    @Test
    fun `removeAccount calls repository`() = runTest {
        // Arrange
        authStateFlow.value = AuthState.Initialized // Ensure initialized
        accountsFlow.value = listOf(testAccount1) // Ensure account exists to be removed
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Act
        viewModel.removeAccount(mockActivity, testAccount1)
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        coVerify { accountRepository.removeAccount(testAccount1) }
    }

    @Test
    fun `toastMessageShown clears toast message in state and repository`() = runTest {
        // Arrange: Simulate a toast message appearing from the repository
        authStateFlow.value = AuthState.Initialized
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        val testToast = "Test Toast Message"
        accountActionMessageFlow.tryEmit(testToast) // Simulate repository emitting a toast
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            // Assert toast is initially set
            // Use expectMostRecentItem() as initial state might also be emitted
            val stateWithToast = expectMostRecentItem()
            assertEquals(testToast, stateWithToast.toastMessage)

            // Act: Simulate UI confirming toast shown
            viewModel.toastMessageShown()
            mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // Assert: Toast is cleared in UI state
            val stateAfterToastShown = awaitItem()
            assertNull(stateAfterToastShown.toastMessage)

            // Assert: Repository's clear function was called
            verify { accountRepository.clearAccountActionMessage() }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- ADD MORE TESTS HERE ---
    // TODO: Test error states from repositories (FolderFetchState.Error, MessageDataState.Error).
    // TODO: Test removing the *currently selected* account when multiple accounts exist.
    // TODO: Test default folder selection fallback logic (when Inbox doesn't exist).
    // TODO: Test edge cases in observation logic (e.g., rapid changes).

}