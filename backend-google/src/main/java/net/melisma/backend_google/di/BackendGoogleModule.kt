package net.melisma.backend_google.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.melisma.backend_google.GmailApiHelper
import net.melisma.backend_google.datasource.GoogleTokenProvider
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.errors.ErrorMapperService
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier annotation for the Google-specific HTTP client
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleHttpClient

/**
 * Hilt module that provides dependencies for the Google backend
 */
@Module
@InstallIn(SingletonComponent::class)
object BackendGoogleModule {

    /**
     * Provides a JSON serializer configured for Google APIs
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        prettyPrint = true // Good for debugging
        isLenient = true
        ignoreUnknownKeys = true // Essential for API stability
    }

    /**
     * Provides an HTTP client specifically configured for Google APIs
     */
    @Provides
    @Singleton
    @GoogleHttpClient
    fun provideGoogleHttpClient(json: Json): HttpClient {
        return HttpClient(OkHttp) {
            // Engine configuration (OkHttp specific)
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    // Configure retries, interceptors etc. if needed
                }
            }

            // JSON Serialization
            install(ContentNegotiation) {
                json(json) // Use the provided Json instance
            }

            // Default request configuration
            defaultRequest {
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
        }
    }

    /**
     * Binds the Google token provider to the TokenProvider interface with a
     * qualifier for multi-binding in the data module later
     */
    @Provides
    @Singleton
    @TokenProviderType("GOOGLE")
    fun provideGoogleTokenProvider(tokenProvider: GoogleTokenProvider): TokenProvider {
        return tokenProvider
    }

    /**
     * Provides the GmailApiHelper for injection
     */
    @Provides
    @Singleton
    fun provideGmailApiHelper(
        @GoogleHttpClient httpClient: HttpClient,
        errorMapper: ErrorMapperService
    ): GmailApiHelper {
        return GmailApiHelper(httpClient, errorMapper)
    }
}

/**
 * Qualifier for different provider types in multi-binding
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenProviderType(val value: String)