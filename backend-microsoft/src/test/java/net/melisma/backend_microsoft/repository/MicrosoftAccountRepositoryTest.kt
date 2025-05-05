package net.melisma.backend_microsoft.repository

// Removed unused flow imports: mapNotNull, toList
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.melisma.backend_microsoft.auth.AddAccountResult
import net.melisma.backend_microsoft.auth.AuthStateListener
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.RemoveAccountResult
import net.melisma.backend_microsoft.errors.ErrorMapper
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Removed Robolectric imports

@OptIn(ExperimentalCoroutinesApi::class)
// Removed @RunWith and @Config annotations
class MicrosoftAccountRepositoryTest {

    // --- Mocks ---
    private lateinit var mockAuthManager: MicrosoftAuthManager
    private lateinit var mockErrorMapper: ErrorMapper
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
        mockErrorMapper = mockk(relaxed = true)
        mockActivity = mockk()

        authStateListenerSlot = slot()
        every { mockAuthManager.setAuthStateListener(capture(authStateListenerSlot)) } just runs
        every { mockAuthManager.isInitialized } returns false // Default to not initialized
        every { mockAuthManager.initializationError } returns null
        every { mockAuthManager.accounts } returns emptyList() // Use manager's accounts property
        every {
            mockAuthManager.addAccount(
                any(),
                any(),
                capture(addAccountCallbackSlot)
            )
        } just runs
        // *** Use eq() for the account mock in removeAccount setup if needed, or rely on specific instance if relaxUnitFun=true handles it ***
        every {
            mockAuthManager.removeAccount(
                eq(testMsalAccount1),
                capture(removeAccountCallbackSlot)
            )
        } just runs

        // Make error mapper mock more realistic - return message based on input
        every { mockErrorMapper.mapAuthExceptionToUserMessage(any()) } answers {
            "Mapped Error: " + ((args[0] as? Throwable)?.message ?: "Unknown")
        }
        every { mockErrorMapper.mapGraphExceptionToUserMessage(any()) } answers {
            "Mapped Graph Error: " + ((args[0] as? Throwable)?.message ?: "Unknown")
        }


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

