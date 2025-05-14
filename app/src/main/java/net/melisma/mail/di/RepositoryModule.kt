// File: app/src/main/java/net/melisma/mail/di/RepositoryModule.kt
package net.melisma.mail.di

// import net.melisma.core_data.di.ApplicationScope // No longer using @ApplicationScope on the @Provides method
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
// Removed AccountRepository, FolderRepository, MessageRepository imports as their bindings are removed
import net.melisma.core_data.repository.ThreadRepository
// Removed DefaultAccountRepository, DefaultFolderRepository, DefaultMessageRepository imports
import net.melisma.data.repository.DefaultThreadRepository
import javax.inject.Singleton

/**
 * Hilt Module for providing Application-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    // Removed bindAccountRepository

    // Removed bindFolderRepository

    // Removed bindMessageRepository

    @Binds
    @Singleton
    abstract fun bindThreadRepository(impl: DefaultThreadRepository): ThreadRepository
}
