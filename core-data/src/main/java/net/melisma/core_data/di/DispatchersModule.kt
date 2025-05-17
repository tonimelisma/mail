package net.melisma.core_data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/**
 * Hilt module responsible for providing CoroutineDispatcher instances.
 * This module is installed in the SingletonComponent, meaning the provided dispatchers
 * will be available as singletons throughout the application lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class) // Makes these bindings available at the application level
object DispatchersModule { // 'object' for modules with only @Provides methods

    /**
     * Provides an IO CoroutineDispatcher.
     * This dispatcher is optimized for I/O-bound work (network requests, disk operations).
     * It's qualified with `@Dispatcher(MailDispatchers.IO)` to distinguish it from
     * other dispatchers if any are provided (e.g., Main, Default).
     *
     * @return A CoroutineDispatcher suitable for IO tasks.
     */
    @Provides
    @Dispatcher(MailDispatchers.IO) // Apply the custom qualifier
    @Singleton // Ensure this dispatcher is a singleton
    fun provideIoDispatcher(): CoroutineDispatcher {
        // Return the standard IO dispatcher from kotlinx.coroutines
        return kotlinx.coroutines.Dispatchers.IO
    }

    // --- Optional: Example of providing other dispatchers if needed in the future ---
    //
    // /**
    //  * Provides a Default CoroutineDispatcher.
    //  * This dispatcher is optimized for CPU-intensive work.
    //  */
    // @Provides
    // @Dispatcher(MailDispatchers.DEFAULT) // Assuming you add DEFAULT to your MailDispatchers enum
    // @Singleton
    // fun provideDefaultDispatcher(): CoroutineDispatcher {
    //     return kotlinx.coroutines.Dispatchers.Default
    // }
    //
    // /**
    //  * Provides a Main CoroutineDispatcher.
    //  * This dispatcher is used for UI-related tasks.
    //  * Note: For Android, Hilt often handles the Main dispatcher implicitly for ViewModels,
    //  * but explicit provision might be needed in other contexts.
    //  */
    // @Provides
    // @Dispatcher(MailDispatchers.MAIN) // Assuming you add MAIN to your MailDispatchers enum
    // @Singleton
    // fun provideMainDispatcher(): CoroutineDispatcher {
    //     return kotlinx.coroutines.Dispatchers.Main
    // }
} 