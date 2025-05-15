package net.melisma.backend_google.auth

import android.content.Context
import android.net.Uri
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import org.junit.Before

@ExperimentalCoroutinesApi
class AppAuthHelperServiceTest {

    @MockK
    private lateinit var context: Context

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appAuthHelperService: AppAuthHelperService

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // Mock Uri.parse and related Uri functionalities
        mockkStatic(Uri::class)
        val mockAuthEndpointUri = mockk<Uri>(relaxed = true)
        val mockTokenEndpointUri = mockk<Uri>(relaxed = true)
        val mockRedirectUri = mockk<Uri>(relaxed = true)

        every { Uri.parse("https://accounts.google.com/o/oauth2/v2/auth") } returns mockAuthEndpointUri
        every { mockAuthEndpointUri.toString() } returns "https://accounts.google.com/o/oauth2/v2/auth"

        every { Uri.parse("https://oauth2.googleapis.com/token") } returns mockTokenEndpointUri
        every { mockTokenEndpointUri.toString() } returns "https://oauth2.googleapis.com/token"

        every { Uri.parse("net.melisma.mail:/oauth2redirect") } returns mockRedirectUri
        every { mockRedirectUri.toString() } returns "net.melisma.mail:/oauth2redirect"

        every { Uri.parse(any<String>()) } answers { mockk<Uri>(relaxed = true).also { every { it.toString() } returns firstArg() } }

        // Mock AuthorizationService
        val mockAuthService = mockk<AuthorizationService>(relaxed = true)
        every { AuthorizationService(context) } returns mockAuthService
        every { AuthorizationService(any()) } returns mockAuthService // Fallback

        // Mock AuthorizationServiceConfiguration and its static fetchFromIssuer method
        mockkStatic(AuthorizationServiceConfiguration::class) // Mock static methods of this class
        val mockServiceConfig =
            mockk<AuthorizationServiceConfiguration>(relaxed = true) // This is the instance we want fetchFromIssuer to return

        every {
            mockServiceConfig.authorizationEndpoint
        } returns mockAuthEndpointUri
        every {
            mockServiceConfig.tokenEndpoint
        } returns mockTokenEndpointUri

        every {
            AuthorizationServiceConfiguration.fetchFromIssuer(
                any(), // discoveryUri
                any()  // callback
            )
        } answers { // Use answers to invoke the callback with the mockServiceConfig
            val callback = arg<AuthorizationServiceConfiguration.RetrieveConfigurationCallback>(1)
            callback.onFetchConfigurationCompleted(mockServiceConfig, null)
        }
        
        // Mock AuthorizationRequest
        mockkConstructor(AuthorizationRequest.Builder::class)
        every {
            anyConstructed<AuthorizationRequest.Builder>().setScope(any())
        } returns mockk(relaxed = true)

        // Create service under test
        appAuthHelperService = AppAuthHelperService(context, testDispatcher)
    }
}