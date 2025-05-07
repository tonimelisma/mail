package net.melisma.backend_microsoft.di

// Import the interface from its own file
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.datasource.MicrosoftTokenProvider
import net.melisma.backend_microsoft.errors.MicrosoftErrorMapper
import net.melisma.core_common.errors.ErrorMapperService
import net.melisma.core_data.datasource.TokenProvider
import javax.inject.Singleton

// REMOVED Duplicate Interface Definition:
// interface AuthConfigProvider {
//     fun getMsalConfigResId(): Int
// }

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendMicrosoftBindingModule {

    @Binds
    @Singleton
    abstract fun bindErrorMapperService(
        microsoftErrorMapper: MicrosoftErrorMapper
    ): ErrorMapperService

    @Binds
    @Singleton
    abstract fun bindTokenProvider(
        microsoftTokenProvider: MicrosoftTokenProvider
    ): TokenProvider
}

@Module
@InstallIn(SingletonComponent::class)
object BackendMicrosoftProvidesModule {

    @Provides
    @Singleton
    fun provideMicrosoftAuthManager(
        @ApplicationContext context: Context,
        authConfigProvider: AuthConfigProvider // Inject the imported interface
    ): MicrosoftAuthManager {
        return MicrosoftAuthManager(context, authConfigProvider)
    }

    // Note: MicrosoftTokenProvider, MicrosoftErrorMapper, and GraphApiHelper
    // use @Inject constructor(...) and Hilt provides them automatically.
}
