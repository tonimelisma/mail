package net.melisma.backend_google.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
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
import net.melisma.backend_google.errors.GoogleErrorMapper // Import the new error mapper
import net.melisma.core_data.datasource.MailApiService
// import net.melisma.core_data.datasource.TokenProvider // TokenProvider for Google is removed
import net.melisma.core_data.di.ApiHelperType
import net.melisma.core_data.di.ErrorMapperType // Import the new qualifier
// import net.melisma.core_data.di.TokenProviderType // TokenProviderType for Google is removed
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
object BackendGoogleModule { // Changed to object module for @Provides

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

    // GoogleAuthManager is @Singleton and has @Inject constructor, so Hilt provides it directly.
    // No need to explicitly provide GoogleAuthManager here unless binding to an interface.

    // REMOVED: provideGoogleTokenProvider, as GoogleTokenProvider.kt is deleted.
    // The functionality is now within GoogleAuthManager.

    /**
     * Provides the GmailApiHelper for injection.
     * It now takes GoogleErrorMapper directly if needed, or a map of ErrorMapperServices.
     * For simplicity, if GmailApiHelper needs an error mapper specifically for Google errors,
     * it can inject GoogleErrorMapper directly. If it needs a generic one,
     * it would inject ErrorMapperService with a qualifier or the map.
     * Assuming GmailApiHelper might use a generic error mapping strategy or a specific one.
     * Let's assume it can take the specific GoogleErrorMapper for now.
     */
    @Provides
    @Singleton
    fun provideGmailApiHelper(
        @GoogleHttpClient httpClient: HttpClient,
        googleErrorMapper: GoogleErrorMapper // Injecting specific mapper for simplicity
        // Alternatively, inject Map<String, ErrorMapperService>
        // and select the "GOOGLE" one.
    ): GmailApiHelper {
        // If GmailApiHelper's constructor expects ErrorMapperService,
        // then googleErrorMapper (which implements it) can be passed.
        return GmailApiHelper(httpClient, googleErrorMapper)
    }

    /**
     * Provides the GmailApiHelper as a MailApiService implementation
     * with the appropriate qualifier for multi-binding
     */
    @Provides
    @Singleton
    @ApiHelperType("GOOGLE")
    @IntoMap
    @StringKey("GOOGLE")
    fun provideGmailApiAsMailService(gmailApiHelper: GmailApiHelper): MailApiService {
        return gmailApiHelper
    }
}

// Abstract module for Binds
@Module
@InstallIn(SingletonComponent::class)
abstract class BackendGoogleBindingModule {

    @Binds
    @Singleton
    @ErrorMapperType("GOOGLE") // Qualify this implementation
    @IntoMap // Add to map
    @StringKey("GOOGLE") // Key for the map
    abstract fun bindGoogleErrorMapperService(
        googleErrorMapper: GoogleErrorMapper // Provided via @Inject constructor
    ): ErrorMapperService
}
