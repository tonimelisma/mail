package net.melisma.backend_google.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveGoogleAccountHolder @Inject constructor() {
    // This should be updated when the user selects an account or after a successful sign-in.
    // For a single Google account app, it can be set after the first successful Google sign-in.
    // Consider loading the last active account ID from SharedPreferences on app start.
    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId

    fun setActiveAccountId(accountId: String?) {
        _activeAccountId.value = accountId
        // TODO: Persist this accountId to SharedPreferences if you want it to
        // survive app restarts as the "last used" Google account.
    }

    fun getActiveAccountIdValue(): String? = _activeAccountId.value
}