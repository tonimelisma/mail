package net.melisma.backend_microsoft.datasource

import android.app.Activity
import android.util.Log // Keep Log import
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic // Keep MockK Static imports
import io.mockk.unmockkAll
import io.mockk.unmockkStatic // Keep MockK Static imports
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.melisma.backend_microsoft.auth.AcquireTokenResult
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.core_data.model.Account
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Removed Robolectric imports

@OptIn(ExperimentalCoroutinesApi::class)
// Removed @RunWith and @Config annotations
class MicrosoftTokenProviderTest {

    // --- Mocks ---
    private lateinit var mockAuthManager: MicrosoftAuthManager
    private lateinit var mockActivity: Activity

    // --- Class Under Test ---
    private lateinit var tokenProvider: MicrosoftTokenProvider

    // --- Test Data ---
    private val msAccount = Account("ms_id_123", "test@outlook.com", "MS")
    private val googleAccount = Account("goog_id_456", "test@gmail.com", "GOOG")
    private val testScopes = listOf("User.Read", "Mail.Read")
    private lateinit var msalAccountMock: IAccount // Initialized in setUp

    // Test constants for tokens
    private val fakeAccessTokenSilent = "fake-access-token-silent"
    private val fakeAccessTokenInteractive = "fake-access-token-interactive"

