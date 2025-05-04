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
import net.melisma.mail.data.repositories.MessageRepository // *** ADDED IMPORT ***
import net.melisma.mail.data.repositories.MicrosoftFolderRepository
import net.melisma.mail.data.repositories.MicrosoftMessageRepository // *** ADDED IMPORT ***
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

    // *** ADDED BINDING for MessageRepository ***
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MicrosoftMessageRepository): MessageRepository

    // Companion object is used for @Provides methods within an abstract module.
    companion object {

        /** Provides the IO dispatcher for background tasks like network calls. */
        @Provides
        @Singleton
        @Dispatcher(MailDispatchers.IO)
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        /** Provides a singleton CoroutineScope bound to the application's lifecycle. */
        @ApplicationScope
        @Provides
        @Singleton
        fun provideApplicationCoroutineScope(
            @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher
        ): CoroutineScope {
            return CoroutineScope(SupervisorJob() + ioDispatcher)
        }

        /** Provides the singleton instance of MicrosoftAuthManager. */
        @Provides
        @Singleton
        fun provideMicrosoftAuthManager(
            @ApplicationContext appContext: Context
        ): MicrosoftAuthManager {
            return MicrosoftAuthManager(
                context = appContext,
                configResId = R.raw.auth_config
            )
        }

        /** Provides the GraphApiHelper object (singleton). */
        @Provides
        fun provideGraphApiHelper(): GraphApiHelper {
            return GraphApiHelper
        }
    }
}
