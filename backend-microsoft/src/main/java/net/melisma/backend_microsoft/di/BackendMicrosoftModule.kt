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
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.datasource.MicrosoftTokenProvider
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.core_data.errors.ErrorMapperService
import javax.inject.Singleton

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
        authConfigProvider: AuthConfigProvider
    ): MicrosoftAuthManager {
        Log.d("BackendMSModule", "Providing MicrosoftAuthManager")
        return MicrosoftAuthManager(context, authConfigProvider)
    }

    // MicrosoftErrorMapper, MicrosoftTokenProvider, GraphApiHelper all have @Inject constructors
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

    @Provides
    @Singleton
    // @TokenProviderType("MS") // For direct injection if needed
    @IntoMap
    @StringKey("MS")
    fun provideTokenProvider(
        microsoftTokenProvider: MicrosoftTokenProvider // Hilt injects this
    ): TokenProvider {
        Log.i("BackendMSModule", "Providing TokenProvider for 'MS' key via @Provides")
        return microsoftTokenProvider
    }

    @Provides
    @Singleton
    // @ApiHelperType("MS") // For direct injection if needed
    @IntoMap
    @StringKey("MS")
    fun provideMailApiService(
        graphApiHelper: GraphApiHelper // Hilt injects this
    ): MailApiService {
        Log.i("BackendMSModule", "Providing MailApiService for 'MS' key via @Provides")
        return graphApiHelper
    }
}