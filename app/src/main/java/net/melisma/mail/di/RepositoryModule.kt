// File: app/src/main/java/net/melisma/mail/di/RepositoryModule.kt
package net.melisma.mail.di

// import net.melisma.core_data.di.ApplicationScope // No longer using @ApplicationScope on the @Provides method
// import dagger.Binds // No Binds needed if all are removed
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
// Removed AccountRepository, FolderRepository, MessageRepository imports as their bindings are removed
// import net.melisma.core_data.repository.ThreadRepository // No longer bound here
// Removed DefaultAccountRepository, DefaultFolderRepository, DefaultMessageRepository imports
// import net.melisma.data.repository.DefaultThreadRepository // No longer bound here
// import javax.inject.Singleton // No longer needed if no @Singleton bindings

/**
 * Hilt Module for providing Application-level dependencies.
 * Repository bindings are now expected to be in their respective feature modules (e.g., :data module).
 */
@Module
@InstallIn(SingletonComponent::class)
// Make it an object if no @Binds methods remain, or remove if empty.
// For now, leave as abstract class, can be refactored if truly empty later.
abstract class RepositoryModule {

    // Removed bindAccountRepository

    // Removed bindFolderRepository

    // Removed bindMessageRepository

    // Removed bindThreadRepository
    // @Binds
    // @Singleton
    // abstract fun bindThreadRepository(impl: DefaultThreadRepository): ThreadRepository
}
