package net.melisma.mail.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.di.AuthConfigProvider
import net.melisma.mail.R
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {

    @Provides
    @Singleton
    fun provideAuthConfigProvider(): AuthConfigProvider {
        return object : AuthConfigProvider {
            override fun getMsalConfigResId(): Int = R.raw.auth_config
        }
    }
} 