    @Before
    fun setUp() {
        mockAuthManager = mockk(relaxUnitFun = true)
        mockActivity = mockk()

        msalAccountMock = mockk {
            every { id } returns msAccount.id
            every { username } returns msAccount.username
            every { authority } returns "https://login.microsoftonline.com/common"
        }
        every { mockAuthManager.accounts } returns listOf(msalAccountMock) // Default setup has one account

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

        tokenProvider = MicrosoftTokenProvider(mockAuthManager)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class) // Keep this
        unmockkAll()
    }

    // --- Helper Functions --- (Keep existing helpers)
    private fun mockSilentCallbackWith(result: AcquireTokenResult) {
        every {
            mockAuthManager.acquireTokenSilent(any(), any(), any())
        } answers {
            val callback = arg<(AcquireTokenResult) -> Unit>(2)
            callback.invoke(result)
        }
    }

    private fun mockInteractiveCallbackWith(result: AcquireTokenResult) {
        every {
            mockAuthManager.acquireTokenInteractive(any(), any(), any(), any())
        } answers {
            val callback = arg<(AcquireTokenResult) -> Unit>(3)
            callback.invoke(result)
        }
    }

    // --- Test Cases ---
    @Test
    fun `getAccessToken fails for non MS account type`() = runTest {
        val result = tokenProvider.getAccessToken(googleAccount, testScopes, null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Account provider type is not MS: GOOG", result.exceptionOrNull()?.message)
        verify { mockAuthManager wasNot Called } // Verify manager wasn't touched
    }

    @Test
    fun `getAccessToken fails if MSAL account not found in manager`() = runTest {
        // Arrange: Override setup to have NO accounts in the manager
        every { mockAuthManager.accounts } returns emptyList()
        val unknownMsAccount = Account("unknown_id", "unknown@test.com", "MS")

        // Act
        val result = tokenProvider.getAccessToken(unknownMsAccount, testScopes, null)

        // Assert Result
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is IllegalStateException)
        // Check message includes the known accounts part (which is empty here)
        assertEquals(
            "MSAL IAccount not found for generic Account ID: unknown_id. Known accounts: ",
            exception?.message
        )

        // Assert Interactions
        // *** APPLY FIX: Expect exactly 2 calls to accounts getter ***
        verify(exactly = 2) { mockAuthManager.accounts }
        // Verify other manager methods were NOT called
        verify(exactly = 0) { mockAuthManager.acquireTokenSilent(any(), any(), any()) }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any(), any()) }
    }

    @Test
    fun `getAccessToken succeeds with silent acquisition`() = runTest {
        // Arrange
        val successAuthResult: IAuthenticationResult = mockk {
            every { accessToken } returns fakeAccessTokenSilent
            every { account } returns msalAccountMock // Ensure result links back to the correct account
        }
        mockSilentCallbackWith(AcquireTokenResult.Success(successAuthResult))

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, null)

        // Assert Result
        assertTrue(result.isSuccess)
        assertEquals(fakeAccessTokenSilent, result.getOrNull())

        // Assert Interactions
        verify(exactly = 1) { mockAuthManager.accounts } // Called once by getMsalAccountById
        verify(exactly = 1) {
            mockAuthManager.acquireTokenSilent(
                msalAccountMock,
                testScopes,
                any()
            )
        }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any(), any()) }
    }

    @Test
    fun `getAccessToken succeeds with interactive acquisition when silent fails (UI Required)`() =
        runTest {
            // Arrange
            mockSilentCallbackWith(AcquireTokenResult.UiRequired)
            val interactiveSuccessResult: IAuthenticationResult = mockk {
                every { accessToken } returns fakeAccessTokenInteractive
                every { account } returns msalAccountMock
            }
            mockInteractiveCallbackWith(AcquireTokenResult.Success(interactiveSuccessResult))

            // Act
            val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

            // Assert Result
            assertTrue(result.isSuccess)
            assertEquals(fakeAccessTokenInteractive, result.getOrNull())

            // Assert Interactions (Order matters here)
            verifyOrder {
                mockAuthManager.accounts // From getMsalAccountById
                mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes, any())
                mockAuthManager.acquireTokenInteractive(
                    mockActivity,
                    msalAccountMock,
                    testScopes,
                    any()
                )
            }
        }

    @Test
    fun `getAccessToken fails when silent fails (UI Required) and no activity provided`() =
        runTest {
            // Arrange
            mockSilentCallbackWith(AcquireTokenResult.UiRequired)

            // Act
            val result = tokenProvider.getAccessToken(msAccount, testScopes, null) // No activity

            // Assert Result
            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(exception is MsalUiRequiredException) // Should be the specific exception type
            assertEquals("UI interaction required, but no Activity provided.", exception?.message)

            // Assert Interactions
            verify(exactly = 1) { mockAuthManager.accounts } // From getMsalAccountById
            verify(exactly = 1) {
                mockAuthManager.acquireTokenSilent(
                    msalAccountMock,
                    testScopes,
                    any()
                )
            }
            verify(exactly = 0) {
                mockAuthManager.acquireTokenInteractive(
                    any(),
                    any(),
                    any(),
                    any()
                )
            } // Interactive never called
        }

    @Test
    fun `getAccessToken fails when silent fails (Error)`() = runTest {
        // Arrange
        val silentError = MsalServiceException("SILENT_CODE", "Silent failure message", null)
        mockSilentCallbackWith(AcquireTokenResult.Error(silentError))

        // Act
        val result = tokenProvider.getAccessToken(
            msAccount,
            testScopes,
            mockActivity
        ) // Activity present but not needed

        // Assert Result
        assertTrue(result.isFailure)
        assertEquals(silentError, result.exceptionOrNull()) // Expect the original exception

        // Assert Interactions
        verify(exactly = 1) { mockAuthManager.accounts } // From getMsalAccountById
        verify(exactly = 1) {
            mockAuthManager.acquireTokenSilent(
                msalAccountMock,
                testScopes,
                any()
            )
        }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any(), any()) }
    }

    @Test
    fun `getAccessToken fails when interactive fails`() = runTest {
        // Arrange
        mockSilentCallbackWith(AcquireTokenResult.UiRequired)
        val interactiveError =
            MsalClientException("INTERACTIVE_CODE", "Interactive failure message")
        mockInteractiveCallbackWith(AcquireTokenResult.Error(interactiveError))

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

        // Assert Result
        assertTrue(result.isFailure)
        assertEquals(interactiveError, result.exceptionOrNull()) // Expect the interactive error

        // Assert Interactions (Order matters)
        verifyOrder {
            mockAuthManager.accounts // From getMsalAccountById
            mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes, any())
            mockAuthManager.acquireTokenInteractive(
                mockActivity,
                msalAccountMock,
                testScopes,
                any()
            )
        }
    }

    @Test
    fun `getAccessToken fails when interactive is cancelled`() = runTest {
        // Arrange
        mockSilentCallbackWith(AcquireTokenResult.UiRequired)
        mockInteractiveCallbackWith(AcquireTokenResult.Cancelled)

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

        // Assert Result
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MsalUserCancelException) // Expect specific cancellation exception

        // Assert Interactions (Order matters)
        verifyOrder {
            mockAuthManager.accounts // From getMsalAccountById
            mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes, any())
            mockAuthManager.acquireTokenInteractive(
                mockActivity,
                msalAccountMock,
                testScopes,
                any()
            )
        }
    }

    @Test
    fun `getAccessToken fails when silent indicates NotInitialized`() = runTest {
        // Arrange
        mockSilentCallbackWith(AcquireTokenResult.NotInitialized)

        // Act
        val result = tokenProvider.getAccessToken(
            msAccount,
            testScopes,
            mockActivity
        ) // Activity present but not needed

        // Assert Result
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is MsalClientException)
        assertEquals("MSAL not initialized.", exception?.message)
        assertEquals(
            MsalClientException.UNKNOWN_ERROR,
            (exception as MsalClientException).errorCode
        ) // Check specific error code if possible

        // Assert Interactions
        verify(exactly = 1) { mockAuthManager.accounts } // From getMsalAccountById
        verify(exactly = 1) {
            mockAuthManager.acquireTokenSilent(
                msalAccountMock,
                testScopes,
                any()
            )
        }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any(), any()) }
    }
}