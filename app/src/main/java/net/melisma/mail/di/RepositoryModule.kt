package net.melisma.mail.di

// No longer needs Context import unless used for other providers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
// No longer needs @ApplicationContext unless used for other providers
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // Use standard Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.melisma.backend_microsoft.di.AuthConfigProvider // Import the INTERFACE defined in the backend module
import net.melisma.core_data.di.ApplicationScope // Qualifier for Scope
import net.melisma.core_data.di.Dispatcher // Qualifier for Dispatcher
import net.melisma.core_data.di.MailDispatchers // Enum for Dispatcher types
import net.melisma.mail.R // Import app's R class to provide the resource ID
import javax.inject.Singleton


/**
 * Hilt Module for providing Application-level dependencies.
 * Provides the AuthConfigProvider implementation needed by backend modules,
 * the application-wide CoroutineScope, and Dispatchers.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule { // Changed to object as it only contains @Provides

    // Provide the application-level CoroutineScope, qualified by @ApplicationScope
    @ApplicationScope
    @Provides
    @Singleton
    fun provideApplicationCoroutineScope(
        @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher // Inject the IO dispatcher
    ): CoroutineScope {
        // Use SupervisorJob + IO Dispatcher for background tasks independent of ViewModel lifecycle
        return CoroutineScope(SupervisorJob() + ioDispatcher)
    }

    // Provide the IO Dispatcher, qualified by @Dispatcher
    @Dispatcher(MailDispatchers.IO)
    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    // Provide the concrete implementation of AuthConfigProvider needed by MicrosoftAuthManager
    @Provides
    @Singleton
    fun provideAuthConfigProvider(): AuthConfigProvider {
        // This object implements the interface defined in :backend-microsoft
        // and provides the actual resource ID from this :app module.
        return object : AuthConfigProvider {
            override fun getMsalConfigResId(): Int = R.raw.auth_config
        }
    }

    // NO LONGER PROVIDES/BINDS:
    // - MicrosoftAuthManager (provided in :backend-microsoft)
    // - Repositories (will be bound in :data)
    // - GraphApiHelper (uses @Inject constructor)
}
