package net.melisma.core_data.di // New package in core-data

import javax.inject.Qualifier

/**
 * Qualifier annotation to distinguish different CoroutineDispatchers provided by Hilt.
 * Defined in :core-data so it can be used by any module providing or consuming dispatchers.
 *
 * Example Usage: `@Inject @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher`
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Dispatcher(val dispatcher: MailDispatchers)

/**
 * Enum defining the types of dispatchers used in the application.
 * Defined in :core-data for common access.
 */
enum class MailDispatchers {
    /** Represents the dispatcher optimized for I/O-bound tasks (network, disk). */
    IO
    // Add DEFAULT, MAIN if needed later for CPU-bound or UI tasks respectively.
}