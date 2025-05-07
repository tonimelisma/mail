package net.melisma.core_data.di

import javax.inject.Scope

/**
 * Custom Hilt scope annotation for application-level lifecycle.
 * Ensures that dependencies annotated with this scope live as long as the application.
 * Used for providing an application-level CoroutineScope.
 */
@Scope // <<< CORRECT ANNOTATION FOR A SCOPE
@Retention(AnnotationRetention.RUNTIME) // Standard for custom Hilt scopes
annotation class ApplicationScope