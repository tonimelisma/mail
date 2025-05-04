package net.melisma.backend_microsoft.repository

import android.app.Activity
import app.cash.turbine.test
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicrosoftAccountRepositoryTest {

    // --- Mocks ---
    private lateinit var mockAuthManager: MicrosoftAuthManager
    private lateinit var mockErrorMapper: ErrorMapper
    private lateinit var mockActivity: Activity
    private lateinit var testScope: TestScope

    // --- Class Under Test ---
    private lateinit var repository: MicrosoftAccountRepository

    // --- Captured Listener & Lambdas ---
    private lateinit var authStateListenerSlot: CapturingSlot<AuthStateListener>

    // Define slots needed for triggering callbacks in specific tests
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
        testScope = TestScope()

        authStateListenerSlot = slot()
        every { mockAuthManager.setAuthStateListener(capture(authStateListenerSlot)) } just runs

        every { mockAuthManager.isInitialized } returns false
        every { mockAuthManager.initializationError } returns null
        every { mockAuthManager.accounts } returns emptyList()

        // Setup mocks to capture lambdas ONLY when needed, avoids capturing issues in unrelated tests
        // We'll set these up within specific tests or arrange blocks if necessary
        // every { mockAuthManager.addAccount(any(), any(), capture(addAccountCallbackSlot)) } just runs // Capture dynamically
        // every { mockAuthManager.removeAccount(any(), capture(removeAccountCallbackSlot)) } just runs // Capture dynamically


        every { mockErrorMapper.mapAuthExceptionToUserMessage(any()) } answers {
            "Auth Error: " + (args[0] as? Throwable)?.message
        }
        every { mockErrorMapper.mapGraphExceptionToUserMessage(any()) } answers {
            "Graph Error: " + (args[0] as? Throwable)?.message
        }

        repository = MicrosoftAccountRepository(
            microsoftAuthManager = mockAuthManager,
            externalScope = testScope,
            errorMapper = mockErrorMapper
        )
        verify { mockAuthManager.setAuthStateListener(any()) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `init determines initial state correctly - Initializing`() = testScope.runTest {
        assertEquals(AuthState.Initializing, repository.authState.value)
        assertTrue(repository.accounts.value.isEmpty())
    }

    @Test
    fun `init determines initial state correctly - InitializationError`() = testScope.runTest {
        val initError = MsalClientException("init_failed", "Init failed")
        every { mockAuthManager.isInitialized } returns false
        every { mockAuthManager.initializationError } returns initError
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)

        val state = repository.authState.value
        assertTrue(state is AuthState.InitializationError)
        assertEquals(initError, (state as AuthState.InitializationError).error)
        assertTrue(repository.accounts.value.isEmpty())
    }

    @Test
    fun `init determines initial state correctly - Initialized with accounts`() =
        testScope.runTest {
            every { mockAuthManager.isInitialized } returns true
            every { mockAuthManager.initializationError } returns null
            every { mockAuthManager.accounts } returns listOf(testMsalAccount1)
            repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)

            assertEquals(AuthState.Initialized, repository.authState.value)
            assertEquals(1, repository.accounts.value.size)
            assertEquals(testGenericAccount1, repository.accounts.value[0])
        }

    @Test
    fun `onAuthStateChanged updates state flows`() = testScope.runTest {
        assertTrue(authStateListenerSlot.isCaptured)
        val listener = authStateListenerSlot.captured

        // Init completes successfully
        listener.onAuthStateChanged(isInitialized = true, accounts = emptyList(), error = null)
        advanceUntilIdle()
        assertEquals(AuthState.Initialized, repository.authState.value)
        assertTrue(repository.accounts.value.isEmpty())
        assertFalse(repository.isLoadingAccountAction.value)

        // Account added
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

        // Init Error Occurs
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
    fun `addAccount success updates loading state and emits message`() = testScope.runTest {
        every { mockAuthManager.isInitialized } returns true
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)
        assertEquals(AuthState.Initialized, repository.authState.value)

        // Setup capturing specifically for this test's call
        every {
            mockAuthManager.addAccount(
                mockActivity,
                testScopes,
                capture(addAccountCallbackSlot)
            )
        } just runs

        repository.addAccount(mockActivity, testScopes)
        assertTrue(repository.isLoadingAccountAction.value)
        assertTrue(addAccountCallbackSlot.isCaptured) // Ensure captured before invoking

        val successResult = AddAccountResult.Success(testMsalAccount1)
        addAccountCallbackSlot.captured.invoke(successResult)
        advanceUntilIdle() // Allow state/message flow update

        assertFalse(repository.isLoadingAccountAction.value)
        repository.accountActionMessage.test {
            val message = awaitItem()
            assertNotNull(message)
            assertTrue(message!!.contains(testMsalAccount1.username))
            cancelAndIgnoreRemainingEvents()
        }
        verify { mockAuthManager.addAccount(mockActivity, testScopes, any()) }
    }

    @Test
    fun `addAccount error updates loading state and emits error message`() = testScope.runTest {
        every { mockAuthManager.isInitialized } returns true
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)
        every {
            mockAuthManager.addAccount(
                mockActivity,
                testScopes,
                capture(addAccountCallbackSlot)
            )
        } just runs
        val addError = MsalClientException("add_fail", "Add failed")
        every { mockErrorMapper.mapAuthExceptionToUserMessage(addError) } returns "Mapped Add Error"

        repository.addAccount(mockActivity, testScopes)
        assertTrue(repository.isLoadingAccountAction.value)
        assertTrue(addAccountCallbackSlot.isCaptured)

        val errorResult = AddAccountResult.Error(addError)
        addAccountCallbackSlot.captured.invoke(errorResult)
        advanceUntilIdle()

        assertFalse(repository.isLoadingAccountAction.value)
        repository.accountActionMessage.test {
            assertEquals("Mapped Add Error", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify { mockAuthManager.addAccount(mockActivity, testScopes, any()) }
    }

    @Test
    fun `addAccount cancelled updates loading state and emits cancel message`() =
        testScope.runTest {
            every { mockAuthManager.isInitialized } returns true
            repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)
            every {
                mockAuthManager.addAccount(
                    mockActivity,
                    testScopes,
                    capture(addAccountCallbackSlot)
                )
            } just runs

            repository.addAccount(mockActivity, testScopes)
            assertTrue(repository.isLoadingAccountAction.value)
            assertTrue(addAccountCallbackSlot.isCaptured)

            addAccountCallbackSlot.captured.invoke(AddAccountResult.Cancelled)
            advanceUntilIdle()

            assertFalse(repository.isLoadingAccountAction.value)
            repository.accountActionMessage.test {
                assertEquals("Account addition cancelled.", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            verify { mockAuthManager.addAccount(mockActivity, testScopes, any()) }
        }

    @Test
    fun `removeAccount success updates loading state and emits message`() = testScope.runTest {
        every { mockAuthManager.isInitialized } returns true
        every { mockAuthManager.accounts } returns listOf(testMsalAccount1)
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)
        assertEquals(listOf(testGenericAccount1), repository.accounts.value)

        // Setup capturing specifically for this test's call
        every {
            mockAuthManager.removeAccount(
                testMsalAccount1,
                capture(removeAccountCallbackSlot)
            )
        } just runs

        repository.removeAccount(testGenericAccount1)
        assertTrue(repository.isLoadingAccountAction.value)
        assertTrue(removeAccountCallbackSlot.isCaptured) // Ensure captured

        removeAccountCallbackSlot.captured.invoke(RemoveAccountResult.Success)
        advanceUntilIdle()

        assertFalse(repository.isLoadingAccountAction.value)
        repository.accountActionMessage.test {
            val message = awaitItem()
            assertNotNull(message)
            assertTrue(message!!.contains(testMsalAccount1.username))
            cancelAndIgnoreRemainingEvents()
        }
        verify { mockAuthManager.removeAccount(testMsalAccount1, any()) }
    }

    @Test
    fun `removeAccount error updates loading state and emits error message`() = testScope.runTest {
        every { mockAuthManager.isInitialized } returns true
        every { mockAuthManager.accounts } returns listOf(testMsalAccount1)
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)

        // Setup capturing specifically for this test's call
        every {
            mockAuthManager.removeAccount(
                testMsalAccount1,
                capture(removeAccountCallbackSlot)
            )
        } just runs
        val removeError = MsalServiceException("remove_fail", "Remove failed", null)
        every { mockErrorMapper.mapAuthExceptionToUserMessage(removeError) } returns "Mapped Remove Error"

        repository.removeAccount(testGenericAccount1)
        assertTrue(repository.isLoadingAccountAction.value)
        assertTrue(removeAccountCallbackSlot.isCaptured)

        val errorResult = RemoveAccountResult.Error(removeError)
        removeAccountCallbackSlot.captured.invoke(errorResult)
        advanceUntilIdle()

        assertFalse(repository.isLoadingAccountAction.value)
        repository.accountActionMessage.test {
            assertEquals("Mapped Remove Error", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify { mockAuthManager.removeAccount(testMsalAccount1, any()) }
    }

    @Test
    fun `removeAccount account not found in manager emits message`() = testScope.runTest {
        every { mockAuthManager.isInitialized } returns true
        every { mockAuthManager.accounts } returns emptyList()
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)

        repository.removeAccount(testGenericAccount1)
        advanceUntilIdle()

        assertFalse(repository.isLoadingAccountAction.value)
        repository.accountActionMessage.test {
            assertEquals("Account not found for removal.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { mockAuthManager.removeAccount(any(), any()) }
    }

    @Test
    fun `addOrRemoveAccount does nothing if not initialized`() = testScope.runTest {
        assertEquals(AuthState.Initializing, repository.authState.value)

        val messages = mutableListOf<String?>()
        val collectJob = launch { repository.accountActionMessage.toList(messages) }

        repository.addAccount(mockActivity, testScopes)
        advanceUntilIdle()
        repository.removeAccount(testGenericAccount1)
        advanceUntilIdle()

        assertFalse(repository.isLoadingAccountAction.value)
        assertEquals(
            listOf("Authentication system not ready.", "Authentication system not ready."),
            messages
        )

        verify(exactly = 0) { mockAuthManager.addAccount(any(), any(), any()) }
        verify(exactly = 0) { mockAuthManager.removeAccount(any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun `clearAccountActionMessage emits null`() = testScope.runTest {
        // Arrange: Trigger a message first by simulating an error/cancel callback
        // Need to setup the mock and capture the slot *before* calling the repo method
        every { mockAuthManager.isInitialized } returns true
        repository = MicrosoftAccountRepository(mockAuthManager, testScope, mockErrorMapper)
        every {
            mockAuthManager.addAccount(
                mockActivity,
                testScopes,
                capture(addAccountCallbackSlot)
            )
        } just runs

        repository.addAccount(
            mockActivity,
            testScopes
        ) // Call repo method, which calls manager and captures slot
        assertTrue(addAccountCallbackSlot.isCaptured) // Verify slot captured
        addAccountCallbackSlot.captured.invoke(AddAccountResult.Cancelled) // Now invoke callback
        advanceUntilIdle()


        repository.accountActionMessage.test {
            assertEquals("Account addition cancelled.", awaitItem()) // Consume previous message

            // Act
            repository.clearAccountActionMessage()
            // advanceUntilIdle() // tryEmit should be quick enough

            // Assert
            assertEquals(null, awaitItem()) // Should emit null to clear
            cancelAndIgnoreRemainingEvents()
        }
    }
}