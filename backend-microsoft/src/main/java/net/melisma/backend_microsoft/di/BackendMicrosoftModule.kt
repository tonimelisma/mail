package net.melisma.backend_microsoft.di

// <<< ADD IMPORT for MicrosoftAuthManager from its new location
// <<< ADD IMPORT for AuthConfigProvider (adjust if placed elsewhere)
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.backend_microsoft.auth.MicrosoftAuthManager
import net.melisma.backend_microsoft.datasource.MicrosoftTokenProvider
import net.melisma.backend_microsoft.errors.ErrorMapper
import net.melisma.backend_microsoft.repository.MicrosoftAccountRepository
import net.melisma.backend_microsoft.repository.MicrosoftFolderRepository
import net.melisma.backend_microsoft.repository.MicrosoftMessageRepository
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Singleton

/** Hilt Module for providing dependencies specific to the Microsoft backend implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackendMicrosoftModule {

    // --- Binds (No changes needed here) ---
    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: MicrosoftAccountRepository): AccountRepository
    @Binds
    @Singleton
    abstract fun bindFolderRepository(impl: MicrosoftFolderRepository): FolderRepository
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MicrosoftMessageRepository): MessageRepository
    @Binds
    @Singleton
    abstract fun bindTokenProvider(impl: MicrosoftTokenProvider): TokenProvider

    // Companion object for @Provides methods
    companion object {

        @Provides
        @Singleton
        @Dispatcher(MailDispatchers.IO)
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @Singleton
        fun provideGraphApiHelper(): GraphApiHelper {
            return GraphApiHelper()
        }

        @Provides // No Singleton needed for object
        fun provideErrorMapper(): ErrorMapper {
            return ErrorMapper
        }

        // <<< ADDED: Provider for MicrosoftAuthManager >>>
        /** Provides the singleton instance of [MicrosoftAuthManager]. */
        @Provides
        @Singleton
        fun provideMicrosoftAuthManager(
            @ApplicationContext appContext: Context, // Get application context via Hilt
            authConfigProvider: AuthConfigProvider // Inject the config provider interface
        ): MicrosoftAuthManager {
            // Construct with context and the provider interface
            return MicrosoftAuthManager(
                context = appContext,
                authConfigProvider = authConfigProvider
            )
        }
    }
}