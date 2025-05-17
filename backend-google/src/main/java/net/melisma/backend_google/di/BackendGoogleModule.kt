package net.melisma.backend_google.di

// Added imports for Context, AccountManager and @ApplicationContext
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
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
import kotlinx.serialization.json.Json
import net.melisma.backend_google.GmailApiHelper
import net.melisma.backend_google.auth.GoogleKtorTokenProvider
import net.melisma.backend_google.errors.GoogleErrorMapper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.errors.ErrorMapperService
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleHttpClient

@Module
@InstallIn(SingletonComponent::class)
object BackendGoogleModule {

    init {
        Log.d(
            "BackendGoogleModule",
            "BackendGoogleModule (object with @Provides) initialized by Hilt"
        )
    }

    @Provides
    @Singleton
    @GoogleHttpClient
    fun provideGoogleHttpClient(
        json: Json,
        googleKtorTokenProvider: GoogleKtorTokenProvider
    ): HttpClient {
        Log.d("BackendGoogleModule", "Providing Google HTTPClient with Auth plugin setup.")
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
                        Log.d("KtorGoogleClient", firstLine)
                    }
                }
            }

            // Install the Auth plugin
            install(Auth) {
                bearer {
                    loadTokens {
                        // This lambda IS a suspend context
                        googleKtorTokenProvider.getBearerTokens()
                    }

                    refreshTokens {
                        // This would be called by Ktor if a request with tokens from loadTokens fails (e.g., 401)
                        googleKtorTokenProvider.getBearerTokens()
                        // Alternatively, a more specific refresh-only method could be exposed by the provider
                    }

                    // Optional: Only send tokens for Gmail API calls
                    sendWithoutRequest { request ->
                        request.url.host == "gmail.googleapis.com" || request.url.host == "www.googleapis.com"
                    }
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideGmailApiHelper(
        @GoogleHttpClient httpClient: HttpClient,
        googleErrorMapper: GoogleErrorMapper
    ): GmailApiHelper {
        Log.d("BackendGoogleModule", "Providing GmailApiHelper")
        return GmailApiHelper(httpClient, googleErrorMapper)
    }

    @Provides
    @Singleton
    @IntoMap
    @StringKey("GOOGLE")
    fun provideGmailApiAsMailService(gmailApiHelper: GmailApiHelper): MailApiService {
        Log.i("BackendGoogleModule", "Providing MailApiService for 'GOOGLE' key via @Provides")
        return gmailApiHelper
    }

    @Provides
    @Singleton
    @IntoMap
    @StringKey("GOOGLE")
    fun provideGoogleErrorMapperService(
        googleErrorMapper: GoogleErrorMapper
    ): ErrorMapperService {
        Log.i("BackendGoogleModule", "Providing ErrorMapperService for 'GOOGLE' key via @Provides")
        return googleErrorMapper
    }

    // New @Provides methods for Context and AccountManager
    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        Log.d("BackendGoogleModule", "Providing ApplicationContext")
        return context
    }

    @Provides
    @Singleton
    fun provideAccountManager(@ApplicationContext context: Context): AccountManager { // Ensure @ApplicationContext if directly using it here for safety
        Log.d("BackendGoogleModule", "Providing AccountManager")
        return AccountManager.get(context)
    }
}