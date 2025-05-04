package net.melisma.backend_microsoft.datasource

// Import specific exceptions instead of wildcard if preferred, but wildcard is fine for tests
import android.app.Activity
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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

@OptIn(ExperimentalCoroutinesApi::class)
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
        // Use relaxUnitFun = true for mocks where we don't care about verifying ALL Unit functions
        mockAuthManager = mockk(relaxUnitFun = true)
        mockActivity = mockk()

        // Initialize the MSAL IAccount mock used in many tests
        msalAccountMock = mockk {
            every { id } returns msAccount.id
            every { username } returns msAccount.username
            every { authority } returns "https://login.microsoftonline.com/common" // Or a more specific authority if needed
        }

        // --- CRITICAL: Mock the 'accounts' property ---
        // This is called early in getAccessToken to find the IAccount.
        // Default behavior: return the mock account. Can be overridden in specific tests.
        every { mockAuthManager.accounts } returns listOf(msalAccountMock)

        tokenProvider = MicrosoftTokenProvider(mockAuthManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Helper Functions for Mocking Callbacks ---

    private fun mockSilentCallbackWith(result: AcquireTokenResult) {
        every {
            mockAuthManager.acquireTokenSilent(
                any(), // Or specific account: msalAccountMock
                any(), // Or specific scopes: testScopes
                any()  // The callback lambda
            )
        } answers { // Use 'answers' to access arguments
            val callback = arg<(AcquireTokenResult) -> Unit>(2) // Callback is the 3rd arg (index 2)
            callback.invoke(result) // Invoke the callback immediately
        }
    }

    private fun mockInteractiveCallbackWith(result: AcquireTokenResult) {
        every {
            mockAuthManager.acquireTokenInteractive(
                any(), // Or specific activity: mockActivity
                any(), // Or specific account: msalAccountMock
                any(), // Or specific scopes: testScopes
                any()  // The callback lambda
            )
        } answers {
            val callback = arg<(AcquireTokenResult) -> Unit>(3) // Callback is the 4th arg (index 3)
            callback.invoke(result) // Invoke the callback immediately
        }
    }

    // --- Test Cases ---

    @Test
    fun `getAccessToken fails for non MS account type`() = runTest {
        // Act
        val result = tokenProvider.getAccessToken(googleAccount, testScopes, null)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(
            "Account provider type is not MS: GOOG",
            result.exceptionOrNull()?.message
        )

        // Verify no auth manager calls were made
        verify { mockAuthManager wasNot Called }
        // More specific verification:
        // verify(exactly = 0) { mockAuthManager.acquireTokenSilent(any(), any(), any()) }
        // verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any(), any()) }
    }

    @Test
    fun `getAccessToken fails if MSAL account not found in manager`() = runTest {
        // Arrange: Override the default mock for 'accounts' property for this test
        every { mockAuthManager.accounts } returns emptyList()
        val unknownMsAccount = Account("unknown_id", "unknown@test.com", "MS")

        // Act
        val result = tokenProvider.getAccessToken(unknownMsAccount, testScopes, null)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(
            "MSAL IAccount not found for generic Account ID: unknown_id",
            result.exceptionOrNull()?.message
        )

        // Verify no token acquisition calls were made (only 'accounts' property was accessed)
        verify(exactly = 1) { mockAuthManager.accounts } // Verify property access
        verify(exactly = 0) { mockAuthManager.acquireTokenSilent(any(), any(), any()) }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any(), any()) }
    }

    @Test
    fun `getAccessToken succeeds with silent acquisition`() = runTest {
        // Arrange: Configure the mock to immediately call back with success
        val successAuthResult: IAuthenticationResult = mockk {
            every { accessToken } returns fakeAccessTokenSilent
            every { account } returns msalAccountMock // Include account in result if needed elsewhere
        }
        mockSilentCallbackWith(AcquireTokenResult.Success(successAuthResult))

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, null)

        // Assert
        assertTrue(result.isSuccess)
        assertEquals(fakeAccessTokenSilent, result.getOrNull())

        // Verify silent was called, interactive was not
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
            // Arrange: Mock silent path -> UiRequired, mock interactive path -> Success
            mockSilentCallbackWith(AcquireTokenResult.UiRequired)

            val interactiveSuccessResult: IAuthenticationResult = mockk {
                every { accessToken } returns fakeAccessTokenInteractive
                every { account } returns msalAccountMock
            }
            mockInteractiveCallbackWith(AcquireTokenResult.Success(interactiveSuccessResult))

            // Act
            val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

            // Assert
            assertTrue(result.isSuccess)
            assertEquals(fakeAccessTokenInteractive, result.getOrNull())

            // Verify the sequence of calls
            verifyOrder {
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
            // Arrange: Mock silent path -> UiRequired
            mockSilentCallbackWith(AcquireTokenResult.UiRequired)

            // Act
            val result =
                tokenProvider.getAccessToken(msAccount, testScopes, null) // Pass null Activity

            // Assert
            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(exception is MsalUiRequiredException)
            // Check the specific message added by the Provider
            assertEquals(
                "UI interaction required, but no Activity provided.",
                exception?.message
            )


            // Verify silent was called, interactive was NOT called
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
            }
        }

    @Test
    fun `getAccessToken fails when silent fails (Error)`() = runTest {
        // Arrange: Mock silent path -> Error
        val silentError = MsalServiceException("SILENT_CODE", "Silent failure message", null)
        mockSilentCallbackWith(AcquireTokenResult.Error(silentError))

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

        // Assert
        assertTrue(result.isFailure)
        assertEquals(silentError, result.exceptionOrNull())

        // Verify silent was called, interactive was not
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
        // Arrange: Mock silent path -> UiRequired, mock interactive path -> Error
        mockSilentCallbackWith(AcquireTokenResult.UiRequired)

        val interactiveError =
            MsalClientException("INTERACTIVE_CODE", "Interactive failure message")
        mockInteractiveCallbackWith(AcquireTokenResult.Error(interactiveError))

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

        // Assert
        assertTrue(result.isFailure)
        assertEquals(interactiveError, result.exceptionOrNull())

        // Verify the sequence
        verifyOrder {
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
        // Arrange: Mock silent path -> UiRequired, mock interactive path -> Cancelled
        mockSilentCallbackWith(AcquireTokenResult.UiRequired)
        mockInteractiveCallbackWith(AcquireTokenResult.Cancelled)

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MsalUserCancelException)

        // Verify the sequence
        verifyOrder {
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
        // Arrange: Mock silent path -> NotInitialized
        mockSilentCallbackWith(AcquireTokenResult.NotInitialized)

        // Act
        val result = tokenProvider.getAccessToken(msAccount, testScopes, mockActivity)

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is MsalClientException)
        // Check the specific message added by the Provider
        assertEquals(
            "MSAL not initialized.",
            exception?.message
        )
        // Check error code if needed (Provider uses UNKNOWN_ERROR here)
        assertEquals(
            MsalClientException.UNKNOWN_ERROR,
            (exception as MsalClientException).errorCode
        )

        // Verify silent was called, interactive was not
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