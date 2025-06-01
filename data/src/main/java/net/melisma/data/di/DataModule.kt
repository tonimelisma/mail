package net.melisma.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.data.repository.DefaultAccountRepository
import net.melisma.data.repository.DefaultFolderRepository
import net.melisma.data.repository.DefaultMessageRepository
import net.melisma.data.repository.DefaultThreadRepository
import javax.inject.Singleton

/**
 * Hilt module that binds repository interfaces from :core-data to their
 * default implementations in the :data module.
 *
 * This module centralizes all repository implementations regardless of the
 * backend provider (Microsoft, Google, etc.).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    /**
     * Binds the implementation of AccountRepository from this module.
     */
    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: DefaultAccountRepository): AccountRepository

    /**
     * Binds the implementation of FolderRepository from this module.
     */
    @Binds
    @Singleton
    abstract fun bindFolderRepository(impl: DefaultFolderRepository): FolderRepository

    /**
     * Binds the implementation of MessageRepository from this module.
     */
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: DefaultMessageRepository): MessageRepository

    /**
     * Binds the implementation of ThreadRepository from this module.
     */
    @Binds
    @Singleton
    abstract fun bindThreadRepository(impl: DefaultThreadRepository): ThreadRepository

    // TODO: P1_SYNC - Provide SyncEngine.
    // SyncEngine is @Singleton and uses @Inject for its constructor,
    // so Hilt will provide it automatically as long as its dependencies
    // (WorkManager, DAOs, MailApiServiceSelector) are available in the graph.
    // A MailApiServiceSelector might need its own provider if it's an interface or complex setup.
}