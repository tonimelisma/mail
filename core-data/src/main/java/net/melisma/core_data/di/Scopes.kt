package net.melisma.core_data.di // New package in core-data

import javax.inject.Qualifier

/**
 * Qualifier annotation for distinguishing the application-level CoroutineScope.
 * Defined in :core-data so it can be used by any module providing or consuming this scope.
 * Ensures that the correct scope is injected where needed (e.g., for long-running background tasks).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope