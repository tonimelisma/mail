package net.melisma.domain.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.domain.account.GetAccountsUseCase
import net.melisma.domain.account.ObserveAuthStateUseCase
import net.melisma.domain.account.SignInUseCase
import net.melisma.domain.account.SignOutUseCase
import net.melisma.domain.actions.DeleteMessageUseCase
import net.melisma.domain.actions.DeleteThreadUseCase
import net.melisma.domain.actions.MarkMessageAsReadUseCase
import net.melisma.domain.actions.MarkThreadAsReadUseCase
import net.melisma.domain.actions.MoveMessageUseCase
import net.melisma.domain.actions.MoveThreadUseCase
import net.melisma.domain.data.GetMessageDetailsUseCase
import net.melisma.domain.data.ObserveFoldersForAccountUseCase
import net.melisma.domain.data.ObserveMessagesForFolderUseCase
import net.melisma.domain.data.ObserveThreadsForFolderUseCase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideObserveAuthStateUseCase(accountRepository: AccountRepository): ObserveAuthStateUseCase {
        return ObserveAuthStateUseCase(accountRepository)
    }

    @Provides
    @Singleton
    fun provideGetAccountsUseCase(accountRepository: AccountRepository): GetAccountsUseCase {
        return GetAccountsUseCase(accountRepository)
    }

    @Provides
    @Singleton
    fun provideSignInUseCase(accountRepository: AccountRepository): SignInUseCase {
        return SignInUseCase(accountRepository)
    }

    @Provides
    @Singleton
    fun provideSignOutUseCase(accountRepository: AccountRepository): SignOutUseCase {
        return SignOutUseCase(accountRepository)
    }

    @Provides
    @Singleton
    fun provideObserveFoldersForAccountUseCase(folderRepository: FolderRepository): ObserveFoldersForAccountUseCase {
        return ObserveFoldersForAccountUseCase(folderRepository)
    }

    @Provides
    @Singleton
    fun provideObserveMessagesForFolderUseCase(messageRepository: MessageRepository): ObserveMessagesForFolderUseCase {
        return ObserveMessagesForFolderUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideObserveThreadsForFolderUseCase(threadRepository: ThreadRepository): ObserveThreadsForFolderUseCase {
        return ObserveThreadsForFolderUseCase(threadRepository)
    }

    @Provides
    @Singleton
    fun provideGetMessageDetailsUseCase(messageRepository: MessageRepository): GetMessageDetailsUseCase {
        return GetMessageDetailsUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideMarkMessageAsReadUseCase(messageRepository: MessageRepository): MarkMessageAsReadUseCase {
        return MarkMessageAsReadUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideDeleteMessageUseCase(messageRepository: MessageRepository): DeleteMessageUseCase {
        return DeleteMessageUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideMoveMessageUseCase(messageRepository: MessageRepository): MoveMessageUseCase {
        return MoveMessageUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideMarkThreadAsReadUseCase(threadRepository: ThreadRepository): MarkThreadAsReadUseCase {
        return MarkThreadAsReadUseCase(threadRepository)
    }

    @Provides
    @Singleton
    fun provideDeleteThreadUseCase(threadRepository: ThreadRepository): DeleteThreadUseCase {
        return DeleteThreadUseCase(threadRepository)
    }

    @Provides
    @Singleton
    fun provideMoveThreadUseCase(threadRepository: ThreadRepository): MoveThreadUseCase {
        return MoveThreadUseCase(threadRepository)
    }
} 