package net.melisma.mail.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.R
import javax.inject.Singleton

/**
 * Hilt Module responsible for providing authentication-related dependencies.
 * @Module indicates that this class contributes bindings to the Hilt dependency graph.
 * @InstallIn(SingletonComponent::class) specifies that the bindings provided by this module
 * will live as long as the application itself (effectively creating singletons).
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    /**
     * Provides a singleton instance of MicrosoftAuthManager.
     * Hilt will ensure that only one instance of this manager is created for the entire app.
     *
     * @param appContext The application context, automatically provided by Hilt via @ApplicationContext.
     * Needed by MicrosoftAuthManager for its initialization.
     * @return A singleton instance of MicrosoftAuthManager.
     */
    @Provides
    @Singleton // Ensures only one instance is created and shared
    fun provideMicrosoftAuthManager(
        @ApplicationContext appContext: Context
    ): MicrosoftAuthManager {
        // Creates the instance, providing the required context and the configuration resource ID.
        // Hilt manages the lifecycle of this instance due to @Singleton and @InstallIn.
        return MicrosoftAuthManager(
            context = appContext,
            configResId = R.raw.auth_config // Reference the MSAL config file in res/raw
        )
    }
}