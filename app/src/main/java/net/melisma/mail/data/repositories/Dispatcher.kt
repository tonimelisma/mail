package net.melisma.mail.data.repositories

// *** ADD THIS IMPORT ***
import javax.inject.Qualifier

/**
 * Qualifier annotation to distinguish different CoroutineDispatchers provided by Hilt.
 * Used in conjunction with MailDispatchers enum.
 *
 * Example Usage: @Inject @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
 */
@Qualifier // Ensure javax.inject.Qualifier is imported
@Retention(AnnotationRetention.BINARY) // Standard retention for qualifiers
annotation class Dispatcher(val dispatcher: MailDispatchers)

/**
 * Enum defining the types of dispatchers used in the application.
 */
enum class MailDispatchers {
    IO // Represents the dispatcher for I/O-bound tasks (network, disk)
    // Add DEFAULT, MAIN if needed later
}
