package net.melisma.backend_google.errors

// Remove unused GoogleAuthenticationException if it's no longer directly mapped
// import net.melisma.backend_google.auth.GoogleAuthenticationException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.AfterClass
import org.junit.BeforeClass
import timber.log.Timber

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
            // Mock Timber statically for the entire test class
            mockkStatic(Timber::class)
            // Assuming Timber is set up to not actually log during tests or uses a TestTree.
            // If not, you might need to provide a specific TestTree or mock specific tag calls.
            // every { Timber.v(any(), any()) } returns Unit // etc. for other levels if needed
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            // Unmock Timber after all tests in the class have run
            unmockkStatic(Timber::class)
        }
    }

    // --- mapAuthExceptionToUserMessage Tests ---

    /* @Test
    fun `mapAuthExceptionToUserMessage handles USER_CANCELED_AUTH_FLOW`() {
        val exception = AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW
        assertEquals(
            "Sign-in cancelled by user.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles PROGRAM_CANCELED_AUTH_FLOW`() {
        val exception = AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW
        assertEquals(
            "Sign-in process was cancelled.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles INVALID_GRANT`() {
        val exception = AuthorizationException.TokenRequestErrors.INVALID_GRANT
        assertEquals(
            "Authentication failed. Your session might have expired or been revoked. Please try signing in again.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles TokenRequestErrors like INVALID_CLIENT`() {
        val exception = AuthorizationException.TokenRequestErrors.INVALID_CLIENT
        val expectedMessage =
            "Authentication configuration error. Please contact support if this persists. (Error: ${exception.errorDescription ?: exception.code})"
        assertEquals(
            expectedMessage,
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    } */

    /* @Test
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
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles GeneralErrors NETWORK_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.NETWORK_ERROR
        assertEquals(
            "Network error during sign-in. Please check your connection.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles GeneralErrors SERVER_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.SERVER_ERROR
        assertEquals(
            "Google server error during sign-in. Please try again later.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    } */

    /* @Test
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
    } */

    /* @Test
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
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles DecodeException`() {
        val mockException = mockk<DecodeException>()
        every { mockException.message } returns "Failed to decode token" // Optional: if message is used by mapper
        // If the mapper logs exception.javaClass.simpleName, that will also be mocked.

        assertEquals(
            "An error occurred while processing your Google Sign-In data. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(mockException)
        )
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles IllegalStateException`() {
        val exception = IllegalStateException("Some other state issue")
        assertEquals(
            "An unexpected authentication error occurred. Please try again later.",
            errorMapper.mapAuthExceptionToUserMessage(exception) // This will hit the 'else' for unknown exceptions
        )
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles unknown Exception`() {
        val exception = RuntimeException("Some generic runtime error")
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(exception)
        )
    } */

    /* @Test
    fun `mapAuthExceptionToUserMessage handles null exception`() {
        assertEquals(
            "An unknown authentication error occurred with Google. Please try again.",
            errorMapper.mapAuthExceptionToUserMessage(null)
        )
    } */

    // --- mapNetworkOrApiException Tests ---

    /* @Test
    fun `mapNetworkOrApiException handles AuthorizationException NETWORK_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.NETWORK_ERROR
        assertEquals(
            "Network error. Please check your connection and try again.",
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles AuthorizationException SERVER_ERROR`() {
        val exception = AuthorizationException.GeneralErrors.SERVER_ERROR
        assertEquals(
            "A server error occurred with Google services. Please try again later.",
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles other AuthorizationException`() {
        val exception = AuthorizationException.TokenRequestErrors.INVALID_CLIENT // Any other auth error
        val expectedMessage = "An API error occurred with Google services. (AppAuth: ${exception.errorDescription ?: exception.code})"
        assertEquals(
            expectedMessage,
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles IOException`() {
        val exception = IOException("Network unavailable")
        assertEquals(
            "Network error: Network unavailable",
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles SocketTimeoutException`() {
        val exception = SocketTimeoutException("Request timed out")
        assertEquals(
            "Network error: The request to Google timed out. Please try again.",
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles UnknownHostException`() {
        val exception = UnknownHostException("gmail.googleapis.com")
        assertEquals(
            "Network error: Could not connect to Google services. Please check your internet connection.",
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles generic Exception with message`() {
        val exception = Exception("A strange thing happened")
        assertEquals(
            "An unexpected error occurred with Google services: A strange thing happened",
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles generic Exception with blank message`() {
        val exception = Exception("  ")
        assertEquals(
            "An unexpected error occurred with Google services.",
            errorMapper.mapNetworkOrApiException(exception).message
        )
    } */

    /* @Test
    fun `mapNetworkOrApiException handles null exception`() {
        assertEquals(
            "An unexpected error occurred with Google services.",
            errorMapper.mapNetworkOrApiException(null).message
        )
    } */
}