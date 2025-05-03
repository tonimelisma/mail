// File: app/src/main/java/net/melisma/mail/di/AuthModule.kt

package net.melisma.mail.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.AccountRepository
import net.melisma.mail.MicrosoftAccountRepository
import net.melisma.mail.R
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @ApplicationScope
    @Provides
    @Singleton
    fun provideApplicationCoroutineScope(
        ioDispatcher: CoroutineDispatcher
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob() + ioDispatcher)
    }

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

    @Provides
    @Singleton
    fun provideAccountRepository(
        // Dependencies needed by MicrosoftAccountRepository constructor
        microsoftAuthManager: MicrosoftAuthManager,
        @ApplicationScope externalScope: CoroutineScope
    ): AccountRepository {
        // Provide the implementation class
        return MicrosoftAccountRepository(microsoftAuthManager, externalScope)
    }
}