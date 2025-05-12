package net.melisma.backend_google.auth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveGoogleAccountHolder @Inject constructor() {
    private val TAG = "ActiveGoogleHolder"

    init {
        Log.d(TAG, "ActiveGoogleAccountHolder: Initialized")
    }

    // This should be updated when the user selects an account or after a successful sign-in.
    // For a single Google account app, it can be set after the first successful Google sign-in.
    // Consider loading the last active account ID from SharedPreferences on app start.
    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId

    fun setActiveAccountId(accountId: String?) {
        Log.d(
            TAG,
            "ActiveGoogleAccountHolder: setActiveAccountId called with accountId=${accountId?.take(5)}..."
        )
        val oldAccountId = _activeAccountId.value
        _activeAccountId.value = accountId

        if (oldAccountId != accountId) {
            if (accountId == null) {
                Log.i(TAG, "ActiveGoogleAccountHolder: Active Google account cleared")
            } else {
                Log.i(
                    TAG,
                    "ActiveGoogleAccountHolder: Active Google account ID set to: ${accountId.take(5)}..."
                )
            }
        } else {
            Log.d(TAG, "ActiveGoogleAccountHolder: Active account unchanged")
        }
        // TODO: Persist this accountId to SharedPreferences if you want it to
        // survive app restarts as the "last used" Google account.
    }

    fun getActiveAccountIdValue(): String? {
        val accountId = _activeAccountId.value
        Log.d(
            TAG,
            "ActiveGoogleAccountHolder: getActiveAccountIdValue returning: ${accountId?.take(5)}..."
        )
        return accountId
    }
}