package net.melisma.data.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.errors.ErrorMapperService

/**
 * This module declares the multi-bound maps for dependency injection.
 * It tells Hilt that we're going to be using multi-binding for these types.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MultiBindingModule {

    // TokenProvider multibinding removed - no longer needed as token management is handled by KtorTokenProviders

    /**
     * Declares a map binding for MailApiService implementations keyed by provider type.
     */
    @Multibinds
    abstract fun mailApiServices(): Map<String, MailApiService>

    /**
     * Declares a map binding for ErrorMapperService implementations keyed by provider type.
     */
    @Multibinds // ADDED: Multibinding for ErrorMapperService map
    abstract fun errorMapperServices(): Map<String, @JvmSuppressWildcards ErrorMapperService>
}