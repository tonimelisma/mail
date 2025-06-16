package net.melisma.domain.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_data.repository.ThreadRepository

// Existing Use Case Class Imports (Account)
import net.melisma.domain.account.GetAccountsUseCase
import net.melisma.domain.account.ObserveAuthStateUseCase
import net.melisma.domain.account.SignInUseCase
import net.melisma.domain.account.SignOutUseCase
import net.melisma.domain.account.SignOutAllMicrosoftAccountsUseCase

// Existing Use Case Class Imports (Actions)
import net.melisma.domain.actions.DeleteMessageUseCase
import net.melisma.domain.actions.DeleteThreadUseCase
import net.melisma.domain.actions.MarkMessageAsReadUseCase
import net.melisma.domain.actions.MarkThreadAsReadUseCase
import net.melisma.domain.actions.MoveMessageUseCase
import net.melisma.domain.actions.MoveThreadUseCase

// Existing Use Case Class Imports (Data)
import net.melisma.domain.data.GetMessageDetailsUseCase
import net.melisma.domain.data.ObserveFoldersForAccountUseCase
import net.melisma.domain.data.ObserveMessagesForFolderUseCase
import net.melisma.domain.data.ObserveThreadsForFolderUseCase

// New Use Case Interface Imports (Data)
import net.melisma.domain.data.GetMessageAttachmentsUseCase
import net.melisma.domain.data.GetThreadsInFolderUseCase
import net.melisma.domain.data.SearchMessagesUseCase
// New Use Case Default Implementation Imports (Data)
import net.melisma.domain.data.DefaultGetMessageAttachmentsUseCase
import net.melisma.domain.data.DefaultGetThreadsInFolderUseCase
import net.melisma.domain.data.DefaultSearchMessagesUseCase

// New Use Case Interface Imports (Actions)
import net.melisma.domain.actions.CreateDraftUseCase
import net.melisma.domain.actions.DownloadAttachmentUseCase
import net.melisma.domain.actions.SaveDraftUseCase
import net.melisma.domain.actions.SendMessageUseCase
import net.melisma.domain.actions.SyncAccountUseCase
import net.melisma.domain.actions.SyncFolderUseCase
// New Use Case Default Implementation Imports (Actions)
import net.melisma.domain.actions.DefaultCreateDraftUseCase
import net.melisma.domain.actions.DefaultDownloadAttachmentUseCase
import net.melisma.domain.actions.DefaultSaveDraftUseCase
import net.melisma.domain.actions.DefaultSendMessageUseCase
import net.melisma.domain.actions.DefaultSyncAccountUseCase
import net.melisma.domain.actions.DefaultSyncFolderUseCase

import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    // Existing Account UseCases (provide the class directly)
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
    fun provideSignOutAllMicrosoftAccountsUseCase(accountRepository: AccountRepository): SignOutAllMicrosoftAccountsUseCase {
        return SignOutAllMicrosoftAccountsUseCase(accountRepository)
    }

    // Existing Data UseCases (provide the class directly)
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

    // Existing Action UseCases (provide the class directly)
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

    // New Data UseCases (provide Default implementation for the interface)
    @Provides
    @Singleton
    fun provideGetThreadsInFolderUseCase(folderRepository: FolderRepository): GetThreadsInFolderUseCase {
        return DefaultGetThreadsInFolderUseCase(folderRepository)
    }

    @Provides
    @Singleton
    fun provideSearchMessagesUseCase(messageRepository: MessageRepository): SearchMessagesUseCase {
        return DefaultSearchMessagesUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideGetMessageAttachmentsUseCase(messageRepository: MessageRepository): GetMessageAttachmentsUseCase {
        return DefaultGetMessageAttachmentsUseCase(messageRepository)
    }

    // New Action UseCases (provide Default implementation for the interface)
    @Provides
    @Singleton
    fun provideSyncFolderUseCase(folderRepository: FolderRepository): SyncFolderUseCase {
        return DefaultSyncFolderUseCase(folderRepository)
    }

    @Provides
    @Singleton
    fun provideSyncAccountUseCase(accountRepository: AccountRepository): SyncAccountUseCase {
        return DefaultSyncAccountUseCase(accountRepository)
    }

    @Provides
    @Singleton
    fun provideCreateDraftUseCase(messageRepository: MessageRepository): CreateDraftUseCase {
        return DefaultCreateDraftUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideSaveDraftUseCase(messageRepository: MessageRepository): SaveDraftUseCase {
        return DefaultSaveDraftUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideSendMessageUseCase(messageRepository: MessageRepository): SendMessageUseCase {
        return DefaultSendMessageUseCase(messageRepository)
    }

    @Provides
    @Singleton
    fun provideDownloadAttachmentUseCase(messageRepository: MessageRepository): DownloadAttachmentUseCase {
        return DefaultDownloadAttachmentUseCase(messageRepository)
    }
}