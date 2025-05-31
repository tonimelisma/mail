package net.melisma.backend_microsoft.di

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
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.backend_microsoft.auth.ActiveMicrosoftAccountHolder
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.auth.MicrosoftKtorTokenProvider
import net.melisma.backend_microsoft.auth.MicrosoftTokenPersistenceService
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.auth.NeedsReauthenticationException
import net.melisma.core_data.auth.TokenProviderException
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.core_data.errors.ErrorMapperService
import timber.log.Timber
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
        Timber.d(
            "BackendMicrosoftModule (object with @Provides) initialized by Hilt"
        )
    }

    @Provides
    @Singleton
    fun provideMicrosoftAuthManager(
        @ApplicationContext context: Context,
        authConfigProvider: AuthConfigProvider,
        @ApplicationScope externalScope: kotlinx.coroutines.CoroutineScope,
        tokenPersistenceService: MicrosoftTokenPersistenceService,
        activeMicrosoftAccountHolder: ActiveMicrosoftAccountHolder,
        @net.melisma.core_data.di.Dispatcher(net.melisma.core_data.di.MailDispatchers.IO) ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): MicrosoftAuthManager {
        Timber.d(
            "Providing MicrosoftAuthManager with correct dependencies"
        )
        return MicrosoftAuthManager(
            context,
            authConfigProvider,
            externalScope,
            tokenPersistenceService,
            activeMicrosoftAccountHolder,
            ioDispatcher
        )
    }

    @Provides
    @Singleton
    @MicrosoftGraphHttpClient
    fun provideMicrosoftGraphHttpClient(
        json: Json,
        microsoftKtorTokenProvider: MicrosoftKtorTokenProvider,
        microsoftAuthManager: MicrosoftAuthManager
    ): HttpClient {
        Timber.d("Providing Microsoft Graph HTTPClient with Auth plugin.")
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
                        Timber.tag("KtorMSGraphClient").d(firstLine)
                    }
                }
            }

            install(Auth) {
                bearer {
                    loadTokens {
                        Timber.tag("KtorAuth").d("MS Auth: loadTokens called.")
                        try {
                            microsoftKtorTokenProvider.getBearerTokens()
                        } catch (e: NeedsReauthenticationException) {
                            Timber.tag("KtorAuth")
                                .w(e, "MS Auth: Needs re-authentication during loadTokens.")
                            null
                        } catch (e: TokenProviderException) {
                            Timber.tag("KtorAuth")
                                .e(e, "MS Auth: TokenProviderException during loadTokens.")
                            null
                        } catch (e: Exception) {
                            Timber.tag("KtorAuth")
                                .e(e, "MS Auth: Unexpected exception during loadTokens.")
                            null
                        }
                    }
                    refreshTokens {
                        Timber.tag("KtorAuth").d(
                            "MS Auth: refreshTokens called. Old access token: ${
                                this.oldTokens?.accessToken?.take(10)
                            }..."
                        )
                        try {
                            microsoftKtorTokenProvider.refreshBearerTokens(this.oldTokens)
                        } catch (e: NeedsReauthenticationException) {
                            Timber.tag("KtorAuth")
                                .w(e, "MS Auth: Needs re-authentication during refreshTokens.")
                            null
                        } catch (e: TokenProviderException) {
                            Timber.tag("KtorAuth")
                                .e(e, "MS Auth: TokenProviderException during refreshTokens.")
                            null
                        } catch (e: Exception) {
                            Timber.tag("KtorAuth")
                                .e(e, "MS Auth: Unexpected exception during refreshTokens.")
                            null
                        }
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
        Timber.i("Providing ErrorMapperService for 'MS' key via @Provides")
        return microsoftErrorMapper
    }

    // TokenProvider binding removed - no longer needed as token management is handled by MicrosoftKtorTokenProvider

    @Provides
    @Singleton
    fun provideGraphApiHelper(
        @MicrosoftGraphHttpClient httpClient: HttpClient,
        microsoftErrorMapper: MicrosoftErrorMapper
    ): GraphApiHelper {
        Timber.d("Providing GraphApiHelper with MS Graph HTTPClient")
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
        Timber.i(
            "Providing MailApiService for 'MS' key via @Provides (now Ktor Auth enabled)"
        )
        return graphApiHelper
    }
}