package net.melisma.core_data.di // Or your common DI module location

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.preferences.DefaultUserPreferencesRepository
import net.melisma.core_data.preferences.UserPreferencesRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesRepositoryModule { // Changed to abstract class for @Binds
    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(
        impl: DefaultUserPreferencesRepository
    ): UserPreferencesRepository
} 