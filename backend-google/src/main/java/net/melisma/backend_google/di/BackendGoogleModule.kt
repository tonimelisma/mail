package net.melisma.backend_google.di

// Added imports for Dispatchers
import android.accounts.AccountManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import net.melisma.backend_google.GmailApiHelper
import net.melisma.backend_google.auth.ActiveGoogleAccountHolder
import net.melisma.backend_google.auth.GoogleAuthManager
import net.melisma.backend_google.auth.GoogleKtorTokenProvider
import net.melisma.backend_google.errors.GoogleErrorMapper
import net.melisma.core_data.auth.NeedsReauthenticationException
import net.melisma.core_data.auth.TokenProviderException
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.errors.ErrorMapperService
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnauthenticatedGoogleHttpClient

@Module
@InstallIn(SingletonComponent::class)
object BackendGoogleModule {

    init {
        Timber.d(
            "BackendGoogleModule (object with @Provides) initialized by Hilt"
        )
    }

    @Provides
    @Singleton
    @GoogleHttpClient
    fun provideGoogleHttpClient(
        json: Json,
        googleKtorTokenProvider: GoogleKtorTokenProvider,
        googleAuthManager: GoogleAuthManager,
        activeGoogleAccountHolder: ActiveGoogleAccountHolder
    ): HttpClient {
        Timber.d("Providing Google HTTPClient with Auth plugin setup.")
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }

            // Optional: Logging for Ktor requests/responses
            install(Logging) {
                level = LogLevel.INFO
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        // Only log the first non-blank line of the message
                        val firstLine = message.lines().firstOrNull { it.isNotBlank() } ?: message
                        Timber.tag("KtorGoogleClient").d(firstLine)
                    }
                }
            }

            // Install the Auth plugin
            install(Auth) {
                bearer {
                    loadTokens {
                        Timber.tag("KtorAuth").d("Google Auth: loadTokens called")
                        try {
                            googleKtorTokenProvider.getBearerTokens()
                        } catch (e: NeedsReauthenticationException) {
                            Timber.tag("KtorAuth")
                                .w(e, "Google Auth: Needs re-authentication during loadTokens.")
                            null
                        } catch (e: TokenProviderException) {
                            Timber.tag("KtorAuth")
                                .e(e, "Google Auth: TokenProviderException during loadTokens.")
                            null
                        }
                    }
                    refreshTokens {
                        Timber.tag("KtorAuth").d("Google Auth: refreshTokens called")
                        try {
                            googleKtorTokenProvider.refreshBearerTokens(this.oldTokens)
                        } catch (e: NeedsReauthenticationException) {
                            Timber.tag("KtorAuth")
                                .w(e, "Google Auth: Needs re-authentication during refreshTokens.")
                            null
                        } catch (e: TokenProviderException) {
                            Timber.tag("KtorAuth")
                                .e(e, "Google Auth: TokenProviderException during refreshTokens.")
                            null
                        }
                    }
                    sendWithoutRequest { request ->
                        request.url.host == "gmail.googleapis.com" || request.url.host == "www.googleapis.com"
                    }
                }
            }
        }
    }

    @Provides
    @Singleton
    @UnauthenticatedGoogleHttpClient
    fun provideUnauthenticatedGoogleHttpClient(json: Json): HttpClient {
        Timber.d("Providing Unauthenticated Google HTTPClient")
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
            // Optional: Logging, but no Auth plugin
            install(Logging) {
                level = LogLevel.INFO
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        val firstLine = message.lines().firstOrNull { it.isNotBlank() } ?: message
                        Timber.tag("KtorUnauthGoogleClient").d(firstLine)
                    }
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideGmailApiHelper(
        @GoogleHttpClient httpClient: HttpClient,
        googleErrorMapper: GoogleErrorMapper,
        ioDispatcher: CoroutineDispatcher,
        gmailService: com.google.api.services.gmail.Gmail?,
        authManager: GoogleAuthManager
    ): GmailApiHelper {
        Timber.d("Providing GmailApiHelper with all dependencies, including GoogleAuthManager")
        return GmailApiHelper(
            httpClient,
            googleErrorMapper,
            ioDispatcher,
            gmailService,
            authManager
        )
    }

    @Provides
    @Singleton
    @IntoMap
    @StringKey("GOOGLE")
    fun provideGmailApiAsMailService(gmailApiHelper: GmailApiHelper): MailApiService {
        Timber.i("Providing MailApiService for 'GOOGLE' key via @Provides")
        return gmailApiHelper
    }

    @Provides
    @Singleton
    @IntoMap
    @StringKey("GOOGLE")
    fun provideGoogleErrorMapperService(
        googleErrorMapper: GoogleErrorMapper
    ): ErrorMapperService {
        Timber.i("Providing ErrorMapperService for 'GOOGLE' key via @Provides")
        return googleErrorMapper
    }

    // New @Provides methods for Context and AccountManager
    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        Timber.d("Providing ApplicationContext")
        return context
    }

    @Provides
    @Singleton
    fun provideAccountManager(@ApplicationContext context: Context): AccountManager { // Ensure @ApplicationContext if directly using it here for safety
        Timber.d("Providing AccountManager")
        return AccountManager.get(context)
    }

    // Added provider for CoroutineDispatcher
    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    // Added provider for Gmail Service (nullable placeholder)
    @Provides
    @Singleton
    fun provideGmailService(
        // Potentially inject: GoogleAuthManager, ApplicationContext, ActiveGoogleAccountHolder
        // For now, a simple approach to satisfy constructor:
        // activeGoogleAccountHolder: ActiveGoogleAccountHolder, 
        // googleAuthManager: GoogleAuthManager,
        // @ApplicationContext context: Context 
    ): com.google.api.services.gmail.Gmail? {
        Timber.w("Providing null for com.google.api.services.gmail.Gmail. Needs full GMSCore/GoogleSignIn based init including HttpTransport, JsonFactory and active user Credentials.")
        // TODO: Implement proper Gmail service initialization. This likely involves:
        // 1. Get active GoogleSignInAccount from GoogleSignIn.getLastSignedInAccount(context).
        // 2. If null, or token needs refresh, trigger sign-in/re-auth flow.
        // 3. Build GoogleAccountCredential using GoogleSignInAccount and GMAIL_SCOPES.
        // 4. Initialize HttpTransport (e.g., GoogleNetHttpTransport.newTrustedTransport() or OkHttp compatible one).
        // 5. Initialize JsonFactory (e.g., JacksonFactory.getDefaultInstance()).
        // 6. Build Gmail service: com.google.api.services.gmail.Gmail.Builder(transport, jsonFactory, credential)
        //    .setApplicationName("Melisma Mail").build()
        // This component is @Singleton, so the service should be cached once created for an active user.
        // Handling account switching or token expiry requires more complex logic here or in GoogleAuthManager.
        return null // Placeholder to allow compilation
    }
}