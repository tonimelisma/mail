package net.melisma.backend_google.errors

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.melisma.backend_google.auth.GoogleAuthenticationException
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
    fun `mapAuthException handles GoogleAuthenticationException for cancellation`() {
        assertEquals(
            "Authentication cancelled by user.",
            errorMapper.mapAuthExceptionToUserMessage(
                GoogleAuthenticationException(
                    "Authentication cancelled",
                    GoogleAuthenticationException.CODE_USER_CANCEL
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles GoogleAuthenticationException for unauthorized`() {
        assertEquals(
            "Authentication failed: Account not authorized.",
            errorMapper.mapAuthExceptionToUserMessage(
                GoogleAuthenticationException(
                    "Unauthorized",
                    GoogleAuthenticationException.CODE_UNAUTHORIZED
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles GoogleAuthenticationException for invalid scopes`() {
        assertEquals(
            "Authentication failed: Invalid scopes requested.",
            errorMapper.mapAuthExceptionToUserMessage(
                GoogleAuthenticationException(
                    "Invalid scopes",
                    GoogleAuthenticationException.CODE_INVALID_SCOPES
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles GoogleAuthenticationException for no account`() {
        assertEquals(
            "No Google account found. Try signing in again.",
            errorMapper.mapAuthExceptionToUserMessage(
                GoogleAuthenticationException(
                    "No account",
                    GoogleAuthenticationException.CODE_NO_ACCOUNT
                )
            )
        )
    }

    @Test
    fun `mapAuthException handles GoogleAuthenticationException with unknown code`() {
        assertEquals(
            "Custom error message",
            errorMapper.mapAuthExceptionToUserMessage(
                GoogleAuthenticationException("Custom error message", "UNKNOWN_CODE")
            )
        )
    }

    @Test
    fun `mapAuthException handles GoogleAuthenticationException with empty message`() {
        assertEquals(
            "Authentication failed (UNKNOWN_CODE)",
            errorMapper.mapAuthExceptionToUserMessage(
                GoogleAuthenticationException("", "UNKNOWN_CODE")
            )
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with message`() {
        assertEquals(
            "User cancelled",
            errorMapper.mapAuthExceptionToUserMessage(CancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapAuthException handles CancellationException with null message`() {
        assertEquals(
            "Authentication cancelled.",
            errorMapper.mapAuthExceptionToUserMessage(CancellationException(null as String?))
        )
    }

    @Test
    fun `mapAuthException handles generic IOException via fallback`() {
        assertEquals(
            "Network error occurred", // mapAuthException falls back to mapNetworkOrApiException
            errorMapper.mapAuthExceptionToUserMessage(IOException("Network issue"))
        )
    }

    @Test
    fun `mapAuthException handles UnknownHostException via fallback`() {
        assertEquals(
            "No internet connection", // mapAuthException falls back to mapNetworkOrApiException
            errorMapper.mapAuthExceptionToUserMessage(UnknownHostException("Cannot resolve"))
        )
    }

    @Test
    fun `mapAuthException handles unknown Exception via fallback`() {
        assertEquals(
            "Unknown error message", // Falls back to network mapping, then uses message
            errorMapper.mapAuthExceptionToUserMessage(RuntimeException("Unknown error message"))
        )
    }

    @Test
    fun `mapAuthException handles unknown Exception with blank message via fallback`() {
        assertEquals(
            "An unknown authentication error occurred", // Falls back to network mapping (unknown), then auth fallback
            errorMapper.mapAuthExceptionToUserMessage(RuntimeException(""))
        )
    }

    @Test
    fun `mapAuthException handles null exception`() {
        assertEquals(
            "An unknown authentication error occurred", // Falls back to network mapping (unknown), then auth fallback
            errorMapper.mapAuthExceptionToUserMessage(null)
        )
    }

    // --- mapNetworkOrApiException Tests ---

    @Test
    fun `mapNetworkOrApiException handles IOException`() {
        assertEquals(
            "Network error occurred",
            errorMapper.mapNetworkOrApiException(IOException("Gmail API network failed"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles SocketTimeoutException`() {
        assertEquals(
            "Connection timed out",
            errorMapper.mapNetworkOrApiException(SocketTimeoutException("Request timed out"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles UnknownHostException`() {
        assertEquals(
            "No internet connection",
            errorMapper.mapNetworkOrApiException(UnknownHostException("Cannot reach gmail.googleapis.com"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles generic Exception with message`() {
        assertEquals(
            "Gmail API processing failed",
            errorMapper.mapNetworkOrApiException(RuntimeException("Gmail API processing failed"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles generic Exception with blank message`() {
        assertEquals(
            "An unknown network or API error occurred",
            errorMapper.mapNetworkOrApiException(RuntimeException(""))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles CancellationException with message`() {
        assertEquals(
            "User cancelled",
            errorMapper.mapNetworkOrApiException(CancellationException("User cancelled"))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles CancellationException with null message`() {
        assertEquals(
            "Operation cancelled.",
            errorMapper.mapNetworkOrApiException(CancellationException(null as String?))
        )
    }

    @Test
    fun `mapNetworkOrApiException handles null exception`() {
        assertEquals(
            "An unknown network or API error occurred",
            errorMapper.mapNetworkOrApiException(null)
        )
    }
}