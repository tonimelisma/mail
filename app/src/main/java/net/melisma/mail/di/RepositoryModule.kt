package net.melisma.mail.di

// <<< REMOVE import for MicrosoftAuthManager from feature_auth
// import net.melisma.feature_auth.MicrosoftAuthManager
// <<< ADD import for the new AuthConfigProvider interface
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.melisma.backend_microsoft.di.AuthConfigProvider
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.mail.R
import javax.inject.Singleton


/**
 * Hilt Module for providing Application-level dependencies like CoroutineScope.
 * The AuthManager provider is now removed, and AuthConfigProvider is added.
 */
@Module
@InstallIn(SingletonComponent::class)
class RepositoryModule {

    // @Binds methods previously REMOVED - stays removed

    companion object {

        // provideApplicationCoroutineScope (No changes needed here)
        @ApplicationScope
        @Provides
        @Singleton
        fun provideApplicationCoroutineScope(
            @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher
        ): CoroutineScope {
            return CoroutineScope(SupervisorJob() + ioDispatcher)
        }

        // <<< REMOVED: Old provider for MicrosoftAuthManager >>>
        /*
        @Provides
        @Singleton
        fun provideMicrosoftAuthManager(
            @ApplicationContext appContext: Context
        ): MicrosoftAuthManager {
            return MicrosoftAuthManager(
                context = appContext,
                configResId = R.raw.auth_config // This was the problem part
            )
        }
        */

        // <<< ADDED: Provider for AuthConfigProvider interface >>>
        /** Provides the implementation for AuthConfigProvider, sourcing the ID from app resources. */
        @Provides
        @Singleton
        fun provideAuthConfigProvider(): AuthConfigProvider {
            return object : AuthConfigProvider {
                // Provide the actual resource ID from the :app module's R class
                override fun getMsalConfigResId(): Int = R.raw.auth_config
            }
        }

        // Other providers like provideGraphApiHelper, provideIoDispatcher were previously REMOVED - stay removed
    }
}