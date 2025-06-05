package net.melisma.core_data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.connectivity.AndroidNetworkMonitor
import net.melisma.core_data.connectivity.NetworkMonitor

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectivityModule {

    @Binds
    abstract fun bindNetworkMonitor(
        androidNetworkMonitor: AndroidNetworkMonitor
    ): NetworkMonitor
} 