        // Defer repository creation to tests or helper to ensure correct TestScope injection
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class) // Keep this
        unmockkAll()
    }

    // Helper function refactored to avoid nested runTest
    private fun initializeRepository(
        testScope: TestScope, // Pass the scope from the test
        isInitialized: Boolean,
        initialError: MsalException?, // Use imported MsalException type
        initialMsalAccounts: List<IAccount> // Use more descriptive name for clarity
    ) {
        every { mockAuthManager.isInitialized } returns isInitialized
        every { mockAuthManager.initializationError } returns initialError
        every { mockAuthManager.accounts } returns initialMsalAccounts // Setup mock manager's state

        // Initialize the repository within the test's scope
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)

        // Manually trigger listener if needed to simulate initial state sync
        // This happens *after* the repository constructor registers the listener.
        if (authStateListenerSlot.isCaptured) {
            authStateListenerSlot.captured.onAuthStateChanged(
                isInitialized = isInitialized,
                accounts = initialMsalAccounts, // Pass the 'initialMsalAccounts' list
                error = initialError
            )
        }
        // advanceUntilIdle() must be called *in the test* after calling this helper
    }


    // --- Test Cases ---

    @Test
    fun `init determines initial state correctly - Initializing`() = runTest {
        // Arrange: Initialize with default mocks (not initialized, no error, no accounts)
        initializeRepository(this, false, null, emptyList())
        advanceUntilIdle() // Allow init process and potential listener calls

        // Assert
        assertEquals(AuthState.Initializing, repository.authState.value)
        assertTrue(repository.accounts.value.isEmpty())
        assertFalse(repository.isLoadingAccountAction.value)
    }

    @Test
    fun `init determines initial state correctly - InitializationError`() = runTest {
        // Arrange
        val initError = MsalClientException("init_failed", "Init failed")
        initializeRepository(this, false, initError, emptyList())
        advanceUntilIdle() // Allow init process and potential listener calls

        // Assert
        val state = repository.authState.value
        assertTrue(state is AuthState.InitializationError)
        assertEquals(initError, (state as AuthState.InitializationError).error)
        assertTrue(repository.accounts.value.isEmpty())
        assertFalse(repository.isLoadingAccountAction.value)
    }

    @Test
    fun `init determines initial state correctly - Initialized with accounts`() = runTest {
        // Arrange
        initializeRepository(this, true, null, listOf(testMsalAccount1))
        advanceUntilIdle() // Allow potential listener call during init to process

        // Assert
        assertEquals(AuthState.Initialized, repository.authState.value)
        assertEquals(1, repository.accounts.value.size)
        assertEquals(testGenericAccount1, repository.accounts.value[0])
        assertFalse(repository.isLoadingAccountAction.value)
    }


    @Test
    fun `onAuthStateChanged updates state flows`() = runTest {
        // Arrange: Start in Initializing state
        initializeRepository(this, false, null, emptyList())
        advanceUntilIdle() // Process init
        assertEquals(AuthState.Initializing, repository.authState.value)
        assertTrue(authStateListenerSlot.isCaptured)
        val listener = authStateListenerSlot.captured

        // Act & Assert: Initialized, no accounts
        listener.onAuthStateChanged(isInitialized = true, accounts = emptyList(), error = null)
        advanceUntilIdle() // Allow state updates to propagate
        assertEquals(AuthState.Initialized, repository.authState.value)
        assertTrue(repository.accounts.value.isEmpty())
        assertFalse(repository.isLoadingAccountAction.value) // Should reset loading

        // Act & Assert: Initialized, one account
        listener.onAuthStateChanged(
            isInitialized = true,
            accounts = listOf(testMsalAccount1),
            error = null
        )
        advanceUntilIdle()
        assertEquals(AuthState.Initialized, repository.authState.value)
        assertEquals(1, repository.accounts.value.size)
        assertEquals(testGenericAccount1, repository.accounts.value[0])
        assertFalse(repository.isLoadingAccountAction.value)

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
        assertTrue(repository.accounts.value.isEmpty())
        assertFalse(repository.isLoadingAccountAction.value)
    }

    @Test
    fun `addAccount success updates loading state and emits message`() = runTest { // Test #4
        // Arrange: Start initialized
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle() // Process init listener

        // Act & Assert
        repository.accountActionMessage.test {
            expectNoEvents() // Ensure no initial message

            repository.addAccount(mockActivity, testScopes)
            assertTrue(repository.isLoadingAccountAction.value) // Loading set immediately

            // Simulate successful callback & listener update
            val successResult = AddAccountResult.Success(testMsalAccount1)
            assertTrue(addAccountCallbackSlot.isCaptured)
            addAccountCallbackSlot.captured.invoke(successResult)
            authStateListenerSlot.captured.onAuthStateChanged(
                isInitialized = true,
                accounts = listOf(testMsalAccount1),
                error = null
            )
            advanceUntilIdle() // Process callback message emit & listener state update

            // Assert final state
            assertFalse(repository.isLoadingAccountAction.value) // Loading reset by listener
            val expectedMessage = "Account added: ${testMsalAccount1.username}"
            assertEquals(expectedMessage, awaitItem())
            assertEquals(
                listOf(testGenericAccount1),
                repository.accounts.value
            ) // Account list updated

            cancelAndIgnoreRemainingEvents()
        }
        coVerify { mockAuthManager.addAccount(mockActivity, testScopes, any()) }
    }


    @Test
    fun `addAccount error updates loading state and emits error message`() = runTest { // Test #7
        // Arrange: Start initialized
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle()
        val addError = MsalClientException("add_fail", "Add failed")
        val expectedMappedError = "Mapped Error: ${addError.message}"
        every { mockErrorMapper.mapAuthExceptionToUserMessage(addError) } returns expectedMappedError

        // Act & Assert
        repository.accountActionMessage.test {
            expectNoEvents()

            repository.addAccount(mockActivity, testScopes)
            assertTrue(repository.isLoadingAccountAction.value)

            // Simulate error callback & listener update
            val errorResult = AddAccountResult.Error(addError)
            assertTrue(addAccountCallbackSlot.isCaptured)
            addAccountCallbackSlot.captured.invoke(errorResult)
            authStateListenerSlot.captured.onAuthStateChanged(
                isInitialized = true,
                accounts = emptyList(), // Assume accounts unchanged on error
                error = null
            )
            advanceUntilIdle()

            // Assert final state
            assertFalse(repository.isLoadingAccountAction.value) // Loading reset by listener
            val expectedFullErrorMessage = "Error adding account: $expectedMappedError"
            assertEquals(expectedFullErrorMessage, awaitItem())
            assertTrue(repository.accounts.value.isEmpty()) // Accounts unchanged

            cancelAndIgnoreRemainingEvents()
        }
        coVerify { mockAuthManager.addAccount(mockActivity, testScopes, any()) }
    }


    @Test
    fun `addAccount cancelled updates loading state and emits cancel message`() =
        runTest { // Test #5
            // Arrange: Start initialized
            initializeRepository(this, true, null, emptyList())
            advanceUntilIdle()

            // Act & Assert
            repository.accountActionMessage.test {
                expectNoEvents()

            repository.addAccount(mockActivity, testScopes)
            assertTrue(repository.isLoadingAccountAction.value)

                // Simulate cancellation callback & listener update
            assertTrue(addAccountCallbackSlot.isCaptured)
            addAccountCallbackSlot.captured.invoke(AddAccountResult.Cancelled)
                authStateListenerSlot.captured.onAuthStateChanged(
                    isInitialized = true,
                    accounts = emptyList(), // Assume accounts unchanged on cancel
                    error = null
                )
            advanceUntilIdle()

                // Assert final state
                assertFalse(repository.isLoadingAccountAction.value) // Loading reset by listener
                assertEquals("Account addition cancelled.", awaitItem())
                assertTrue(repository.accounts.value.isEmpty()) // Accounts unchanged

                cancelAndIgnoreRemainingEvents()
            }
            coVerify { mockAuthManager.addAccount(mockActivity, testScopes, any()) }
        }


    @Test
    fun `removeAccount success updates loading state and emits message`() = runTest { // Test #3
        // Arrange: Start initialized with one account
        initializeRepository(this, true, null, listOf(testMsalAccount1))
        advanceUntilIdle()

        // Act & Assert
        repository.accountActionMessage.test {
            expectNoEvents()

            repository.removeAccount(testGenericAccount1)
            assertTrue(repository.isLoadingAccountAction.value)

            // Simulate successful callback & listener update
            assertTrue(removeAccountCallbackSlot.isCaptured)
            removeAccountCallbackSlot.captured.invoke(RemoveAccountResult.Success)
            authStateListenerSlot.captured.onAuthStateChanged(
                isInitialized = true,
                accounts = emptyList(), // Accounts list is now empty
                error = null
            )
            advanceUntilIdle()

            // Assert final state
            assertFalse(repository.isLoadingAccountAction.value) // Loading reset by listener
            val expectedMessage = "Account removed: ${testMsalAccount1.username}"
            assertEquals(expectedMessage, awaitItem())
            assertTrue(repository.accounts.value.isEmpty()) // Account list updated

            cancelAndIgnoreRemainingEvents()
        }
        coVerify { mockAuthManager.removeAccount(eq(testMsalAccount1), any()) } // Use eq() matcher
    }

    @Test
    fun `removeAccount error updates loading state and emits error message`() = runTest { // Test #9
        // Arrange: Start initialized with one account
        val initialAccounts = listOf(testMsalAccount1)
        initializeRepository(this, true, null, initialAccounts)
        advanceUntilIdle()
        val removeError = MsalServiceException("remove_fail", "Remove failed", null)
        val expectedMappedError = "Mapped Error: ${removeError.message}"
        every { mockErrorMapper.mapAuthExceptionToUserMessage(removeError) } returns expectedMappedError

        // Act & Assert
        repository.accountActionMessage.test {
            expectNoEvents()

            repository.removeAccount(testGenericAccount1)
            assertTrue(repository.isLoadingAccountAction.value)

            // Simulate error callback & listener update
            assertTrue(removeAccountCallbackSlot.isCaptured)
            val errorResult = RemoveAccountResult.Error(removeError)
            removeAccountCallbackSlot.captured.invoke(errorResult)
            authStateListenerSlot.captured.onAuthStateChanged(
                isInitialized = true,
                accounts = initialAccounts, // Assume accounts unchanged on error
                error = null
            )
            advanceUntilIdle()

            // Assert final state
            assertFalse(repository.isLoadingAccountAction.value) // Loading reset by listener
            val expectedFullErrorMessage = "Error removing account: $expectedMappedError"
            assertEquals(expectedFullErrorMessage, awaitItem())
            assertEquals(
                listOf(testGenericAccount1),
                repository.accounts.value
            ) // Accounts unchanged

            cancelAndIgnoreRemainingEvents()
        }
        coVerify { mockAuthManager.removeAccount(eq(testMsalAccount1), any()) } // Use eq() matcher
    }

    @Test
    fun `removeAccount account not found in manager emits message`() = runTest { // Test #8
        // Arrange: Start initialized with NO accounts
        initializeRepository(this, true, null, emptyList())
        advanceUntilIdle()

        // Act & Assert
        repository.accountActionMessage.test {
            expectNoEvents()

            // Try removing an account that doesn't exist in the manager's list
            repository.removeAccount(testGenericAccount1)
            advanceUntilIdle() // Allow the check and message emission to occur

            // Assert: loading state should reset, specific message emitted
            assertFalse(repository.isLoadingAccountAction.value) // Should be false as action failed early
            assertEquals("Account not found for removal.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) {
            mockAuthManager.removeAccount(
                any(),
                any()
            )
        } // Verify manager wasn't called
    }


    @Test
    fun `addOrRemoveAccount does nothing if not initialized`() = runTest {
        // Arrange: Start in Initializing state
        initializeRepository(this, false, null, emptyList())
        advanceUntilIdle() // Process init
        assertEquals(AuthState.Initializing, repository.authState.value)

        // Act & Assert
        repository.accountActionMessage.test {
            expectNoEvents()

            // Attempt add
            repository.addAccount(mockActivity, testScopes)
            advanceUntilIdle() // Let message emit
            assertEquals("Authentication system not ready.", awaitItem())
            assertFalse(repository.isLoadingAccountAction.value) // Loading should be false

            // Attempt remove
            repository.removeAccount(testGenericAccount1)
            advanceUntilIdle() // Let message emit
            assertEquals("Authentication system not ready.", awaitItem())
            assertFalse(repository.isLoadingAccountAction.value) // Loading should be false

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { mockAuthManager.addAccount(any(), any(), any()) }
        coVerify(exactly = 0) { mockAuthManager.removeAccount(any(), any()) }
    }


    @Test
    fun `clearAccountActionMessage emits null`() =
        runTest { // Test #6 - NOTE: This test had a timeout, fix is not guaranteed
            // Arrange: Start initialized and emit an initial message
            initializeRepository(this, true, null, emptyList())
            advanceUntilIdle() // Process init
            // Trigger an initial message emission
            repository.addAccount(mockActivity, testScopes)
            assertTrue(addAccountCallbackSlot.isCaptured)
            addAccountCallbackSlot.captured.invoke(AddAccountResult.Cancelled)
            authStateListenerSlot.captured.onAuthStateChanged(
                isInitialized = true,
                accounts = emptyList(), // Simulate listener update after cancel
                error = null
            )
            advanceUntilIdle() // Ensure setup completes and initial message is emitted

            // Act & Assert
        repository.accountActionMessage.test {
            // Assert: Consume the initial message
            assertEquals("Account addition cancelled.", awaitItem())

            // Act: Clear the message
            repository.clearAccountActionMessage()

            // Add advanceUntilIdle to allow launched coroutine for emission to run
            advanceUntilIdle()

            // Assert: Expect null to be emitted
            // NOTE: If this still times out, it requires deeper coroutine/dispatcher debugging
            assertEquals(null, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

}

// Helper extension function (remains unchanged)
// This function is internal to the test file and doesn't affect the interface call issue.
// Its parameter name 'msalAccounts' is local to this function.
private fun mapToGenericAccounts(msalAccounts: List<IAccount>): List<Account> {
    return msalAccounts.map { Account(it.id ?: "", it.username ?: "Unknown User", "MS") }
}