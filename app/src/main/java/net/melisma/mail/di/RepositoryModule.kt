package net.melisma.mail.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.R
import javax.inject.Singleton


// Definition of @ApplicationScope annotation REMOVED from here


/**
 * Hilt Module for providing Application-level dependencies like CoroutineScope and AuthManager.
 * Repository bindings are now moved to the specific backend modules (e.g., :backend-microsoft).
 */
@Module
@InstallIn(SingletonComponent::class)
class RepositoryModule {

    // @Binds methods REMOVED

    companion object {

        /**
         * Provides a singleton [CoroutineScope] tied to the application's lifecycle.
         * Uses a [SupervisorJob] so failure of one child coroutine doesn't cancel the scope.
         * Uses the IO dispatcher (provided by another module) as the default context.
         */
        @ApplicationScope // Use the qualifier (imported from core-data)
        @Provides
        @Singleton
        fun provideApplicationCoroutineScope(
            // Inject the dispatcher qualified from core-data, provided by backend-microsoft module
            @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher
        ): CoroutineScope {
            return CoroutineScope(SupervisorJob() + ioDispatcher)
        }

        /** Provides the singleton instance of [MicrosoftAuthManager] from the :feature-auth module. */
        @Provides
        @Singleton
        fun provideMicrosoftAuthManager(
            @ApplicationContext appContext: Context
        ): MicrosoftAuthManager {
            return MicrosoftAuthManager(
                context = appContext,
                configResId = R.raw.auth_config
            )
        }

        // provideGraphApiHelper REMOVED
        // provideIoDispatcher REMOVED
    }
}