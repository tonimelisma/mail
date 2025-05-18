package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.FolderFetchState
import net.melisma.core_data.model.GenericAuthResult
import net.melisma.core_data.model.GenericSignOutResult
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MessageDataState
import net.melisma.core_data.model.OverallApplicationAuthState
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.preferences.MailViewModePreference
import net.melisma.core_data.preferences.UserPreferences
import net.melisma.core_data.preferences.UserPreferencesRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.data.repository.DefaultAccountRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class MainViewModelTest {

    // Rules for JUnit and Coroutines
    @get:Rule
    val instantTaskExecutorRule =
        InstantTaskExecutorRule() // For LiveData and other Arch components
    private val testDispatcher = StandardTestDispatcher()

    // Mocks
    private lateinit var mockApplicationContext: Context
    private lateinit var mockDefaultAccountRepository: DefaultAccountRepository
    private lateinit var mockFolderRepository: FolderRepository
    private lateinit var mockMessageRepository: MessageRepository
    private lateinit var mockThreadRepository: ThreadRepository
    private lateinit var mockUserPreferencesRepository: UserPreferencesRepository
    private lateinit var mockActivity: Activity


    // Flows for repositories
    private lateinit var overallApplicationAuthStateFlow: MutableStateFlow<OverallApplicationAuthState>
    private lateinit var accountsFlow: MutableStateFlow<List<Account>>
    private lateinit var accountActionMessagesFlow: MutableSharedFlow<String?>
    private lateinit var foldersStateFlow: MutableStateFlow<Map<String, FolderFetchState>>
    private lateinit var messageDataStateFlow: MutableStateFlow<MessageDataState>
    private lateinit var threadDataStateFlow: MutableStateFlow<ThreadDataState>
    private lateinit var userPreferencesFlow: MutableStateFlow<UserPreferences>


    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockApplicationContext = mock()
        mockDefaultAccountRepository = mock()
        mockFolderRepository = mock()
        mockMessageRepository = mock()
        mockThreadRepository = mock()
        mockUserPreferencesRepository = mock()
        mockActivity = mock() // Mock activity for sign-in

        // Initialize flows
        overallApplicationAuthStateFlow = MutableStateFlow(OverallApplicationAuthState.UNKNOWN)
        accountsFlow = MutableStateFlow(emptyList())
        accountActionMessagesFlow = MutableSharedFlow() // For toasts from account repo
        foldersStateFlow = MutableStateFlow(emptyMap())
        messageDataStateFlow = MutableStateFlow(MessageDataState.Initial)
        threadDataStateFlow = MutableStateFlow(ThreadDataState.Initial)
        userPreferencesFlow =
            MutableStateFlow(UserPreferences(mailViewMode = MailViewModePreference.THREADS))


        // Setup mock behavior for repositories
        whenever(mockDefaultAccountRepository.overallApplicationAuthState).thenReturn(
            overallApplicationAuthStateFlow
        )
        whenever(mockDefaultAccountRepository.getAccounts()).thenReturn(accountsFlow)
        whenever(mockDefaultAccountRepository.observeActionMessages()).thenReturn(
            accountActionMessagesFlow
        )

        whenever(mockFolderRepository.observeFoldersState()).thenReturn(foldersStateFlow)
        // Mock manageObservedAccounts to prevent errors if it's called
        whenever(mockFolderRepository.manageObservedAccounts(any())).then {}


        whenever(mockMessageRepository.messageDataState).thenReturn(messageDataStateFlow)
        whenever(mockThreadRepository.threadDataState).thenReturn(threadDataStateFlow)
        whenever(mockUserPreferencesRepository.userPreferencesFlow).thenReturn(userPreferencesFlow)

        // Mock connectivity manager
        val mockConnectivityManager = mock<android.net.ConnectivityManager>()
        val mockNetworkCapabilities = mock<android.net.NetworkCapabilities>()
        whenever(mockApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
            mockConnectivityManager
        )
        whenever(mockConnectivityManager.activeNetwork).thenReturn(mock()) // Assuming network is active
        whenever(mockConnectivityManager.getNetworkCapabilities(any())).thenReturn(
            mockNetworkCapabilities
        )
        whenever(mockNetworkCapabilities.hasTransport(any())).thenReturn(true) // Assuming internet connection


        viewModel = MainViewModel(
            applicationContext = mockApplicationContext,
            defaultAccountRepository = mockDefaultAccountRepository,
            folderRepository = mockFolderRepository,
            messageRepository = mockMessageRepository,
            threadRepository = mockThreadRepository,
            userPreferencesRepository = mockUserPreferencesRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(OverallApplicationAuthState.UNKNOWN, state.overallApplicationAuthState)
        assertTrue(state.accounts.isEmpty())
        assertFalse(state.isLoadingAccountAction)
        assertTrue(state.foldersByAccountId.isEmpty())
        assertNull(state.selectedFolderAccountId)
        assertNull(state.selectedFolder)
        assertEquals(MessageDataState.Initial, state.messageDataState)
        assertEquals(ThreadDataState.Initial, state.threadDataState)
        assertEquals(
            MailViewModePreference.THREADS,
            state.currentViewMode
        ) // From default user pref
        assertNull(state.toastMessage)
    }

    @Test
    fun `overallApplicationAuthState updates from repository`() = runTest {
        overallApplicationAuthStateFlow.value =
            OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED,
            viewModel.uiState.value.overallApplicationAuthState
        )

        overallApplicationAuthStateFlow.value = OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED,
            viewModel.uiState.value.overallApplicationAuthState
        )
    }

    @Test
    fun `accounts list updates from repository`() = runTest {
        val mockAccounts = listOf(Account("id1", "user1", "MS", false))
        accountsFlow.value = mockAccounts
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(mockAccounts, viewModel.uiState.value.accounts)
        verify(mockFolderRepository).manageObservedAccounts(mockAccounts)
    }

    @Test
    fun `folder selection is cleared when selected account is removed`() = runTest {
        // Setup initial state with a selected folder and account
        val account1 = Account("id1", "user1", "MS", false)
        val folder1 = MailFolder(
            "folderId1",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )
        viewModel =
            spy(viewModel) // Spy to verify internal calls if necessary, not strictly needed here for state

        accountsFlow.value = listOf(account1)
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate selecting a folder
        viewModel.selectFolder(folder1, account1)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.selectedFolder)
        assertEquals(account1.id, viewModel.uiState.value.selectedFolderAccountId)

        // Remove the account
        accountsFlow.value = emptyList()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedFolder)
        assertNull(viewModel.uiState.value.selectedFolderAccountId)
        verify(mockMessageRepository, times(1)).setTargetFolder(
            null,
            null
        ) // Initial selection + clear
        verify(mockThreadRepository, times(1)).setTargetFolderForThreads(
            null,
            null,
            null
        )  // Initial selection + clear
    }


    @Test
    fun `toast message updates from account repository action messages`() = runTest {
        val message = "Test Toast"
        accountActionMessagesFlow.emit(message)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(message, viewModel.uiState.value.toastMessage)
    }

    @Test
    fun `foldersByAccountId updates from repository`() = runTest {
        val folderState = FolderFetchState.Success(
            listOf(
                MailFolder(
                    "f1",
                    "Inbox",
                    "Inbox",
                    "id1",
                    WellKnownFolderType.INBOX,
                    0,
                    0,
                    true,
                    '/',
                    false
                )
            )
        )
        val newFolderMap = mapOf("id1" to folderState)
        foldersStateFlow.value = newFolderMap
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(newFolderMap, viewModel.uiState.value.foldersByAccountId)
    }

    @Test
    fun `messageDataState updates from repository`() = runTest {
        val successState = MessageDataState.Success(emptyList())
        messageDataStateFlow.value = successState
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(successState, viewModel.uiState.value.messageDataState)
    }

    @Test
    fun `threadDataState updates from repository`() = runTest {
        val successState = ThreadDataState.Success(emptyList())
        threadDataStateFlow.value = successState
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(successState, viewModel.uiState.value.threadDataState)
    }

    @Test
    fun `currentViewMode updates from user preferences`() = runTest {
        userPreferencesFlow.value = UserPreferences(mailViewMode = MailViewModePreference.MESSAGES)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(MailViewModePreference.MESSAGES, viewModel.uiState.value.currentViewMode)
    }

    @Test
    fun `startSignInProcess success updates isLoading and posts toast`() = runTest {
        val mockAccount = Account("id1", "user1", "MS", false)
        val successResult = GenericAuthResult.Success(mockAccount)
        whenever(mockDefaultAccountRepository.signIn(any(), any(), any())).thenReturn(
            flowOf(
                successResult
            )
        )

        viewModel.startSignInProcess(mockActivity, "MS")

        // isLoadingAccountAction should become true then false
        assertTrue(viewModel.uiState.value.isLoadingAccountAction) // Set true at start of function
        testDispatcher.scheduler.advanceUntilIdle() // Let flow emission and collection happen
        assertFalse(viewModel.uiState.value.isLoadingAccountAction) // Set false on result

        assertEquals("Signed in as user1", viewModel.uiState.value.toastMessage)
        verify(mockDefaultAccountRepository).signIn(mockActivity, null, "MS")
    }

    @Test
    fun `startSignInProcess UiActionRequired updates isLoading and posts intent`() = runTest {
        val mockIntent = mock<Intent>()
        val uiRequiredResult = GenericAuthResult.UiActionRequired(mockIntent)
        whenever(mockDefaultAccountRepository.signIn(any(), any(), any())).thenReturn(
            flowOf(
                uiRequiredResult
            )
        )

        viewModel.startSignInProcess(mockActivity, "GOOGLE")
        testDispatcher.scheduler.advanceUntilIdle() // isLoadingAccountAction is true due to UiActionRequired

        assertTrue(viewModel.uiState.value.isLoadingAccountAction)
        assertEquals(mockIntent, viewModel.pendingAuthIntent.value)
        verify(mockDefaultAccountRepository).signIn(mockActivity, null, "GOOGLE")
    }

    @Test
    fun `startSignInProcess MSAL interactive error shows specific toast`() = runTest {
        val errorResult = GenericAuthResult.Error(
            message = "MSAL interactive required",
            type = net.melisma.core_data.model.GenericAuthErrorType.AUTHENTICATION_FAILED,
            msalRequiresInteractiveSignIn = true
        )
        whenever(mockDefaultAccountRepository.signIn(any(), any(), any())).thenReturn(
            flowOf(
                errorResult
            )
        )

        viewModel.startSignInProcess(mockActivity, "MS")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoadingAccountAction)
        assertEquals(
            "Sign-in failed: Please try signing in again. (Interactive action needed)",
            viewModel.uiState.value.toastMessage
        )
    }

    @Test
    fun `startSignInProcess generic error shows generic toast`() = runTest {
        val errorResult = GenericAuthResult.Error(
            message = "Some other error",
            type = net.melisma.core_data.model.GenericAuthErrorType.UNKNOWN_ERROR,
            msalRequiresInteractiveSignIn = false
        )
        whenever(mockDefaultAccountRepository.signIn(any(), any(), any())).thenReturn(
            flowOf(
                errorResult
            )
        )

        viewModel.startSignInProcess(mockActivity, "MS")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoadingAccountAction)
        assertEquals("Error: Some other error", viewModel.uiState.value.toastMessage)
    }

    @Test
    fun `authIntentLaunched clears pendingAuthIntent`() = runTest {
        val mockIntent = mock<Intent>()
        viewModel.pendingAuthIntent.value = mockIntent // Simulate an intent being pending
        assertNotNull(viewModel.pendingAuthIntent.value)

        viewModel.authIntentLaunched()
        assertNull(viewModel.pendingAuthIntent.value)
    }

    @Test
    fun `handleAuthenticationResult calls repository`() = runTest {
        val mockIntent = mock<Intent>()
        viewModel.handleAuthenticationResult("GOOGLE", Activity.RESULT_OK, mockIntent)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(mockDefaultAccountRepository).handleAuthenticationResult(
            "GOOGLE",
            Activity.RESULT_OK,
            mockIntent
        )
    }

    @Test
    fun `signOutAndRemoveAccount calls repository and updates isLoading`() = runTest {
        val account = Account("id1", "user1", "MS", false)
        whenever(mockDefaultAccountRepository.signOut(account)).thenReturn(
            flowOf(
                GenericSignOutResult.Success
            )
        )

        viewModel.signOutAndRemoveAccount(account)
        assertTrue(viewModel.uiState.value.isLoadingAccountAction)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoadingAccountAction)
        assertEquals("Signed out user1", viewModel.uiState.value.toastMessage)
        verify(mockDefaultAccountRepository).signOut(account)
    }

    @Test
    fun `toastMessageShown clears toastMessage`() = runTest {
        viewModel.uiState.value = viewModel.uiState.value.copy(toastMessage = "A toast")
        assertNotNull(viewModel.uiState.value.toastMessage)

        viewModel.toastMessageShown()
        assertNull(viewModel.uiState.value.toastMessage)
    }

    @Test
    fun `selectFolder updates state and calls repository for THREADS view`() = runTest {
        val account = Account("id1", "user1", "MS", false)
        val folder = MailFolder(
            "f1",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )
        userPreferencesFlow.value =
            UserPreferences(mailViewMode = MailViewModePreference.THREADS) // Ensure THREADS mode
        testDispatcher.scheduler.advanceUntilIdle() // let preference update

        viewModel.selectFolder(folder, account)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(folder, state.selectedFolder)
        assertEquals(account.id, state.selectedFolderAccountId)
        assertEquals(MailViewModePreference.THREADS, state.currentViewMode)

        verify(mockThreadRepository).setTargetFolderForThreads(account, folder)
        verify(mockMessageRepository, never()).setTargetFolder(
            any(),
            any()
        ) // Should not call for messages
    }

    @Test
    fun `selectFolder updates state and calls repository for MESSAGES view`() = runTest {
        val account = Account("id1", "user1", "MS", false)
        val folder = MailFolder(
            "f1",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )
        userPreferencesFlow.value = UserPreferences(mailViewMode = MailViewModePreference.MESSAGES)
        testDispatcher.scheduler.advanceUntilIdle() // let preference update

        viewModel.selectFolder(folder, account)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(folder, state.selectedFolder)
        assertEquals(account.id, state.selectedFolderAccountId)
        assertEquals(MailViewModePreference.MESSAGES, state.currentViewMode)

        verify(mockMessageRepository).setTargetFolder(account, folder)
        verify(mockThreadRepository, never()).setTargetFolderForThreads(any(), any(), any())
    }

    @Test
    fun `selectFolder with same folder and account in THREADS mode does not re-fetch if data exists`() =
        runTest {
            val account = Account("id1", "user1", "MS", false)
            val folder = MailFolder(
                "f1",
                "Inbox",
                "Inbox",
                "id1",
                WellKnownFolderType.INBOX,
                0,
                0,
                true,
                '/',
                false
            )

            userPreferencesFlow.value =
                UserPreferences(mailViewMode = MailViewModePreference.THREADS)
            threadDataStateFlow.value = ThreadDataState.Success(emptyList()) // Data already exists
            testDispatcher.scheduler.advanceUntilIdle()

            // Select folder first time
            viewModel.selectFolder(folder, account)
            testDispatcher.scheduler.advanceUntilIdle()

            // Select same folder again
            viewModel.selectFolder(folder, account)
            testDispatcher.scheduler.advanceUntilIdle()

            verify(mockThreadRepository, times(1)).setTargetFolderForThreads(
                account,
                folder
            ) // Called only once
        }

    @Test
    fun `setViewModePreference updates preference and reselects folder if one is selected`() =
        runTest {
            val account = Account("acc1", "testAcc", "MS", false)
            val folder = MailFolder(
                "folder1",
                "Inbox",
                "Inbox",
                "acc1",
                WellKnownFolderType.INBOX,
                0,
                0,
                true,
                '/',
                false
            )

            // Initial setup
            accountsFlow.value = listOf(account)
            userPreferencesFlow.value =
                UserPreferences(mailViewMode = MailViewModePreference.THREADS)
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.selectFolder(folder, account) // Select a folder
            testDispatcher.scheduler.advanceUntilIdle()

            // Reset mocks for verification of re-selection
            org.mockito.kotlin.reset(mockMessageRepository, mockThreadRepository)
            whenever(mockUserPreferencesRepository.updateMailViewMode(any())).thenReturn(Unit) // Ensure it doesn't throw

            // Change preference
            viewModel.setViewModePreference(MailViewModePreference.MESSAGES)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify preference was updated
            verify(mockUserPreferencesRepository).updateMailViewMode(MailViewModePreference.MESSAGES)
            // Verify UI state reflects new mode
            assertEquals(MailViewModePreference.MESSAGES, viewModel.uiState.value.currentViewMode)
            // Verify folder content is re-fetched for the new mode
            verify(mockMessageRepository).setTargetFolder(account, folder)
            verify(mockThreadRepository, never()).setTargetFolderForThreads(
                any(),
                any(),
                any()
            ) // Assuming threads were cleared or not called
        }

    @Test
    fun `setViewModePreference updates data states when switching modes`() = runTest {
        // Start in THREADS mode with some message data (which shouldn't exist but to test reset)
        userPreferencesFlow.value = UserPreferences(mailViewMode = MailViewModePreference.THREADS)
        messageDataStateFlow.value =
            MessageDataState.Success(emptyList()) // Simulate some message data
        threadDataStateFlow.value = ThreadDataState.Initial
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            MessageDataState.Success(emptyList()),
            viewModel.uiState.value.messageDataState
        )

        // Switch to MESSAGES mode
        viewModel.setViewModePreference(MailViewModePreference.MESSAGES)
        testDispatcher.scheduler.advanceUntilIdle()
        // MessageDataState should remain, ThreadDataState should be reset (if it had data)

        // Now switch back to THREADS
        // Simulate some thread data
        threadDataStateFlow.value = ThreadDataState.Success(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ThreadDataState.Success(emptyList()), viewModel.uiState.value.threadDataState)


        viewModel.setViewModePreference(MailViewModePreference.THREADS)
        testDispatcher.scheduler.advanceUntilIdle()

        // MessageDataState should be reset to Initial
        assertEquals(MessageDataState.Initial, viewModel.uiState.value.messageDataState)
    }


    @Test
    fun `refreshCurrentView calls thread repository in THREADS mode`() = runTest {
        val account = Account("id1", "user1", "MS", false)
        val folder = MailFolder(
            "f1",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )
        accountsFlow.value = listOf(account)
        userPreferencesFlow.value = UserPreferences(mailViewMode = MailViewModePreference.THREADS)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.selectFolder(folder, account)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refreshCurrentView(mockActivity)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockThreadRepository, times(2)).setTargetFolderForThreads(
            account,
            folder,
            mockActivity
        ) // Once for select, once for refresh
    }

    @Test
    fun `refreshCurrentView calls message repository in MESSAGES mode`() = runTest {
        val account = Account("id1", "user1", "MS", false)
        val folder = MailFolder(
            "f1",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )
        accountsFlow.value = listOf(account)
        userPreferencesFlow.value = UserPreferences(mailViewMode = MailViewModePreference.MESSAGES)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.selectFolder(folder, account)
        testDispatcher.scheduler.advanceUntilIdle()

        // Reset mock because selectFolder also calls setTargetFolder
        org.mockito.kotlin.reset(mockMessageRepository)

        viewModel.refreshCurrentView(mockActivity)
        testDispatcher.scheduler.advanceUntilIdle()

        // Expecting setTargetFolder to be called. The current implementation of refreshCurrentView calls setTargetFolder again.
        verify(mockMessageRepository).setTargetFolder(account, folder)
    }

    @Test
    fun `refreshCurrentView does nothing if no folder selected`() = runTest {
        viewModel.refreshCurrentView(mockActivity)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Select a folder first.", viewModel.uiState.value.toastMessage)
        verify(mockThreadRepository, never()).setTargetFolderForThreads(any(), any(), any())
        verify(mockMessageRepository, never()).setTargetFolder(any(), any())
    }

    @Test
    fun `getSelectedAccount returns correct account`() = runTest {
        val account1 = Account("id1", "user1", "MS", false)
        val account2 = Account("id2", "user2", "GOOGLE", false)
        val folder1 = MailFolder(
            "f1",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )

        accountsFlow.value = listOf(account1, account2)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.getSelectedAccount()) // No folder selected initially

        viewModel.selectFolder(folder1, account1)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(account1, viewModel.getSelectedAccount())
    }

    @Test
    fun `default folder selection picks Inbox if available`() = runTest {
        val account1 = Account("id1", "user1", "MS", false)
        val inboxFolder = MailFolder(
            "inboxId",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )
        val otherFolder = MailFolder(
            "otherId",
            "Other",
            "Other",
            "id1",
            WellKnownFolderType.UNKNOWN,
            0,
            0,
            true,
            '/',
            false
        )

        overallApplicationAuthStateFlow.value =
            OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
        accountsFlow.value = listOf(account1)
        foldersStateFlow.value = mapOf(
            "id1" to FolderFetchState.Success(
                listOf(
                    otherFolder,
                    inboxFolder
                )
            )
        ) // Inbox is second
        testDispatcher.scheduler.advanceUntilIdle() // This will trigger selectDefaultFolderIfNeeded

        assertNotNull(viewModel.uiState.value.selectedFolder)
        assertEquals(inboxFolder.id, viewModel.uiState.value.selectedFolder?.id)
        assertEquals(account1.id, viewModel.uiState.value.selectedFolderAccountId)
    }

    @Test
    fun `default folder selection picks first folder if Inbox not available`() = runTest {
        val account1 = Account("id1", "user1", "MS", false)
        val firstFolder = MailFolder(
            "firstId",
            "My Stuff",
            "My Stuff",
            "id1",
            WellKnownFolderType.UNKNOWN,
            0,
            0,
            true,
            '/',
            false
        )
        val otherFolder = MailFolder(
            "otherId",
            "Other",
            "Other",
            "id1",
            WellKnownFolderType.UNKNOWN,
            0,
            0,
            true,
            '/',
            false
        )

        overallApplicationAuthStateFlow.value =
            OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
        accountsFlow.value = listOf(account1)
        foldersStateFlow.value =
            mapOf("id1" to FolderFetchState.Success(listOf(firstFolder, otherFolder)))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.selectedFolder)
        assertEquals(firstFolder.id, viewModel.uiState.value.selectedFolder?.id)
    }

    @Test
    fun `default folder selection skipped if auth state is not suitable`() = runTest {
        overallApplicationAuthStateFlow.value = OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
        accountsFlow.value = emptyList() // also no accounts
        foldersStateFlow.value = emptyMap()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedFolder)
    }

    @Test
    fun `overallApplicationAuthState change to NO_ACCOUNTS clears folder selection`() = runTest {
        // Arrange: Setup an initial state with a selected folder
        val account = Account("id1", "user1", "MS", false)
        val folder = MailFolder(
            "f1",
            "Inbox",
            "Inbox",
            "id1",
            WellKnownFolderType.INBOX,
            0,
            0,
            true,
            '/',
            false
        )
        overallApplicationAuthStateFlow.value =
            OverallApplicationAuthState.AT_LEAST_ONE_ACCOUNT_AUTHENTICATED
        accountsFlow.value = listOf(account)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.selectFolder(folder, account)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.selectedFolder)

        // Act: Change auth state to one that should clear selection
        overallApplicationAuthStateFlow.value = OverallApplicationAuthState.NO_ACCOUNTS_CONFIGURED
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert: Folder selection is cleared
        assertNull(viewModel.uiState.value.selectedFolder)
        assertNull(viewModel.uiState.value.selectedFolderAccountId)
        verify(mockMessageRepository, times(1)).setTargetFolder(null, null)
        verify(mockThreadRepository, times(1)).setTargetFolderForThreads(null, null, null)
    }
} 