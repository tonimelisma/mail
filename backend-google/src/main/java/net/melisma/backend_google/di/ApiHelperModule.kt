package net.melisma.backend_google.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.backend_google.GmailApiHelper
import net.melisma.core_data.di.ApiHelperType
import javax.inject.Singleton

/**
 * Module that provides the GmailApiHelper for multi-binding
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiHelperModule {

    /**
     * Provides the GmailApiHelper for the multi-binding map
     * with the key "GOOGLE"
     */
    @Provides
    @Singleton
    @ApiHelperType("GOOGLE")
    fun provideGmailApiHelper(gmailApiHelper: GmailApiHelper): Any {
        return gmailApiHelper
    }
}