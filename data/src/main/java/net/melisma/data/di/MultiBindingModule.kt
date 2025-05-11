package net.melisma.data.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.TokenProvider
import net.melisma.core_data.errors.ErrorMapperService // ADDED: Import for ErrorMapperService

/**
 * This module declares the multi-bound maps for dependency injection.
 * It tells Hilt that we're going to be using multi-binding for these types.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MultiBindingModule {

    /**
     * Declares a map binding for TokenProvider implementations keyed by provider type.
     */
    @Multibinds
    abstract fun tokenProviders(): Map<String, TokenProvider>

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