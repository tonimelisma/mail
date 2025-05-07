// File: app/src/main/java/net/melisma/mail/di/RepositoryModule.kt
package net.melisma.mail.di

// import net.melisma.core_data.di.ApplicationScope // No longer using @ApplicationScope on the @Provides method
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.mail.R
import javax.inject.Singleton

/**
 * Hilt Module for providing Application-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    /**
     * Provides the application-level CoroutineScope.
     * It's scoped with @Singleton as this module is installed in SingletonComponent.
     */
    // CHANGED @ApplicationScope to @Singleton
    @Singleton
    @Provides
    fun provideApplicationCoroutineScope(
        // You can use your @Dispatcher qualifier if you have multiple CoroutineDispatchers
        // and need to distinguish them. For a single IO dispatcher, it's fine.
        @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob() + ioDispatcher)
    }

    @Dispatcher(MailDispatchers.IO) // This is a @Qualifier
    @Provides
    @Singleton // This is the @Scope
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideAuthConfigProvider(): AuthConfigProvider {
        return object : AuthConfigProvider {
            override fun getMsalConfigResId(): Int = R.raw.auth_config
        }
    }
}
