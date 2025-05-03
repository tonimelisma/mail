package net.melisma.mail.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.AccountRepository
import net.melisma.mail.GraphApiHelper
import net.melisma.mail.MicrosoftAccountRepository
import net.melisma.mail.R
import net.melisma.mail.data.datasources.MicrosoftTokenProvider
import net.melisma.mail.data.datasources.TokenProvider
import net.melisma.mail.data.repositories.Dispatcher // Import Dispatcher annotation
import net.melisma.mail.data.repositories.FolderRepository
import net.melisma.mail.data.repositories.MailDispatchers // Import MailDispatchers enum
import net.melisma.mail.data.repositories.MicrosoftFolderRepository
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier annotation for distinguishing the application-level CoroutineScope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt Module responsible for providing repository implementations and related dependencies.
 * Uses @Binds for interface-implementation bindings and @Provides for concrete instances.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    // Binds the AccountRepository interface to its Microsoft implementation.
    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: MicrosoftAccountRepository): AccountRepository

    // Binds the FolderRepository interface to its Microsoft implementation.
    @Binds
    @Singleton
    abstract fun bindFolderRepository(impl: MicrosoftFolderRepository): FolderRepository

    // Binds the TokenProvider interface to its Microsoft implementation.
    @Binds
    @Singleton
    abstract fun bindTokenProvider(impl: MicrosoftTokenProvider): TokenProvider

    // Companion object is used for @Provides methods within an abstract module.
    companion object {

        /** Provides the IO dispatcher for background tasks like network calls. */
        @Provides
        @Singleton // Ensure single IO dispatcher instance
        @Dispatcher(MailDispatchers.IO) // Qualify the IO dispatcher
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        /** Provides a singleton CoroutineScope bound to the application's lifecycle. */
        @ApplicationScope // Use custom qualifier
        @Provides
        @Singleton
        fun provideApplicationCoroutineScope(
            @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher // Inject qualified dispatcher
        ): CoroutineScope {
            // Use SupervisorJob so failure of one child job doesn't cancel the whole scope.
            // Combine with the injected IO dispatcher.
            return CoroutineScope(SupervisorJob() + ioDispatcher)
        }

        /** Provides the singleton instance of MicrosoftAuthManager. */
        @Provides
        @Singleton
        fun provideMicrosoftAuthManager(
            @ApplicationContext appContext: Context // Inject application context
        ): MicrosoftAuthManager {
            // Instantiate the auth manager with context and config resource ID.
            return MicrosoftAuthManager(
                context = appContext,
                configResId = R.raw.auth_config // Ensure R.raw.auth_config exists
            )
        }

        /** Provides the GraphApiHelper object (singleton). */
        @Provides
        // Singleton scope isn't strictly needed for Kotlin objects, but doesn't hurt.
        fun provideGraphApiHelper(): GraphApiHelper {
            // GraphApiHelper is an object, so we just return the instance.
            return GraphApiHelper
        }
    }
}
