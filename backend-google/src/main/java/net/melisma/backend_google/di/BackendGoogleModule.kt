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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.melisma.backend_google.GmailApiHelper
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

    // Note: For a production implementation, we would include token refresh logic
    // using the Ktor Auth plugin. This has been documented separately in HISTORY.md.

    @Provides
    @Singleton
    @GoogleHttpClient
    fun provideGoogleHttpClient(json: Json): HttpClient {
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