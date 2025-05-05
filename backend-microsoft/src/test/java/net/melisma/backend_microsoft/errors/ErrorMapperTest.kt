package net.melisma.backend_microsoft.errors

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

class ErrorMapperTest {

    // Test target is the ErrorMapper object

    companion object {
        @BeforeClass
        @JvmStatic // Necessary for JUnit 4 static methods
        fun beforeClass() {
            // Mock Log statically for the entire test class
            mockkStatic(Log::class)
            every { Log.v(any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0 // Handle String overload
            every { Log.w(any(), any<String>(), any()) } returns 0 // Handle Throwable overload
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
        }

        @AfterClass
        @JvmStatic // Necessary for JUnit 4 static methods
        fun afterClass() {
            // Unmock Log after all tests in the class have run
            unmockkStatic(Log::class)
        }
    }


    // --- mapAuthExceptionToUserMessage Tests ---
    // ... (Keep passing tests unchanged) ...

    @Test
    fun `mapAuthException handles MsalUserCancelException`() {
        assertEquals(
            "Authentication cancelled.",
            ErrorMapper.mapAuthExceptionToUserMessage(MsalUserCancelException())
        )
    }

    @Test
    fun `mapAuthException handles MsalUiRequiredException`() {
        // We can use the public constructor now, providing necessary (even if dummy) codes
        assertEquals(
            "Session expired. Please retry or sign out/in.",
            ErrorMapper.mapAuthExceptionToUserMessage(
                MsalUiRequiredException(
                    "test_code",
                    "test msg"
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles MsalClientException NoCurrentAccount`() {
        // Use public constructor with known error code constant
        assertEquals(
            "Account not found or session invalid.",
            ErrorMapper.mapAuthExceptionToUserMessage(MsalClientException(MsalClientException.NO_CURRENT_ACCOUNT))
        )
    }

    @Test
    fun `mapAuthException handles MsalClientException InvalidParameter`() {
        assertEquals(
            "Authentication request is invalid.",
            ErrorMapper.mapAuthExceptionToUserMessage(MsalClientException(MsalClientException.INVALID_PARAMETER))
        )
    }

    @Test
    fun `mapAuthException handles generic MsalClientException with message`() {
        assertEquals(
            "Client failure message",
            ErrorMapper.mapAuthExceptionToUserMessage(
                MsalClientException(
                    "client_code",
                    "Client failure message"
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles generic MsalClientException with blank message`() {
        assertEquals(
            "Authentication client error (blank_client_code)",
            ErrorMapper.mapAuthExceptionToUserMessage(MsalClientException("blank_client_code", ""))
        )
    }

    @Test
    fun `mapAuthException handles MsalServiceException with message`() {
        // Use public constructor
        assertEquals(
            "Service failure message",
            ErrorMapper.mapAuthExceptionToUserMessage(
                MsalServiceException(
                    "service_code",
                    "Service failure message",
                    null
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles MsalServiceException with blank message`() {
        assertEquals(
            "Authentication service error (blank_service_code)",
            ErrorMapper.mapAuthExceptionToUserMessage(
                MsalServiceException(
                    "blank_service_code",
                    "",
                    null
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles MsalArgumentException (as other MsalException)`() {
        // MsalArgumentException IS-A MsalException, use its public constructor
        assertEquals(
            "Argument failure message", // Expect the message itself when present
            ErrorMapper.mapAuthExceptionToUserMessage(
                MsalArgumentException(
                    "arg_name",
                    "param_name",
                    "Argument failure message"
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles other MsalException with message`() {
        // Cannot instantiate base MsalException directly usually. Test with another subtype like MsalDeclinedScopeException if needed, or rely on subtype tests.
        // Using MsalArgumentException as an example of a subtype not explicitly handled.
        assertEquals(
            "Argument failure message", // Expect the message itself when present
            ErrorMapper.mapAuthExceptionToUserMessage(
                MsalArgumentException(
                    "arg_name",
                    "param_name",
                    "Argument failure message"
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles other MsalException with blank message`() {
        // Arrange: Create an MsalArgumentException with a blank message.
        // Its errorCode property will likely resolve to "illegal_argument_exception" or similar.
        val blankArgumentException = MsalArgumentException(
            "arg_name",
            "param_name",
            "" // Blank message triggers the fallback
        )
        val actualErrorCode =
            blankArgumentException.errorCode ?: "UNKNOWN" // Get the actual error code

        // Assert: Expect the fallback message using the actual error code from the exception instance.
        // *** APPLY FIX: Use the actual error code from the exception ***
        assertEquals(
            "Authentication failed ($actualErrorCode)", // Corrected expected message format
            ErrorMapper.mapAuthExceptionToUserMessage(blankArgumentException)
        )
    }

    @Test
    fun `mapAuthException handles generic IOException via fallback`() {
        assertEquals(
            "Network error occurred",
            ErrorMapper.mapAuthExceptionToUserMessage(IOException("Network issue"))
        )
    }

    @Test
    fun `mapAuthException handles UnknownHostException via fallback`() {
        assertEquals(
            "No internet connection",
            ErrorMapper.mapAuthExceptionToUserMessage(UnknownHostException("Cannot resolve"))
        )
    }

    @Test
    fun `mapAuthException handles unknown non Msal Exception via fallback`() {
        assertEquals(
            "Unknown error message", // Should map to the exception message if present
            ErrorMapper.mapAuthExceptionToUserMessage(RuntimeException("Unknown error message"))
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with message`() {
        assertEquals(
            "User cancelled", // Expect specific message from CancellationException
            ErrorMapper.mapAuthExceptionToUserMessage(CancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with null message`() {
        // Use the constructor that takes a cause (Throwable?) or null message
        assertEquals(
            "Authentication cancelled.", // Expect fallback message for null message CancellationException
            ErrorMapper.mapAuthExceptionToUserMessage(CancellationException(null as String?))
        )
    }

    @Test
    fun `mapAuthException handles null exception`() {
        assertEquals("An unknown error occurred", ErrorMapper.mapAuthExceptionToUserMessage(null))
    }


    // --- mapGraphExceptionToUserMessage Tests ---
    // ... (Keep passing tests unchanged) ...
    @Test
    fun `mapGraphException handles IOException`() {
        assertEquals(
            "Network error occurred",
            ErrorMapper.mapGraphExceptionToUserMessage(IOException("Graph network failed"))
        )
    }

    @Test
    fun `mapGraphException handles UnknownHostException`() {
        assertEquals(
            "No internet connection",
            ErrorMapper.mapGraphExceptionToUserMessage(UnknownHostException("Cannot reach graph.microsoft.com"))
        )
    }

    @Test
    fun `mapGraphException handles generic Exception with message`() {
        assertEquals(
            "Graph processing failed", // Expect the exception message
            ErrorMapper.mapGraphExceptionToUserMessage(RuntimeException("Graph processing failed"))
        )
    }

    @Test
    fun `mapGraphException handles generic Exception with blank message`() {
        assertEquals(
            "An unknown error occurred", // Expect fallback
            ErrorMapper.mapGraphExceptionToUserMessage(RuntimeException(""))
        )
    }

    @Test
    fun `mapGraphException handles null exception`() {
        assertEquals("An unknown error occurred", ErrorMapper.mapGraphExceptionToUserMessage(null))
    }
}