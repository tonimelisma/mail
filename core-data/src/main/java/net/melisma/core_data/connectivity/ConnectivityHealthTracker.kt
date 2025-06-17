package net.melisma.core_data.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Lightweight in-memory tracker that observes repeated network failures (DNS, time-outs, etc.)
 * and emits a coarse throttling state for other components (sync producers, controllers).
 *
 * Any component catching a connectivity-related exception should call [recordFailure].
 * A successful network response should call [recordSuccess].  Real connectivity recovery
 * (Android reports validated network) resets the failure window automatically.
 */
@Singleton
class ConnectivityHealthTracker @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    @net.melisma.core_data.di.ApplicationScope externalScope: CoroutineScope,
) {

    enum class ThrottleState { NORMAL, DEGRADED, BLOCKED }

    private val windowMillis = TimeUnit.MINUTES.toMillis(1)
    private val failures: MutableList<Long> = mutableListOf()

    private val _state = MutableStateFlow(ThrottleState.NORMAL)
    val state: StateFlow<ThrottleState> = _state

    init {
        // Observe real connectivity – on a validated connection we immediately reset.
        externalScope.launch(Dispatchers.IO) {
            networkMonitor.isOnline.collectLatest { online ->
                if (online) {
                    reset()
                }
            }
        }
    }

    fun recordFailure() {
        synchronized(failures) {
            val now = System.currentTimeMillis()
            failures.add(now)
            // Remove stale entries outside window
            failures.removeAll { now - it > windowMillis }
            updateStateLocked()
        }
    }

    fun recordSuccess() {
        reset()
    }

    fun isBlocked(): Boolean = _state.value == ThrottleState.BLOCKED

    fun shouldPauseHeavyWork(): Boolean = _state.value == ThrottleState.BLOCKED || _state.value == ThrottleState.DEGRADED

    private fun reset() {
        synchronized(failures) {
            if (failures.isNotEmpty()) {
                failures.clear()
                updateStateLocked()
            }
        }
    }

    private fun updateStateLocked() {
        val now = System.currentTimeMillis()
        failures.removeAll { now - it > windowMillis }
        val count = failures.size
        val newState = when {
            count >= 4 -> ThrottleState.BLOCKED
            count >= 2 -> ThrottleState.DEGRADED
            else -> ThrottleState.NORMAL
        }
        if (newState != _state.value) {
            Timber.tag("Throttle").w("ConnectivityHealthTracker: transitioning ${_state.value} → $newState (failures in window=$count)")
            _state.value = newState
        }
    }
} 