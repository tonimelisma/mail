package net.melisma.core_data.connectivity

import kotlinx.coroutines.flow.Flow

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
    val isWifiConnected: Flow<Boolean>
} 