// File: backend-microsoft/src/test/java/net/melisma/backend_microsoft/datasource/MicrosoftTokenProviderTest.kt
package net.melisma.backend_microsoft.datasource

import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.melisma.backend_microsoft.auth.AcquireTokenResult
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.core_data.model.Account
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// kotlinx.coroutines.flow.first is already imported in MicrosoftTokenProvider.kt, not needed here for tests
// if the methods under test are suspend and internally use .first()

@OptIn(ExperimentalCoroutinesApi::class)
class MicrosoftTokenProviderTest {

    private lateinit var mockAuthManager: MicrosoftAuthManager
    private lateinit var mockActivity: Activity
    private lateinit var tokenProvider: MicrosoftTokenProvider

    private val msAccountModel = Account("ms_id_123", "test@outlook.com", "MS")
    private val googleAccountModel = Account("goog_id_456", "test@gmail.com", "GOOG")
    private val testScopes = listOf("User.Read", "Mail.Read")

    private lateinit var msalAccountMock: IAccount
    private val fakeAccessTokenSilent = "fake-access-token-silent"
    private val fakeAccessTokenInteractive = "fake-access-token-interactive"

    @Before
    fun setUp() {
        mockAuthManager = mockk(relaxUnitFun = true)
        mockActivity = mockk()

        msalAccountMock = mockk {
            every { id } returns msAccountModel.id
            every { username } returns msAccountModel.username
            every { authority } returns "https://login.microsoftonline.com/common"
        }
        every { mockAuthManager.accounts } returns listOf(msalAccountMock)

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        tokenProvider = MicrosoftTokenProvider(mockAuthManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // Updated helper to mock the Flow returned by acquireTokenSilent
    private fun mockSilentFlowWith(result: AcquireTokenResult) {
        // MicrosoftAuthManager.acquireTokenSilent now takes 2 arguments (IAccount?, List<String>)
        every { mockAuthManager.acquireTokenSilent(any(), any()) } returns flowOf(result)
    }

    // Updated helper to mock the Flow returned by acquireTokenInteractive
    private fun mockInteractiveFlowWith(result: AcquireTokenResult) {
        // MicrosoftAuthManager.acquireTokenInteractive now takes 3 arguments (Activity, IAccount?, List<String>)
        every {
            mockAuthManager.acquireTokenInteractive(
                any(),
                any(),
                any()
            )
        } returns flowOf(result)
    }

    @Test
    fun `getAccessToken fails for non MS account type`() = runTest {
        val result = tokenProvider.getAccessToken(googleAccountModel, testScopes, null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Account provider type is not MS: GOOG", result.exceptionOrNull()?.message)
        verify { mockAuthManager wasNot Called }
    }

    @Test
    fun `getAccessToken fails if MSAL account not found in manager`() = runTest {
        // Mock accounts to return empty list consistently
        every { mockAuthManager.accounts } returns emptyList()
        val unknownMsAccount = Account("unknown_id", "unknown@test.com", "MS")

        val result = tokenProvider.getAccessToken(unknownMsAccount, testScopes, null)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(
            "Exception should be IllegalStateException, but was ${exception?.javaClass?.simpleName}",
            exception is IllegalStateException
        )

        // Be more flexible with the error message format
        val expectedMessagePrefix = "MSAL IAccount not found for generic Account ID: unknown_id"
        assertTrue(
            "Error message should start with: $expectedMessagePrefix but was: ${exception?.message}",
            exception?.message?.startsWith(expectedMessagePrefix) == true
        )
            
        verify(exactly = 1) { mockAuthManager.accounts }
        verify(exactly = 0) { mockAuthManager.acquireTokenSilent(any(), any()) }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any()) }
    }

    @Test
    fun `getAccessToken succeeds with silent acquisition`() = runTest {
        val successAuthResult: IAuthenticationResult = mockk {
            every { accessToken } returns fakeAccessTokenSilent
            // every { account } returns msalAccountMock // Not strictly needed for this mock
        }
        mockSilentFlowWith(AcquireTokenResult.Success(successAuthResult))

        val result = tokenProvider.getAccessToken(msAccountModel, testScopes, null)

        assertTrue(result.isSuccess)
        assertEquals(fakeAccessTokenSilent, result.getOrNull())
        verify(exactly = 1) { mockAuthManager.accounts }
        verify(exactly = 1) { mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes) }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any()) }
    }

    @Test
    fun `getAccessToken succeeds with interactive acquisition when silent fails (UI Required)`() =
        runTest {
            mockSilentFlowWith(AcquireTokenResult.UiRequired)
            val interactiveSuccessResult: IAuthenticationResult = mockk {
                every { accessToken } returns fakeAccessTokenInteractive
                // every { account } returns msalAccountMock
            }
            mockInteractiveFlowWith(AcquireTokenResult.Success(interactiveSuccessResult))

            val result = tokenProvider.getAccessToken(msAccountModel, testScopes, mockActivity)

            assertTrue(result.isSuccess)
            assertEquals(fakeAccessTokenInteractive, result.getOrNull())
            verifyOrder {
                mockAuthManager.accounts
                mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes)
                mockAuthManager.acquireTokenInteractive(mockActivity, msalAccountMock, testScopes)
            }
        }

    @Test
    fun `getAccessToken fails when silent fails (UI Required) and no activity provided`() =
        runTest {
            mockSilentFlowWith(AcquireTokenResult.UiRequired)

            val result = tokenProvider.getAccessToken(msAccountModel, testScopes, null)

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(exception is MsalUiRequiredException)
            assertEquals("UI interaction required, but no Activity provided.", exception?.message)
            verify(exactly = 1) { mockAuthManager.accounts }
            verify(exactly = 1) { mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes) }
            verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any()) }
        }

    @Test
    fun `getAccessToken fails when silent fails (Error)`() = runTest {
        val silentError = MsalServiceException("SILENT_CODE", "Silent failure message", null)
        mockSilentFlowWith(AcquireTokenResult.Error(silentError))

        val result = tokenProvider.getAccessToken(msAccountModel, testScopes, mockActivity)

        assertTrue(result.isFailure)
        assertEquals(silentError, result.exceptionOrNull())
        verify(exactly = 1) { mockAuthManager.accounts }
        verify(exactly = 1) { mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes) }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any()) }
    }

    @Test
    fun `getAccessToken fails when interactive fails`() = runTest {
        mockSilentFlowWith(AcquireTokenResult.UiRequired)
        val interactiveError =
            MsalClientException("INTERACTIVE_CODE", "Interactive failure message")
        mockInteractiveFlowWith(AcquireTokenResult.Error(interactiveError))

        val result = tokenProvider.getAccessToken(msAccountModel, testScopes, mockActivity)

        assertTrue(result.isFailure)
        assertEquals(interactiveError, result.exceptionOrNull())
        verifyOrder {
            mockAuthManager.accounts
            mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes)
            mockAuthManager.acquireTokenInteractive(mockActivity, msalAccountMock, testScopes)
        }
    }

    @Test
    fun `getAccessToken fails when interactive is cancelled`() = runTest {
        mockSilentFlowWith(AcquireTokenResult.UiRequired)
        mockInteractiveFlowWith(AcquireTokenResult.Cancelled)

        val result = tokenProvider.getAccessToken(msAccountModel, testScopes, mockActivity)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MsalUserCancelException)
        verifyOrder {
            mockAuthManager.accounts
            mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes)
            mockAuthManager.acquireTokenInteractive(mockActivity, msalAccountMock, testScopes)
        }
    }

    @Test
    fun `getAccessToken fails when silent indicates NotInitialized`() = runTest {
        mockSilentFlowWith(AcquireTokenResult.NotInitialized)

        val result = tokenProvider.getAccessToken(msAccountModel, testScopes, mockActivity)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is MsalClientException)
        assertEquals("MSAL not initialized.", exception?.message)
        verify(exactly = 1) { mockAuthManager.accounts }
        verify(exactly = 1) { mockAuthManager.acquireTokenSilent(msalAccountMock, testScopes) }
        verify(exactly = 0) { mockAuthManager.acquireTokenInteractive(any(), any(), any()) }
    }
}
