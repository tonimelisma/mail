package net.melisma.backend_google.errors

// Import androidx.credentials exceptions
// Import a concrete subclass of GetCredentialException
// Remove unused GoogleAuthenticationException if it's no longer directly mapped
// import net.melisma.backend_google.auth.GoogleAuthenticationException
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unit tests for the GoogleErrorMapper implementation.
 */
class GoogleErrorMapperTest {

    // --- Class Under Test ---
    private val errorMapper = GoogleErrorMapper()

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

    @Test
    fun `mapAuthExceptionToUserMessage handles GetCredentialCancellationException`() {
        assertEquals(
            "Sign-in cancelled by user.",
            errorMapper.mapAuthExceptionToUserMessage(GetCredentialCancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles GetCredentialCancellationException with null message`() {
        assertEquals(
            "Sign-in cancelled by user.",
            errorMapper.mapAuthExceptionToUserMessage(GetCredentialCancellationException(null))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles NoCredentialException`() {
        assertEquals(
            "No Google accounts found on this device to sign in. Please add an account to your device or try another method.",
            errorMapper.mapAuthExceptionToUserMessage(NoCredentialException("No accounts"))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles GetCredentialException`() {
        val message = "Some credential error from UnknownException"
        val actualException = GetCredentialUnknownException(message)
        val expectedMessagePart = "Google Sign-In failed. Please try again."
        val mappedMessage = errorMapper.mapAuthExceptionToUserMessage(actualException)
        assert(mappedMessage.startsWith(expectedMessagePart))
        assert(mappedMessage.contains("(Error:") && mappedMessage.endsWith(")"))
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles GetCredentialException with null type and message`() {
        val actualException = GetCredentialUnknownException(null)
        val mappedMessage = errorMapper.mapAuthExceptionToUserMessage(actualException)
        val expectedMessagePart = "Google Sign-In failed. Please try again."
        assert(mappedMessage.startsWith(expectedMessagePart))
        assert(mappedMessage.contains("(Error:") && mappedMessage.endsWith(")"))
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles GoogleIdTokenParsingException`() {
        assertEquals(
            "An error occurred while processing your Google Sign-In. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(GoogleIdTokenParsingException(null as Throwable?))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles IllegalStateException with PendingIntent missing`() {
        val exception = IllegalStateException("PendingIntent missing for Sign-in")
        assertEquals(
            "An internal error occurred with Google Sign-In. Please try again. (${exception.message})",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles IllegalStateException with access token null`() {
        val exception = IllegalStateException("Access token is null")
        assertEquals(
            "An internal error occurred with Google Sign-In. Please try again. (${exception.message})",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles general IllegalStateException`() {
        assertEquals(
            "An unexpected authentication error occurred. Please try again later.",
            errorMapper.mapAuthExceptionToUserMessage(IllegalStateException("Some other state issue"))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles ApiException`() {
        val statusCode = 10 // Example status code
        assertEquals(
            "A Google authentication error occurred (Code: $statusCode). Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(
                ApiException(
                    com.google.android.gms.common.api.Status(
                        statusCode,
                        "Auth API Error"
                    )
                )
            )
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles generic IOException via fallback`() {
        // mapAuthExceptionToUserMessage will use its own "Unknown Google Auth error" for non-specific exceptions
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(IOException("Network issue"))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles UnknownHostException via fallback`() {
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(UnknownHostException("Cannot resolve"))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles unknown Exception`() {
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(RuntimeException("Unknown error message"))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles unknown Exception with blank message`() {
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(RuntimeException(""))
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles null exception`() {
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(null)
        )
    }

    // --- mapNetworkOrApiException Tests ---

    @Test
    fun `mapNetworkOrApiException handles IOException`() {
        assertEquals(
            "Network error. Please check your internet connection and try again.",
            errorMapper.mapNetworkOrApiException(IOException("Gmail API network failed"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles SocketTimeoutException`() {
        // SocketTimeoutException is an IOException, so it gets the generic IOException message
        assertEquals(
            "Network error. Please check your internet connection and try again.",
            errorMapper.mapNetworkOrApiException(SocketTimeoutException("Request timed out"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles UnknownHostException`() {
        // UnknownHostException is an IOException
        assertEquals(
            "Network error. Please check your internet connection and try again.",
            errorMapper.mapNetworkOrApiException(UnknownHostException("Cannot reach gmail.googleapis.com"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles ApiException`() {
        val statusCode = 16 // Example: CommonStatusCodes.TIMEOUT or another API status
        assertEquals(
            "A Google service error occurred (Code: $statusCode). Please check your connection or try again.",
            errorMapper.mapNetworkOrApiException(
                ApiException(
                    com.google.android.gms.common.api.Status(
                        statusCode,
                        "API Error"
                    )
                )
            )
        )
    }

    @Test
    fun `mapNetworkOrApiException handles generic Exception with message`() {
        // Generic exceptions fall into the 'else' branch
        assertEquals(
            "An unknown network or Google service error occurred. Please try again.",
            errorMapper.mapNetworkOrApiException(RuntimeException("Gmail API processing failed"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles generic Exception with blank message`() {
        assertEquals(
            "An unknown network or Google service error occurred. Please try again.",
            errorMapper.mapNetworkOrApiException(RuntimeException(""))
        )
    }

    // CancellationException is not explicitly handled by mapNetworkOrApiException, so it falls to generic
    @Test
    fun `mapNetworkOrApiException handles CancellationException with message`() {
        assertEquals(
            "An unknown network or Google service error occurred. Please try again.",
            errorMapper.mapNetworkOrApiException(CancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles CancellationException with null message`() {
        assertEquals(
            "An unknown network or Google service error occurred. Please try again.",
            errorMapper.mapNetworkOrApiException(CancellationException(null as String?))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles null exception`() {
        assertEquals(
            "An unknown network or Google service error occurred. Please try again.",
            errorMapper.mapNetworkOrApiException(null)
        )
    }
}