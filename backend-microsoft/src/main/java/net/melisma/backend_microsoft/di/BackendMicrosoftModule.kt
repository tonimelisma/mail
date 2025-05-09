// File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/di/BackendMicrosoftModule.kt
package net.melisma.backend_microsoft.di

// HttpClient and Json will be provided by NetworkModule.kt, so remove Ktor imports if not used elsewhere here.
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.datasource.MicrosoftTokenProvider
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.ApiHelperType
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.core_data.di.TokenProviderType
import net.melisma.core_data.errors.ErrorMapperService
import javax.inject.Singleton

// This module binds interfaces to their concrete implementations within this backend module.
@Module
@InstallIn(SingletonComponent::class)
abstract class BackendMicrosoftBindingModule {

    @Binds
    @Singleton
    abstract fun bindErrorMapperService(
        microsoftErrorMapper: MicrosoftErrorMapper // Provided via @Inject constructor
    ): ErrorMapperService

    @Binds
    @TokenProviderType("MS")
    @IntoMap
    @StringKey("MS")
    @Singleton
    abstract fun bindTokenProvider(
        microsoftTokenProvider: MicrosoftTokenProvider // Provided via @Inject constructor
    ): TokenProvider

    @Binds
    @ApiHelperType("MS")
    @IntoMap
    @StringKey("MS")
    @Singleton
    abstract fun bindMailApiService(
        graphApiHelper: GraphApiHelper // Provided via @Inject constructor
    ): MailApiService
}

// This module provides instances of classes that need explicit construction.
@Module
@InstallIn(SingletonComponent::class)
object BackendMicrosoftProvidesModule {

    @Provides
    @Singleton
    fun provideMicrosoftAuthManager(
        @ApplicationContext context: Context,
        authConfigProvider: AuthConfigProvider // Injected by Hilt (implementation from :app module)
    ): MicrosoftAuthManager {
        return MicrosoftAuthManager(context, authConfigProvider)
    }

    // REMOVED: provideKtorJson() - This will now be provided by NetworkModule.kt
    // REMOVED: provideKtorHttpClient() - This will now be provided by NetworkModule.kt

    // GraphApiHelper, MicrosoftTokenProvider, MicrosoftErrorMapper are assumed to use @Inject constructor.
    // If GraphApiHelper needs HttpClient, Hilt will inject it from NetworkModule.
}
