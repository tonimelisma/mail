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

    @Test
    fun `mapAuthException handles MsalUserCancelException`() {
        assertEquals(
            "Authentication cancelled.",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(MsalUserCancelException())
        )
    }

    @Test
    fun `mapAuthException handles MsalUiRequiredException`() {
        assertEquals(
            "Session expired. Please retry or sign out/in.",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(
                MsalUiRequiredException("test_code", "test msg")
            )
        )
    }

    @Test
    fun `mapAuthException handles MsalClientException NoCurrentAccount`() {
        assertEquals(
            "Account not found or session invalid.",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(MsalClientException(MsalClientException.NO_CURRENT_ACCOUNT))
        )
    }

    @Test
    fun `mapAuthException handles MsalClientException InvalidParameter`() {
        assertEquals(
            "Authentication request is invalid.",
            // Call method on the instance
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
            "Authentication client error (blank_client_code)",
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
            "Authentication service error (blank_service_code)",
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
        val actualErrorCode = blankArgumentException.errorCode ?: "UNKNOWN"
        assertEquals(
            "Authentication failed ($actualErrorCode)",
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(blankArgumentException)
        )
    }

    @Test
    fun `mapAuthException handles generic IOException via fallback`() {
        assertEquals(
            "Network error occurred", // mapAuthException falls back to mapNetworkOrApiException
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(IOException("Network issue"))
        )
    }

    @Test
    fun `mapAuthException handles UnknownHostException via fallback`() {
        assertEquals(
            "No internet connection", // mapAuthException falls back to mapNetworkOrApiException
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(UnknownHostException("Cannot resolve"))
        )
    }

    @Test
    fun `mapAuthException handles unknown non Msal Exception via fallback`() {
        assertEquals(
            "Unknown error message", // Falls back to network mapping, then uses message
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(RuntimeException("Unknown error message"))
        )
    }

    @Test
    fun `mapAuthException handles unknown non Msal Exception with blank message via fallback`() {
        assertEquals(
            "An unknown authentication error occurred", // Falls back to network mapping (unknown), then auth fallback
            // Call method on the instance
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
            "An unknown authentication error occurred", // Falls back to network mapping (unknown), then auth fallback
            // Call method on the instance
            errorMapper.mapAuthExceptionToUserMessage(null)
        )
    }


    // --- mapNetworkOrApiException Tests ---
    // Tests now call methods on the errorMapper instance

    @Test
    fun `mapNetworkOrApiException handles IOException`() {
        assertEquals(
            "Network error occurred",
            // Call method on the instance
            errorMapper.mapNetworkOrApiException(IOException("Graph network failed"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles UnknownHostException`() {
        assertEquals(
            "No internet connection",
            // Call method on the instance
            errorMapper.mapNetworkOrApiException(UnknownHostException("Cannot reach graph.microsoft.com"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles generic Exception with message`() {
        assertEquals(
            "Graph processing failed",
            // Call method on the instance
            errorMapper.mapNetworkOrApiException(RuntimeException("Graph processing failed"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles generic Exception with blank message`() {
        assertEquals(
            "An unknown network or API error occurred",
            // Call method on the instance
            errorMapper.mapNetworkOrApiException(RuntimeException(""))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles CancellationException with message`() {
        assertEquals(
            "User cancelled",
            // Call method on the instance
            errorMapper.mapNetworkOrApiException(CancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles CancellationException with null message`() {
        assertEquals(
            "Operation cancelled.",
            // Call method on the instance
            errorMapper.mapNetworkOrApiException(CancellationException(null as String?))
        )
    }


    @Test
    fun `mapNetworkOrApiException handles null exception`() {
        assertEquals(
            "An unknown network or API error occurred",
            // Call method on the instance
            errorMapper.mapNetworkOrApiException(null)
        )
    }
}
