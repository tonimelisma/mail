package net.melisma.core_data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.connectivity.ConnectivityHealthTracker
import net.melisma.core_data.connectivity.NetworkMonitor
import javax.inject.Singleton
import net.melisma.core_data.di.ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object ConnectivityHealthTrackerModule {

    @Provides
    @Singleton
    fun provideConnectivityHealthTracker(
        networkMonitor: NetworkMonitor,
        @ApplicationScope appScope: kotlinx.coroutines.CoroutineScope,
    ): ConnectivityHealthTracker = ConnectivityHealthTracker(networkMonitor, appScope)
} 