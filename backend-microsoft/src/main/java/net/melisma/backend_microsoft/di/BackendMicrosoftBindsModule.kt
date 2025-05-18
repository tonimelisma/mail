package net.melisma.backend_microsoft.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.backend_microsoft.repository.MicrosoftAccountRepository
import net.melisma.core_data.di.MicrosoftRepo // Import the qualifier
import net.melisma.core_data.repository.AccountRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendMicrosoftBindsModule {

    @Binds
    @Singleton
    @MicrosoftRepo // Apply the qualifier
    abstract fun bindMicrosoftAccountRepository(
        impl: MicrosoftAccountRepository
    ): AccountRepository
} 