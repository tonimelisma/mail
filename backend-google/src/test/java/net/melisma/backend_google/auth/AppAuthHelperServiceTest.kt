package net.melisma.backend_google.auth

import android.content.Context
import android.net.Uri
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkConstructor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class AppAuthHelperServiceTest {

    @MockK
    private lateinit var context: Context

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appAuthHelperService: AppAuthHelperService

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // Mock AuthorizationService
        val mockAuthService = mockk<AuthorizationService>(relaxed = true)
        every { AuthorizationService(any()) } returns mockAuthService

        // Mock AuthorizationServiceConfiguration
        mockkConstructor(AuthorizationServiceConfiguration::class)
        every {
            anyConstructed<AuthorizationServiceConfiguration>().authorizationEndpoint
        } returns Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
        every {
            anyConstructed<AuthorizationServiceConfiguration>().tokenEndpoint
        } returns Uri.parse("https://oauth2.googleapis.com/token")

        // Mock AuthorizationRequest
        mockkConstructor(AuthorizationRequest.Builder::class)
        every {
            anyConstructed<AuthorizationRequest.Builder>().setScope(any())
        } returns mockk(relaxed = true)

        // Create service under test
        appAuthHelperService = AppAuthHelperService(context, testDispatcher)
    }

    @Test
    fun `getServiceConfiguration returns valid configuration`() = runTest(testDispatcher) {
        // When
        val config = appAuthHelperService.getServiceConfiguration()

        // Then
        assertNotNull(config)
        assertEquals(
            "https://accounts.google.com/o/oauth2/v2/auth",
            config.authorizationEndpoint.toString()
        )
        assertEquals("https://oauth2.googleapis.com/token", config.tokenEndpoint.toString())
    }

    @Test
    fun `buildAuthorizationRequest creates properly configured request`() =
        runTest(testDispatcher) {
            // Given
            val clientId = "test-client-id"
            val redirectUri = Uri.parse("net.melisma.mail:/oauth2redirect")
            val scopes = "https://mail.google.com/ email profile openid"

            // Set up mocks to return the values we want to verify
            every {
                anyConstructed<AuthorizationRequest>().clientId
            } returns clientId
            every {
                anyConstructed<AuthorizationRequest>().redirectUri
            } returns redirectUri
            every {
                anyConstructed<AuthorizationRequest>().scope
            } returns scopes

            // When
            val request =
                appAuthHelperService.buildAuthorizationRequest(clientId, redirectUri, scopes)

            // Then
            assertNotNull(request)
            assertEquals(clientId, request.clientId)
            assertEquals(redirectUri, request.redirectUri)
            assertEquals(scopes, request.scope)
        }
}