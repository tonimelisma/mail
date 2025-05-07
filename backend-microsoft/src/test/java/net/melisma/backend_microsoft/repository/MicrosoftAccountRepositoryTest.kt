package net.melisma.backend_microsoft.repository

import android.app.Activity
import android.util.Log
import app.cash.turbine.test
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import io.mockk.CapturingSlot
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.melisma.backend_microsoft.auth.AddAccountResult
import net.melisma.backend_microsoft.auth.AuthStateListener
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.RemoveAccountResult
import net.melisma.core_common.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicrosoftAccountRepositoryTest {

    // --- Mocks ---
    private lateinit var mockAuthManager: MicrosoftAuthManager
    private lateinit var mockErrorMapper: ErrorMapperService // Mock the INTERFACE
    private lateinit var mockActivity: Activity

    // --- Class Under Test ---
    private lateinit var repository: MicrosoftAccountRepository

    // --- Captured Listener & Lambdas ---
    private lateinit var authStateListenerSlot: CapturingSlot<AuthStateListener>
    private val addAccountCallbackSlot = slot<(AddAccountResult) -> Unit>()
    private val removeAccountCallbackSlot = slot<(RemoveAccountResult) -> Unit>()

    // --- Test Data ---
    private val testMsalAccount1: IAccount = mockk {
        every { id } returns "ms_id_1"
        every { username } returns "user1@test.com"
    }
    private val testGenericAccount1 = Account("ms_id_1", "user1@test.com", "MS")
    private val testScopes = listOf("User.Read", "Mail.Read")

    @Before
    fun setUp() {
        mockAuthManager = mockk(relaxUnitFun = true)
        mockErrorMapper = mockk() // Mock the interface
        mockActivity = mockk()

        authStateListenerSlot = slot()
        every { mockAuthManager.setAuthStateListener(capture(authStateListenerSlot)) } just runs
        every { mockAuthManager.isInitialized } returns false
        every { mockAuthManager.initializationError } returns null
        every { mockAuthManager.accounts } returns emptyList()
        every {
            mockAuthManager.addAccount(
                any(),
                any(),
                capture(addAccountCallbackSlot)
            )
        } just runs
        every {
            mockAuthManager.removeAccount(
                eq(testMsalAccount1),
                capture(removeAccountCallbackSlot)
            )
        } just runs

        // Mock the interface methods
        every { mockErrorMapper.mapAuthExceptionToUserMessage(any()) } answers {
            "Mapped Error: " + ((args[0] as? Throwable)?.message ?: "Unknown Auth")
        }
        // mapNetworkOrApiException might be called by mapAuthExceptionToUserMessage as fallback
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
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkAll()
    }

    // Helper function to initialize repository within a test scope
    private fun initializeRepository(
        testScope: TestScope,
        isInitialized: Boolean,
        initialError: MsalException?,
        initialMsalAccounts: List<IAccount>
    ) {
        every { mockAuthManager.isInitialized } returns isInitialized
        every { mockAuthManager.initializationError } returns initialError
        every { mockAuthManager.accounts } returns initialMsalAccounts

        // Pass the mocked INTERFACE to the constructor
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)

        // Manually trigger listener if needed
        if (authStateListenerSlot.isCaptured) {
            authStateListenerSlot.captured.onAuthStateChanged(
                isInitialized = isInitialized,
                accounts = initialMsalAccounts,
                error = initialError
            )
        }
    }

    // --- Test Cases ---

    @Test
    fun `init determines initial state correctly - Initializing`() = runTest {
        initializeRepository(this, false, null, emptyList())
        advanceUntilIdle()
        assertEquals(AuthState.Initializing, repository.authState.value)
        assertTrue(repository.accounts.value.isEmpty())
        assertFalse(repository.isLoadingAccountAction.value)
    }

    @Test
    fun `init determines initial state correctly - InitializationError`() = runTest {
        val initError = MsalClientException("init_failed", "Init failed")
        initializeRepository(this, false, initError, emptyList())
        advanceUntilIdle()
        val state = repository.authState.value
        assertTrue(state is AuthState.InitializationError)
        assertEquals(initError, (state as AuthState.InitializationError).error)
    }

    @Test
    fun `init determines initial state correctly - Initialized with accounts`() = runTest {
        initializeRepository(this, true, null, listOf(testMsalAccount1))
        advanceUntilIdle()
        assertEquals(AuthState.Initialized, repository.authState.value)
        assertEquals(listOf(testGenericAccount1), repository.accounts.value)
    }


    @Test
    fun `onAuthStateChanged updates state flows`() = runTest {
        initializeRepository(this, false, null, emptyList())
        advanceUntilIdle()
        assertTrue(authStateListenerSlot.isCaptured)
        val listener = authStateListenerSlot.captured

        // Act & Assert: Initialized, no accounts
        listener.onAuthStateChanged(isInitialized = true, accounts = emptyList(), error = null)
        advanceUntilIdle()
        assertEquals(AuthState.Initialized, repository.authState.value)
        assertTrue(repository.accounts.value.isEmpty())

        // Act & Assert: Initialized, one account
        listener.onAuthStateChanged(
            isInitialized = true,
            accounts = listOf(testMsalAccount1),
            error = null
        )
        advanceUntilIdle()
        assertEquals(AuthState.Initialized, repository.authState.value)
        assertEquals(listOf(testGenericAccount1), repository.accounts.value)

        // Act & Assert: Error state
        val initError = MsalClientException("init_error", "Error during init")
        listener.onAuthStateChanged(
            isInitialized = false,
            accounts = emptyList(),
            error = initError
        )
        advanceUntilIdle()
        val errorState = repository.authState.value
        assertTrue(errorState is AuthState.InitializationError)
        assertEquals(initError, (errorState as AuthState.InitializationError).error)
    }

    @Test
    fun `addAccount success updates loading state and emits message`() = runTest {
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle()

        repository.accountActionMessage.test {
            expectNoEvents()
            repository.addAccount(mockActivity, testScopes)
            assertTrue(repository.isLoadingAccountAction.value)

            val successResult = AddAccountResult.Success(testMsalAccount1)
            assertTrue(addAccountCallbackSlot.isCaptured)
            addAccountCallbackSlot.captured.invoke(successResult)
            authStateListenerSlot.captured.onAuthStateChanged(true, listOf(testMsalAccount1), null)
            advanceUntilIdle()

            assertFalse(repository.isLoadingAccountAction.value)
            assertEquals("Account added: ${testMsalAccount1.username}", awaitItem())
            assertEquals(listOf(testGenericAccount1), repository.accounts.value)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { mockAuthManager.addAccount(mockActivity, testScopes, any()) }
    }


    @Test
    fun `addAccount error updates loading state and emits error message`() = runTest {
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle()
        val addError = MsalClientException("add_fail", "Add failed")
        // Mock the specific error mapping call
        every { mockErrorMapper.mapAuthExceptionToUserMessage(addError) } returns "Mapped Add Error"

        repository.accountActionMessage.test {
            expectNoEvents()
            repository.addAccount(mockActivity, testScopes)
            assertTrue(repository.isLoadingAccountAction.value)

            val errorResult = AddAccountResult.Error(addError)
            assertTrue(addAccountCallbackSlot.isCaptured)
            addAccountCallbackSlot.captured.invoke(errorResult)
            authStateListenerSlot.captured.onAuthStateChanged(true, emptyList(), null)
            advanceUntilIdle()

            assertFalse(repository.isLoadingAccountAction.value)
            // Assert message uses the mocked mapped error
            assertEquals("Error adding account: Mapped Add Error", awaitItem())
            assertTrue(repository.accounts.value.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        verify { mockErrorMapper.mapAuthExceptionToUserMessage(addError) } // Verify specific call
    }


    @Test
    fun `addAccount cancelled updates loading state and emits cancel message`() = runTest {
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle()

        repository.accountActionMessage.test {
            expectNoEvents()
            repository.addAccount(mockActivity, testScopes)
            assertTrue(repository.isLoadingAccountAction.value)

            assertTrue(addAccountCallbackSlot.isCaptured)
            addAccountCallbackSlot.captured.invoke(AddAccountResult.Cancelled)
            authStateListenerSlot.captured.onAuthStateChanged(true, emptyList(), null)
            advanceUntilIdle()

            assertFalse(repository.isLoadingAccountAction.value)
            assertEquals("Account addition cancelled.", awaitItem())
            assertTrue(repository.accounts.value.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test
    fun `removeAccount success updates loading state and emits message`() = runTest {
        initializeRepository(this, true, null, listOf(testMsalAccount1))
        advanceUntilIdle()

        repository.accountActionMessage.test {
            expectNoEvents()
            repository.removeAccount(testGenericAccount1)
            assertTrue(repository.isLoadingAccountAction.value)

            assertTrue(removeAccountCallbackSlot.isCaptured)
            removeAccountCallbackSlot.captured.invoke(RemoveAccountResult.Success)
            authStateListenerSlot.captured.onAuthStateChanged(true, emptyList(), null)
            advanceUntilIdle()

            assertFalse(repository.isLoadingAccountAction.value)
            assertEquals("Account removed: ${testMsalAccount1.username}", awaitItem())
            assertTrue(repository.accounts.value.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { mockAuthManager.removeAccount(eq(testMsalAccount1), any()) }
    }

    @Test
    fun `removeAccount error updates loading state and emits error message`() = runTest {
        val initialAccounts = listOf(testMsalAccount1)
        initializeRepository(this, true, null, initialAccounts)
        advanceUntilIdle()
        val removeError = MsalServiceException("remove_fail", "Remove failed", null)
        // Mock the specific error mapping call
        every { mockErrorMapper.mapAuthExceptionToUserMessage(removeError) } returns "Mapped Remove Error"

        repository.accountActionMessage.test {
            expectNoEvents()
            repository.removeAccount(testGenericAccount1)
            assertTrue(repository.isLoadingAccountAction.value)

            assertTrue(removeAccountCallbackSlot.isCaptured)
            val errorResult = RemoveAccountResult.Error(removeError)
            removeAccountCallbackSlot.captured.invoke(errorResult)
            authStateListenerSlot.captured.onAuthStateChanged(true, initialAccounts, null)
            advanceUntilIdle()

            assertFalse(repository.isLoadingAccountAction.value)
            // Assert message uses the mocked mapped error
            assertEquals("Error removing account: Mapped Remove Error", awaitItem())
            assertEquals(listOf(testGenericAccount1), repository.accounts.value)
            cancelAndIgnoreRemainingEvents()
        }
        verify { mockErrorMapper.mapAuthExceptionToUserMessage(removeError) } // Verify specific call
    }

    @Test
    fun `removeAccount account not found in manager emits message`() = runTest {
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle()

        repository.accountActionMessage.test {
            expectNoEvents()
            repository.removeAccount(testGenericAccount1) // Account not in manager's list
            advanceUntilIdle()

            assertFalse(repository.isLoadingAccountAction.value)
            assertEquals("Account not found for removal.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { mockAuthManager.removeAccount(any(), any()) }
    }


    @Test
    fun `addOrRemoveAccount does nothing if not initialized`() = runTest {
        initializeRepository(this, false, null, emptyList())
        advanceUntilIdle()

        repository.accountActionMessage.test {
            expectNoEvents()
            repository.addAccount(mockActivity, testScopes)
            advanceUntilIdle()
            assertEquals("Authentication system not ready.", awaitItem())
            assertFalse(repository.isLoadingAccountAction.value)

            repository.removeAccount(testGenericAccount1)
            advanceUntilIdle()
            assertEquals("Authentication system not ready.", awaitItem())
            assertFalse(repository.isLoadingAccountAction.value)

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { mockAuthManager.addAccount(any(), any(), any()) }
        coVerify(exactly = 0) { mockAuthManager.removeAccount(any(), any()) }
    }


    @Test
    fun `clearAccountActionMessage emits null`() = runTest {
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle()
        // Trigger an initial message
        repository.addAccount(mockActivity, testScopes)
        assertTrue(addAccountCallbackSlot.isCaptured)
        addAccountCallbackSlot.captured.invoke(AddAccountResult.Cancelled)
        authStateListenerSlot.captured.onAuthStateChanged(true, emptyList(), null)
        advanceUntilIdle()

        repository.accountActionMessage.test {
            assertEquals("Account addition cancelled.", awaitItem()) // Consume initial message
            repository.clearAccountActionMessage()
            advanceUntilIdle()
            assertEquals(null, awaitItem()) // Expect null after clear
            cancelAndIgnoreRemainingEvents()
        }
    }
}
