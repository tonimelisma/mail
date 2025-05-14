package net.melisma.mail.di

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

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {

    @Singleton
    @Provides
    fun provideApplicationCoroutineScope(
        @Dispatcher(MailDispatchers.IO) ioDispatcher: CoroutineDispatcher
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob() + ioDispatcher)
    }

    @Dispatcher(MailDispatchers.IO)
    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideAuthConfigProvider(): AuthConfigProvider {
        return object : AuthConfigProvider {
            override fun getMsalConfigResId(): Int = R.raw.auth_config
        }
    }
} 