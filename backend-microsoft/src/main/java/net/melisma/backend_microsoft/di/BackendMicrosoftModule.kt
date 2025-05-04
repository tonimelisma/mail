package net.melisma.backend_microsoft.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.melisma.backend_microsoft.GraphApiHelper
import net.melisma.backend_microsoft.datasource.MicrosoftTokenProvider
import net.melisma.backend_microsoft.errors.ErrorMapper
import net.melisma.backend_microsoft.repository.MicrosoftAccountRepository
import net.melisma.backend_microsoft.repository.MicrosoftFolderRepository
import net.melisma.backend_microsoft.repository.MicrosoftMessageRepository
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.di.Dispatcher // Import qualifier from core-data
import net.melisma.core_data.di.MailDispatchers // Import enum from core-data
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import javax.inject.Singleton

/** Hilt Module for providing dependencies specific to the Microsoft backend implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackendMicrosoftModule {

    /** Binds the core AccountRepository interface to the Microsoft implementation. */
    @Binds
    @Singleton
    abstract fun bindAccountRepository(
        impl: MicrosoftAccountRepository
    ): AccountRepository

    /** Binds the core FolderRepository interface to the Microsoft implementation. */
    @Binds
    @Singleton
    abstract fun bindFolderRepository(
        impl: MicrosoftFolderRepository
    ): FolderRepository

    /** Binds the core MessageRepository interface to the Microsoft implementation. */
    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        impl: MicrosoftMessageRepository
    ): MessageRepository

    /** Binds the core TokenProvider interface to the Microsoft implementation. */
    @Binds
    @Singleton
    abstract fun bindTokenProvider(
        impl: MicrosoftTokenProvider
    ): TokenProvider

    // Companion object for @Provides methods
    companion object {

        /** Provides the IO CoroutineDispatcher. */
        @Provides
        @Singleton
        @Dispatcher(MailDispatchers.IO) // Use the qualifier annotation (imported from core-data)
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        /** Provides the singleton GraphApiHelper class. */
        @Provides
        @Singleton // Add Singleton as GraphApiHelper is now a class
        fun provideGraphApiHelper(): GraphApiHelper {
            // Hilt will inject constructor dependencies if any were added later
            return GraphApiHelper()
        }

        /** Provides the singleton ErrorMapper object. */
        @Provides
        // @Singleton // Not needed for Kotlin objects
        fun provideErrorMapper(): ErrorMapper {
            return ErrorMapper
        }
    }
}