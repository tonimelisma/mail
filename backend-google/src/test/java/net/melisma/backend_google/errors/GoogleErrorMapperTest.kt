package net.melisma.backend_google.errors

// Remove unused GoogleAuthenticationException if it's no longer directly mapped
// import net.melisma.backend_google.auth.GoogleAuthenticationException
import android.util.Log
import com.auth0.android.jwt.DecodeException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.openid.appauth.AuthorizationException
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    fun `mapAuthExceptionToUserMessage handles USER_CANCELED_AUTH_FLOW`() {
        val exception = AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW
        assertEquals(
            "Sign-in cancelled by user.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles PROGRAM_CANCELED_AUTH_FLOW`() {
        val exception = AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW
        assertEquals(
            "Sign-in process was cancelled.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles INVALID_GRANT`() {
        val exception = AuthorizationException.TokenRequestErrors.INVALID_GRANT
        assertEquals(
            "Authentication failed. Your session might have expired or been revoked. Please try signing in again.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles TokenRequestErrors like INVALID_CLIENT`() {
        val exception = AuthorizationException.TokenRequestErrors.INVALID_CLIENT
        val expectedMessage =
            "Authentication configuration error. Please contact support if this persists. (Error: ${exception.errorDescription ?: exception.code})"
        assertEquals(
            expectedMessage,
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles TokenRequestErrors with null description`() {
        val exception = AuthorizationException(
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
            AuthorizationException.TokenRequestErrors.INVALID_REQUEST.code,
            "invalid_request",
            null,
            null,
            null
        )
        val expectedMessage =
            "Authentication configuration error. Please contact support if this persists. (Error: ${exception.code})"
        assertEquals(
            expectedMessage,
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles GeneralErrors NETWORK_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.NETWORK_ERROR
        assertEquals(
            "Network error during sign-in. Please check your connection.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles GeneralErrors SERVER_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.SERVER_ERROR
        assertEquals(
            "Google server error during sign-in. Please try again later.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles other AuthorizationException`() {
        // Using a custom AuthorizationException that doesn't match specific codes
        val exception = AuthorizationException(
            AuthorizationException.TYPE_GENERAL_ERROR,
            777, // Custom code for "other"
            "custom_auth_error_name",
            "A custom general auth error description",
            null, // errorUri
            null  // rootCause
        )
        val expectedMessage =
            "An authentication error occurred with Google. (AppAuth: ${exception.errorDescription ?: exception.code})"
        assertEquals(
            expectedMessage,
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles other AuthorizationException with null description`() {
        val exception = AuthorizationException(
            AuthorizationException.TYPE_GENERAL_ERROR,
            999, // Code 999 for "other"
            "other_error_name",
            null, // errorDescription
            null, // errorUri
            null  // rootCause
        )
        val expectedMessage =
            "An authentication error occurred with Google. (AppAuth: ${exception.code})" // errorDescription is null
        assertEquals(
            expectedMessage,
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles DecodeException`() {
        val mockException = mockk<DecodeException>()
        every { mockException.message } returns "Failed to decode token" // Optional: if message is used by mapper
        // If the mapper logs exception.javaClass.simpleName, that will also be mocked.

        assertEquals(
            "An error occurred while processing your Google Sign-In data. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(mockException)
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles IllegalStateException`() {
        val exception = IllegalStateException("Some other state issue")
        assertEquals(
            "An unexpected authentication error occurred. Please try again later.",
            errorMapper.mapAuthExceptionToUserMessage(exception) // This will hit the 'else' for unknown exceptions
        )
    }

    @Test
    fun `mapAuthExceptionToUserMessage handles unknown Exception`() {
        val exception = RuntimeException("Some generic runtime error")
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
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
    fun `mapNetworkOrApiException handles AuthorizationException NETWORK_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.NETWORK_ERROR
        assertEquals(
            "Network error during Google operation. Please check your connection or try again. (AppAuth Code: ${exception.code})",
            errorMapper.mapNetworkOrApiException(exception)
        )
    }

    @Test
    fun `mapNetworkOrApiException handles AuthorizationException SERVER_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.SERVER_ERROR
        assertEquals(
            "Network error during Google operation. Please check your connection or try again. (AppAuth Code: ${exception.code})",
            errorMapper.mapNetworkOrApiException(exception)
        )
    }

    @Test
    fun `mapNetworkOrApiException handles other AuthorizationException`() {
        val exception = AuthorizationException(
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR, // A type that hits the else in the mapper's when for AuthEx
            888, // Custom code not matching NETWORK_ERROR or SERVER_ERROR
            "custom_api_token_error_name",
            "A custom API related token auth error description",
            null, // errorUri
            null  // rootCause
        )
        // This should fall into the 'else' for AuthorizationException in mapNetworkOrApiException
        val expectedMessage =
            "A Google service error occurred during authentication. (AppAuth Code: ${exception.code})"
        assertEquals(
            expectedMessage,
            errorMapper.mapNetworkOrApiException(exception)
        )
    }

    @Test
    fun `mapNetworkOrApiException handles IOException`() {
        assertEquals(
            "Network error. Please check your internet connection and try again.",
            errorMapper.mapNetworkOrApiException(IOException("Gmail API network failed"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles SocketTimeoutException`() {
        // SocketTimeoutException is an IOException
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
    fun `mapNetworkOrApiException handles generic Exception with message`() {
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

    @Test
    fun `mapNetworkOrApiException handles null exception`() {
        assertEquals(
            "An unknown network or Google service error occurred. Please try again.",
            errorMapper.mapNetworkOrApiException(null)
        )
    }
}