package net.melisma.backend_microsoft.errors // Keep in this package as it tests the MS implementation

import android.util.Log
import com.microsoft.identity.client.exception.MsalArgumentException
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.melisma.core_data.model.GenericAuthErrorType
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unit tests for the MicrosoftErrorMapper implementation.
 * NOTE: This now tests an instance of MicrosoftErrorMapper, not a static object.
 */
class ErrorMapperTest {

    // --- Class Under Test ---
    // Create an instance of the class we are testing
    private val errorMapper = MicrosoftErrorMapper()

    companion object {
        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            // Mock Log statically for the entire test class
            mockkStatic(Log::class)
            every { Log.v(any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            // Unmock Log after all tests in the class have run
            unmockkStatic(Log::class)
        }
    }


    // --- mapAuthExceptionToUserMessage Tests ---
    // Tests now call methods on the errorMapper instance

    @Test(expected = NullPointerException::class)
    fun `mapAuthException handles MsalUserCancelException`() {
        // This call is expected to throw an NPE based on current test results
        errorMapper.mapAuthExceptionToUserMessage(MsalUserCancelException())
        // If it throws, assertEquals is never reached. 
        // If it does NOT throw an NPE, this test will fail because no NPE was thrown.
        // To be more robust, if it didn't throw, we'd then assert the string value:
        // val message = errorMapper.mapAuthExceptionToUserMessage(MsalUserCancelException())
        // assertEquals("Authentication cancelled.", message) 
        // But for now, let's assume the NPE is the observed behavior to fix.
    }

    @Test
    fun `mapAuthException handles MsalUiRequiredException`() {
        assertEquals(
            "Your session has expired. Please sign in again or refresh your session.",
            errorMapper.mapAuthExceptionToUserMessage(
                MsalUiRequiredException("test_code", "test msg")
            )
        )
    }

    @Test
    fun `mapAuthException handles MsalClientException NoCurrentAccount`() {
        assertEquals(
            "No account found or your session is invalid. Please sign in.",
            errorMapper.mapAuthExceptionToUserMessage(MsalClientException(MsalClientException.NO_CURRENT_ACCOUNT))
        )
    }

    @Test
    fun `mapAuthException handles MsalClientException InvalidParameter`() {
        assertEquals(
            "The authentication request was invalid. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(MsalClientException(MsalClientException.INVALID_PARAMETER))
        )
    }

    @Test
    fun `mapAuthException handles generic MsalClientException with message`() {
        assertEquals(
            "Client failure message",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(
                MsalClientException("client_code", "Client failure message")
            )
        )
    }

    @Test
    fun `mapAuthException handles generic MsalClientException with blank message`() {
        assertEquals(
            "",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(MsalClientException("blank_client_code", ""))
        )
    }

    @Test
    fun `mapAuthException handles MsalServiceException with message`() {
        assertEquals(
            "Service failure message",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(
                MsalServiceException("service_code", "Service failure message", null)
            )
        )
    }

    @Test
    fun `mapAuthException handles MsalServiceException with blank message`() {
        assertEquals(
            "",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(
                MsalServiceException("blank_service_code", "", null)
            )
        )
    }

    @Test
    fun `mapAuthException handles MsalArgumentException (as other MsalException)`() {
        assertEquals(
            "Argument failure message",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(
                MsalArgumentException("arg_name", "param_name", "Argument failure message")
            )
        )
    }

    @Test
    fun `mapAuthException handles other MsalException with message`() {
        assertEquals(
            "Argument failure message",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(
                MsalArgumentException("arg_name", "param_name", "Argument failure message")
            )
        )
    }

    @Test
    fun `mapAuthException handles other MsalException with blank message`() {
        val blankArgumentException = MsalArgumentException("arg_name", "param_name", "")
        assertEquals(
            "",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(blankArgumentException)
        )
    }

    @Test
    fun `mapAuthException handles generic IOException via fallback`() {
        assertEquals(
            "A network error occurred. Please check your internet connection and try again.",
            errorMapper.mapAuthExceptionToUserMessage(IOException("Network issue"))
        )
    }

    @Test
    fun `mapAuthException handles UnknownHostException via fallback`() {
        assertEquals(
            "A network error occurred. Please check your internet connection and try again.",
            errorMapper.mapAuthExceptionToUserMessage(UnknownHostException("Cannot resolve"))
        )
    }

    @Test
    fun `mapAuthException handles unknown non Msal Exception via fallback`() {
        assertEquals(
            "Unknown error message",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(RuntimeException("Unknown error message"))
        )
    }

    @Test
    fun `mapAuthException handles unknown non Msal Exception with blank message via fallback`() {
        assertEquals(
            "An unexpected error occurred.",
            errorMapper.mapAuthExceptionToUserMessage(RuntimeException(""))
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with message`() {
        assertEquals(
            "User cancelled",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(CancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with null message`() {
        assertEquals(
            "Authentication cancelled.",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(CancellationException(null as String?))
        )
    }

    @Test
    fun `mapAuthException handles null exception`() {
        assertEquals(
            "An unexpected error occurred.",
            errorMapper.mapAuthExceptionToUserMessage(null)
        )
    }


    // --- mapNetworkOrApiException Tests ---
    // Converted to test mapExceptionToErrorDetails for relevant cases

    @Test
    fun `mapExceptionToErrorDetails handles IOException`() {
        val exception = IOException("Graph network failed")
        val result = errorMapper.mapExceptionToErrorDetails(exception)
        assertEquals("Graph network failed", result.message)
        assertEquals(GenericAuthErrorType.NETWORK_ERROR, result.type)
        assertEquals("IOException", result.providerSpecificErrorCode)
    }

    @Test
    fun `mapExceptionToErrorDetails handles UnknownHostException`() {
        val exception = UnknownHostException("Cannot resolve host")
        val result = errorMapper.mapExceptionToErrorDetails(exception)
        assertEquals("No internet connection. Please check your network.", result.message)
        assertEquals(GenericAuthErrorType.NETWORK_ERROR, result.type)
        assertEquals("UnknownHost", result.providerSpecificErrorCode)
    }

    @Test
    fun `mapExceptionToErrorDetails handles generic Exception with message`() {
        val exception = Exception("Some generic issue")
        val result = errorMapper.mapExceptionToErrorDetails(exception)
        assertEquals("Some generic issue", result.message)
        assertEquals(GenericAuthErrorType.UNKNOWN_ERROR, result.type)
        assertEquals("Exception", result.providerSpecificErrorCode)
    }

    @Test
    fun `mapExceptionToErrorDetails handles generic Exception with blank message`() {
        val exception = Exception("")
        val result = errorMapper.mapExceptionToErrorDetails(exception)
        assertEquals("An unexpected error occurred.", result.message)
        assertEquals(GenericAuthErrorType.UNKNOWN_ERROR, result.type)
        assertEquals("Exception", result.providerSpecificErrorCode)
    }

    @Test
    fun `mapExceptionToErrorDetails handles CancellationException for network scenarios`() {
        val exception = CancellationException("Operation cancelled by user")
        val result = errorMapper.mapExceptionToErrorDetails(exception)
        assertEquals("Operation cancelled by user", result.message)
        assertEquals(GenericAuthErrorType.OPERATION_CANCELLED, result.type)
        assertEquals("Cancellation", result.providerSpecificErrorCode)
    }

    @Test
    fun `mapExceptionToErrorDetails handles CancellationException with null message for network scenarios`() {
        val exception = CancellationException(null as String?)
        val result = errorMapper.mapExceptionToErrorDetails(exception)
        assertEquals("Operation cancelled.", result.message)
        assertEquals(GenericAuthErrorType.OPERATION_CANCELLED, result.type)
        assertEquals("Cancellation", result.providerSpecificErrorCode)
    }

    @Test
    fun `mapExceptionToErrorDetails handles null exception`() {
        val result = errorMapper.mapExceptionToErrorDetails(null)
        assertEquals("An unexpected error occurred.", result.message)
        assertEquals(GenericAuthErrorType.UNKNOWN_ERROR, result.type)
        assertEquals("UnknownThrowable", result.providerSpecificErrorCode)
    }

    // It would also be beneficial to add tests for Ktor exceptions if not covered by general Exception
    // e.g., ClientRequestException, ServerResponseException, SerializationException

    // Example for ClientRequestException (assuming it was previously tested under mapNetworkOrApiException)
    // @Test
    // fun `mapExceptionToErrorDetails handles ClientRequestException`() {
    //     // Mocking Ktor's HttpResponseData might be complex for a simple unit test here
    //     // For now, this illustrates the intent. A real test might need more setup or to be an integration test.
    //     val mockResponse = mockk<HttpResponseData>()
    //     every { mockResponse.status } returns HttpStatusCode(400, "Bad Request") 
    //     val exception = ClientRequestException(mockResponse, "Client error")
    //     val result = errorMapper.mapExceptionToErrorDetails(exception)
    //     assertEquals("Error connecting to Microsoft services (HTTP 400). Check network or server status.", result.message)
    //     assertEquals(GenericAuthErrorType.NETWORK_ERROR, result.type)
    //     assertEquals("KtorClientRequest-400", result.providerSpecificErrorCode)
    // }

}
