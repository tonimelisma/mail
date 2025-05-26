package net.melisma.backend_microsoft.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.backend_microsoft.repository.MicrosoftAccountRepository
import net.melisma.core_data.di.MicrosoftRepo
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MicrosoftRepositoryModule {

    @Binds
    @Singleton
    @MicrosoftRepo
    abstract fun bindMicrosoftAccountRepository(
        impl: MicrosoftAccountRepository
    ): AccountRepository
} 