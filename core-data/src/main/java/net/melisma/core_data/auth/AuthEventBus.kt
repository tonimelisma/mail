package net.melisma.core_data.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central bus publishing authentication-related events.
 * Providers emit [AuthEvent.AuthSuccess] after a successful silent token refresh
 * so other layers (e.g., repositories) can clear stale *needsReauthentication* flags.
 */
@Singleton
class AuthEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AuthEvent> = _events

    fun publish(event: AuthEvent) {
        _events.tryEmit(event)
    }
}

sealed class AuthEvent {
    data class AuthSuccess(
        val accountId: String,
        val providerType: String
    ) : AuthEvent()

    data class AuthFailure(
        val accountId: String,
        val providerType: String,
        val throwable: Throwable? = null
    ) : AuthEvent()
} 