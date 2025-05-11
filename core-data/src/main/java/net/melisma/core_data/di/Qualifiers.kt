package net.melisma.core_data.di

import javax.inject.Qualifier

/**
 * Qualifier annotation for token provider type.
 * Used to distinguish between different implementations in a multi-binding map.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenProviderType(val value: String)

/**
 * Qualifier annotation for API helper type (MailApiService implementations).
 * Used to distinguish between different implementations in a multi-binding map.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiHelperType(val value: String)

/**
 * Qualifier annotation for ErrorMapperService type.
 * Used to distinguish between different implementations of ErrorMapperService in a multi-binding map.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ErrorMapperType(val value: String)
