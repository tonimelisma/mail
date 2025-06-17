package net.melisma.core_data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.GlobalScope
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkMonitor {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val onlineUpdates: Flow<Boolean> = callbackFlow {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Check capabilities to ensure it's actually online and validated
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                             capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                trySend(online)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val isInternet =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(isInternet)
            }
        }

        // Check initial state for isOnline
        val initialNetwork = connectivityManager.activeNetwork
        val initialCapabilities = connectivityManager.getNetworkCapabilities(initialNetwork)
        val initiallyOnline =
            initialCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    initialCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        trySend(initiallyOnline)

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "AndroidNetworkMonitor: Missing ACCESS_NETWORK_STATE permission for isOnline flow.")
            trySend(false) // Assume offline if permission is missing
        }

        awaitClose { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }.conflate()

    // Expose as hot StateFlow to prevent re-registering the network callback for every consumer/`first()` call
    override val isOnline: Flow<Boolean> = onlineUpdates.stateIn(
        scope = kotlinx.coroutines.GlobalScope, // This singleton monitor lives for the app lifetime
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    private val wifiUpdates: Flow<Boolean> = callbackFlow {
        val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            private fun checkWifiStatus() {
                val activeNetwork = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                           caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && // Ensure it's also online
                           caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                trySend(wifi)
            }

            override fun onAvailable(network: Network) { checkWifiStatus() }
            override fun onLost(network: Network) { checkWifiStatus() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // We need to check the *active* network's capabilities, not just the changed one,
                // as onCapabilitiesChanged can be for a non-primary network.
                checkWifiStatus()
            }
        }

        // Check initial state for isWifiConnected
        val activeNetworkInitial = connectivityManager.activeNetwork
        val initialWifiCaps = connectivityManager.getNetworkCapabilities(activeNetworkInitial)
        val initiallyWifi = initialWifiCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                              initialWifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                              initialWifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        trySend(initiallyWifi)

        // A general request, we filter by transport type in the callback logic.
        // Listening for specific transport types directly in NetworkRequest is complex because
        // a Wi-Fi network might connect but not have internet, or another network (mobile) might be primary.
        // So, we react to general network changes and then specifically check if the active one is Wi-Fi with internet.
        val genericNetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(genericNetworkRequest, wifiNetworkCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "AndroidNetworkMonitor: Missing ACCESS_NETWORK_STATE permission for isWifiConnected flow.")
            trySend(false) // Assume not on Wi-Fi if permission is missing
        }

        awaitClose { connectivityManager.unregisterNetworkCallback(wifiNetworkCallback) }
    }.conflate()

    override val isWifiConnected: Flow<Boolean> = wifiUpdates.stateIn(
        scope = kotlinx.coroutines.GlobalScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    // Consider making these StateFlows if frequent .first() calls are an issue, for caching last known state.
    // For workers, .first() is fine as they are short-lived. For ViewModels, .stateIn is used.
} 