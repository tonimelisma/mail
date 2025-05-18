package net.melisma.core_data.common

/**
 * A generic sealed class to represent the result of a persistence operation.
 * It can be either Success, holding data of type T, or Failure, holding an errorType of enum E.
 *
 * @param T The type of the data in case of success.
 */
sealed class PersistenceResult<out T> {
    /**
     * Represents a successful persistence operation.
     * @param data The data that was persisted or retrieved.
     */
    data class Success<T>(val data: T) : PersistenceResult<T>()

    /**
     * Represents a failed persistence operation.
     * @param E The type of the error enum, specific to the module using this result.
     * @param errorType The specific type of error that occurred.
     * @param message An optional descriptive message for the error.
     * @param cause An optional Throwable that caused the failure.
     */
    data class Failure<E : Enum<E>>(
        val errorType: E,
        val message: String? = null,
        val cause: Throwable? = null
    ) : PersistenceResult<Nothing>()
}

// Convenience type aliases for common use cases
typealias UnitPersistenceResult<E> = PersistenceResult<Unit>
typealias StringPersistenceResult<E> = PersistenceResult<String>
// Add more as needed, e.g., ListPersistenceResult<T, E> = PersistenceResult<List<T>, E>

/**
 * A dummy enum that can be used for the E type parameter in PersistenceResult.Success
 * when the error type is not relevant for the success case.
 * This helps satisfy the generic constraint if E is non-nullable in the sealed class definition,
 * although type inference or using 'Nothing' might also work depending on usage.
 *
 * For the current PersistenceResult definition, Success<T, E> requires E.
 * If Success truly never cares about E, the sealed class could be defined differently,
 * or we ensure that 'Nothing' can be inferred or used as E for Success.
 *
 * Using a concrete, though empty, enum for the Success<T, E> case if E must be a non-nullable Enum.
 */
enum class NoErrorTypeEnum  // This can be used as E in Success if E is strictly Enum<E> and non-nullable.

// Revised PersistenceResult to simplify Success not needing E explicitly in its signature
// if E is primarily for Failure.
// Let's use the structure from Microsoft refactoring, it was simpler and worked.

/*
This is the structure used in Microsoft refactoring and which is simpler:
package net.melisma.core_data.common

sealed class PersistenceResult<out T> {
    data class Success<T>(val data: T) : PersistenceResult<T>()
    data class Failure<E : Enum<E>>( // E is the specific Error enum for the module
        val errorType: E,
        val message: String? = null,
        val cause: Throwable? = null
    ) : PersistenceResult<Nothing>()
}
This is the one I will implement.
*/ 