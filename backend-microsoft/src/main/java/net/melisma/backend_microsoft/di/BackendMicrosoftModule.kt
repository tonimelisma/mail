package net.melisma.backend_microsoft.di

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
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.MicrosoftKtorTokenProvider
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.core_data.errors.ErrorMapperService
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MicrosoftGraphHttpClient

// Module for providing concrete instances and map contributions.
// We've consolidated bindings here using @Provides for map entries.
@Module
@InstallIn(SingletonComponent::class)
object BackendMicrosoftModule { // Changed to object module as it now only contains @Provides

    init {
        Log.d(
            "BackendMSModule",
            "BackendMicrosoftModule (object with @Provides) initialized by Hilt"
        )
    }

    @Provides
    @Singleton
    fun provideMicrosoftAuthManager(
        @ApplicationContext context: Context,
        authConfigProvider: AuthConfigProvider,
        @ApplicationScope externalScope: CoroutineScope,
        secureEncryptionService: net.melisma.core_data.security.SecureEncryptionService,
        @net.melisma.core_data.di.Dispatcher(net.melisma.core_data.di.MailDispatchers.IO) ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): MicrosoftAuthManager {
        Log.d(
            "BackendMSModule",
            "Providing MicrosoftAuthManager with ApplicationScope CoroutineScope, SecureEncryptionService, and IODispatcher"
        )
        return MicrosoftAuthManager(
            context,
            authConfigProvider,
            externalScope,
            secureEncryptionService,
            ioDispatcher
        )
    }

    @Provides
    @Singleton
    @MicrosoftGraphHttpClient
    fun provideMicrosoftGraphHttpClient(
        json: Json,
        microsoftKtorTokenProvider: MicrosoftKtorTokenProvider
    ): HttpClient {
        Log.d("BackendMSModule", "Providing Microsoft Graph HTTPClient with Auth plugin.")
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                }
            }
            install(ContentNegotiation) { json(json) }

            install(Logging) {
                level = LogLevel.INFO
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        val firstLine = message.lines().firstOrNull { it.isNotBlank() } ?: message
                        Log.d("KtorMSGraphClient", firstLine)
                    }
                }
            }

            install(Auth) {
                bearer {
                    loadTokens {
                        microsoftKtorTokenProvider.loadBearerTokens()
                    }
                    refreshTokens {
                        microsoftKtorTokenProvider.refreshBearerTokens(this.oldTokens)
                    }
                    // Only send tokens to Microsoft Graph
                    sendWithoutRequest { request ->
                        request.url.host == "graph.microsoft.com"
                    }
                }
            }
        }
    }

    // MicrosoftErrorMapper and GraphApiHelper have @Inject constructors
    // So Hilt can provide them as parameters to @Provides methods.

    @Provides
    @Singleton
    // @ErrorMapperType("MS") // This qualifier is for direct injection, not strictly needed for map entry if only map is used
    @IntoMap
    @StringKey("MS")
    fun provideErrorMapperService(
        microsoftErrorMapper: MicrosoftErrorMapper // Hilt injects this
    ): ErrorMapperService {
        Log.i("BackendMSModule", "Providing ErrorMapperService for 'MS' key via @Provides")
        return microsoftErrorMapper
    }

    // TokenProvider binding removed - no longer needed as token management is handled by MicrosoftKtorTokenProvider

    @Provides
    @Singleton
    fun provideGraphApiHelper(
        @MicrosoftGraphHttpClient httpClient: HttpClient,
        microsoftErrorMapper: MicrosoftErrorMapper
    ): GraphApiHelper {
        Log.d("BackendMSModule", "Providing GraphApiHelper with MS Graph HTTPClient")
        return GraphApiHelper(httpClient, microsoftErrorMapper)
    }

    @Provides
    @Singleton
    // @ApiHelperType("MS") // For direct injection if needed
    @IntoMap
    @StringKey("MS")
    fun provideMailApiService(
        graphApiHelper: GraphApiHelper // Hilt injects this
    ): MailApiService {
        Log.i(
            "BackendMSModule",
            "Providing MailApiService for 'MS' key via @Provides (now Ktor Auth enabled)"
        )
        return graphApiHelper
    }
}