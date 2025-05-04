package net.melisma.backend_microsoft.errors

// No Before needed for object testing
import com.microsoft.identity.client.exception.MsalArgumentException
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

class ErrorMapperTest {

    // Test target is the ErrorMapper object

    // --- mapAuthExceptionToUserMessage Tests ---

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
            "Argument failure message",
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
            "Argument failure message",
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
        assertEquals(
            "Authentication failed (INVALID_ARGUMENT)",
            ErrorMapper.mapAuthExceptionToUserMessage(
                MsalArgumentException(
                    "arg_name",
                    "param_name",
                    ""
                )
            )
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
            "Unknown error message",
            ErrorMapper.mapAuthExceptionToUserMessage(RuntimeException("Unknown error message"))
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with message`() {
        assertEquals(
            "User cancelled",
            ErrorMapper.mapAuthExceptionToUserMessage(CancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with null message`() {
        // Use the constructor that takes a cause (Throwable?) or null message
        assertEquals(
            "Authentication cancelled.",
            ErrorMapper.mapAuthExceptionToUserMessage(CancellationException(null as String?))
        )
    }

    @Test
    fun `mapAuthException handles null exception`() {
        assertEquals("An unknown error occurred", ErrorMapper.mapAuthExceptionToUserMessage(null))
    }

    // --- mapGraphExceptionToUserMessage Tests ---

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
            "Graph processing failed",
            ErrorMapper.mapGraphExceptionToUserMessage(RuntimeException("Graph processing failed"))
        )
    }

    @Test
    fun `mapGraphException handles generic Exception with blank message`() {
        assertEquals(
            "An unknown error occurred",
            ErrorMapper.mapGraphExceptionToUserMessage(RuntimeException(""))
        )
    }

    @Test
    fun `mapGraphException handles null exception`() {
        assertEquals("An unknown error occurred", ErrorMapper.mapGraphExceptionToUserMessage(null))
    }
}