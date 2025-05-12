package net.melisma.backend_google.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
                level = LogLevel.HEADERS // Or LogLevel.ALL for more detail
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        Log.d("KtorGoogleClient", message)
                    }
                }
            }

            // Install the Auth plugin
            install(Auth) {
                bearer {
                    loadTokens {
                        // This lambda IS a suspend context
                        googleKtorTokenProvider.loadBearerTokens()
                    }

                    refreshTokens { // oldTokens: BearerTokens? -> // Ktor provides oldTokens as this.oldTokens implicitly
                        // This lambda IS a suspend context
                        googleKtorTokenProvider.refreshBearerTokens(this.oldTokens)
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
}