package net.melisma.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import net.melisma.data.sync.gate.CachePressureGatekeeper
import net.melisma.data.sync.gate.Gatekeeper
import net.melisma.data.sync.gate.NetworkGatekeeper

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncGateModule {

    @Binds
    @IntoSet
    abstract fun bindCachePressureGatekeeper(gatekeeper: CachePressureGatekeeper): Gatekeeper

    @Binds
    @IntoSet
    abstract fun bindNetworkGatekeeper(gatekeeper: NetworkGatekeeper): Gatekeeper
} 